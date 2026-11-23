package app.transmit.ai

/**
 * Built-in prices (USD per million tokens) used to enforce budgets when a provider's model list
 * does not include pricing. Anthropic prices are first-party list prices; others are list prices
 * as of 2026-11-22, marked estimated because they change without the app knowing. The user can
 * override any of them per model in settings.
 */
object Pricing {
    private val anthropic = linkedMapOf(
        "claude-fable-5-2" to Price(8.0, 40.0),
        "claude-mythos-5-2" to Price(8.0, 40.0),
        "claude-fable-5-1" to Price(10.0, 50.0),
        "claude-fable-5" to Price(10.0, 50.0),
        "claude-mythos-5-1" to Price(10.0, 50.0),
        "claude-opus-5-5" to Price(4.0, 20.0),
        "claude-opus-5" to Price(5.0, 25.0),
        "claude-opus-4-8" to Price(5.0, 25.0),
        "claude-opus-4-7" to Price(5.0, 25.0),
        "claude-opus-4-6" to Price(5.0, 25.0),
        "claude-opus-4-5" to Price(5.0, 25.0),
        "claude-sonnet-5-5" to Price(2.0, 10.0),
        "claude-sonnet-5" to Price(2.0, 10.0),
        "claude-sonnet-4-6" to Price(3.0, 15.0),
        "claude-sonnet-4-5" to Price(3.0, 15.0),
        "claude-haiku-5-5" to Price(0.10, 0.50),
        "claude-haiku-4-5" to Price(1.0, 5.0),
    )

    private val openai = linkedMapOf(
        "gpt-6.2-astra" to Price(8.0, 40.0, true),
        "gpt-6.2-sol" to Price(2.0, 10.0, true),
        "gpt-6.1-sol" to Price(2.0, 10.0, true),
        "gpt-6.1-terra" to Price(1.0, 5.0, true),
        "gpt-6-astra" to Price(10.0, 50.0, true),
        "gpt-6-sol" to Price(2.0, 10.0, true),
        "gpt-6-luna" to Price(0.10, 0.50, true),
        "gpt-5.6-sol" to Price(4.0, 20.0, true),
        "gpt-5.6-terra" to Price(2.0, 12.0, true),
        "gpt-5.6-luna" to Price(0.20, 1.20, true),
        "gpt-5.5" to Price(5.0, 30.0, true),
        "gpt-5.4-nano" to Price(0.20, 1.25, true),
        "gpt-5.4-mini" to Price(0.75, 4.50, true),
        "gpt-5.4" to Price(2.50, 15.0, true),
        "gpt-5.2" to Price(1.75, 14.0, true),
        "gpt-5.1" to Price(1.25, 10.0, true),
        "gpt-5-nano" to Price(0.05, 0.40, true),
        "gpt-5-mini" to Price(0.25, 2.0, true),
        "gpt-5" to Price(1.25, 10.0, true),
        "gpt-4.1-nano" to Price(0.10, 0.40, true),
        "gpt-4.1-mini" to Price(0.40, 1.60, true),
        "gpt-4.1" to Price(2.0, 8.0, true),
        "gpt-4o-mini" to Price(0.15, 0.60, true),
        "gpt-4o" to Price(2.50, 10.0, true),
        "o4-mini" to Price(1.10, 4.40, true),
        "o3-mini" to Price(1.10, 4.40, true),
        "o3" to Price(2.0, 8.0, true),
    )

    private val gemini = linkedMapOf(
        // 3.8 and 3.6 Flash rise to $1.50 / $7.50 on 2027-01-01.
        "gemini-3.8-flash" to Price(0.75, 3.75, true),
        "gemini-3.6-flash" to Price(0.75, 3.75, true),
        "gemini-3.5-flash-lite" to Price(0.30, 2.50, true),
        "gemini-3.1-flash-lite" to Price(0.25, 1.50, true),
        "gemini-3.1-pro-preview" to Price(2.0, 12.0, true),
        "gemini-3-flash-preview" to Price(0.50, 3.0, true),
        "gemini-2.5-flash-lite" to Price(0.10, 0.40, true),
        "gemini-2.5-flash" to Price(0.30, 2.50, true),
        "gemini-2.5-pro" to Price(1.25, 10.0, true),
        "gemini-2.0-flash-lite" to Price(0.075, 0.30, true),
        "gemini-2.0-flash" to Price(0.10, 0.40, true),
    )

    fun builtIn(provider: ProviderType, modelId: String): Price? {
        val table = when (provider) {
            ProviderType.ANTHROPIC -> anthropic
            ProviderType.OPENAI -> openai
            ProviderType.GEMINI -> gemini
            ProviderType.OPENROUTER, ProviderType.CUSTOM -> return null
        }
        val id = modelId.removePrefix("models/").lowercase()
        table[id]?.let { return it }
        // Dated snapshots ("claude-haiku-4-5-20251001", "gpt-4o-2024-08-06") share their family's price.
        return table.entries.filter { id.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
    }
}
