package app.transmit.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import com.anthropic.models.models.ModelInfo as SdkModelInfo

/** Claude via the official Anthropic Java SDK. */
class AnthropicAiClient(apiKey: String, baseUrl: String? = null) : AiClient {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (!baseUrl.isNullOrBlank()) baseUrl(baseUrl) }
        .timeout(Duration.ofSeconds(120))
        .maxRetries(2)
        .build()

    override suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        call {
            val out = mutableListOf<ModelInfo>()
            var page = client.models().list()
            while (true) {
                page.data().forEach { out += it.toInfo() }
                // hasNextPage() ignores has_more and would fetch one extra, empty page.
                if (page.hasMore().orElse(false) != true || !page.hasNextPage()) break
                page = page.nextPage()
            }
            out
        }
    }

    private fun SdkModelInfo.toInfo(): ModelInfo {
        val efforts = runCatching {
            val e = capabilities().orElse(null)?.effort() ?: return@runCatching emptyList()
            if (!e.supported()) return@runCatching emptyList()
            buildList {
                if (e.low().supported()) add(Effort.LOW)
                if (e.medium().supported()) add(Effort.MEDIUM)
                if (e.high().supported()) add(Effort.HIGH)
                if (e.xhigh().orElse(null)?.supported() == true) add(Effort.XHIGH)
                if (e.max().supported()) add(Effort.MAX)
            }
        }.getOrDefault(emptyList())
        return ModelInfo(
            id = id(),
            name = displayName(),
            contextWindow = runCatching { maxInputTokens().orElse(null) }.getOrNull(),
            maxOutput = runCatching { maxTokens().orElse(null) }.getOrNull(),
            price = Pricing.builtIn(ProviderType.ANTHROPIC, id()),
            efforts = efforts,
            created = runCatching { createdAt().toEpochSecond() }.getOrNull(),
        )
    }

    override suspend fun complete(request: CompletionRequest): CompletionResult = withContext(Dispatchers.IO) {
        val builder = MessageCreateParams.builder()
            .model(request.model)
            .maxTokens(request.maxTokens.toLong())
            .system(request.system)
            .addUserMessage(request.prompt)
        sdkEffort(request.effort)?.let { builder.outputConfig(OutputConfig.builder().effort(it).build()) }
        if (request.model in SERVER_FALLBACK_MODELS) {
            // Server-side refusal fallback: a declined request is retried on a suitable model.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        val message = call { client.messages().create(builder.build()) }
        if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
            throw AiException("The model declined to process this message.", AiException.Kind.REFUSAL)
        }
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
        val usage = message.usage()
        CompletionResult(
            text = text,
            inputTokens = usage.inputTokens(),
            outputTokens = usage.outputTokens(),
            model = runCatching { message.model().asString() }.getOrDefault(request.model),
        )
    }

    private fun sdkEffort(effort: Effort): OutputConfig.Effort? = when (effort) {
        Effort.LOW -> OutputConfig.Effort.LOW
        Effort.MEDIUM -> OutputConfig.Effort.MEDIUM
        Effort.HIGH -> OutputConfig.Effort.HIGH
        Effort.XHIGH -> OutputConfig.Effort.XHIGH
        Effort.MAX -> OutputConfig.Effort.MAX
        Effort.DEFAULT, Effort.NONE, Effort.MINIMAL -> null
    }

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: UnauthorizedException) {
        throw AiException("Anthropic rejected the API key.", AiException.Kind.AUTH, e)
    } catch (e: PermissionDeniedException) {
        throw AiException("This API key can't use that model.", AiException.Kind.AUTH, e)
    } catch (e: RateLimitException) {
        throw AiException("Anthropic rate limit reached. Try again shortly.", AiException.Kind.RATE_LIMIT, e)
    } catch (e: NotFoundException) {
        throw AiException("Model not found. Pick another model in settings.", AiException.Kind.BAD_REQUEST, e)
    } catch (e: BadRequestException) {
        throw AiException(e.message ?: "Anthropic rejected the request.", AiException.Kind.BAD_REQUEST, e)
    } catch (e: AnthropicServiceException) {
        throw AiException("Anthropic error ${e.statusCode()}: ${e.message}", AiException.Kind.OTHER, e)
    } catch (e: AnthropicIoException) {
        throw AiException("Network error talking to Anthropic.", AiException.Kind.NETWORK, e)
    } catch (e: AnthropicException) {
        throw AiException(e.message ?: "Anthropic request failed.", AiException.Kind.OTHER, e)
    }

    private companion object {
        val SERVER_FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
    }
}
