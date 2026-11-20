package app.transmit.access

import app.transmit.ai.AiClient
import app.transmit.ai.AiException
import app.transmit.ai.CompletionRequest
import app.transmit.ai.CompletionResult
import app.transmit.ai.ModelInfo
import kotlinx.coroutines.delay

/**
 * Debug builds: OpenRouter for the fake backend's dummy key ([FakeAccessBackend.DUMMY_KEY]), so that key never leaves
 * the device. OpenRouter refuses it, and a refused trial key means the trial is over: the first translation used to end
 * every debug trial at once. Translations come back after a pause, as over a network: the demo's sample lines in
 * Spanish, anything else marked with "≈". Other requests (the tutor, a draft's translation) fail as a service problem
 * does. Nothing is spent.
 */
internal object FakeManagedAi : AiClient {
    private const val LATENCY_MS = 1_200L
    private val tag = Regex("<m id=\"([^\"]+)\">(.*?)</m>", RegexOption.DOT_MATCHES_ALL)

    /** The fake for [key], or null for a real key, which goes to OpenRouter. */
    fun forKey(key: String): AiClient? = this.takeIf { key == FakeAccessBackend.DUMMY_KEY }

    /** No list: the model's details are left unknown, and nothing replaces a real key's cached list. */
    override suspend fun listModels(): List<ModelInfo> = throw AiException("The debug fake has no model list.", AiException.Kind.OTHER)

    override suspend fun complete(request: CompletionRequest): CompletionResult {
        delay(LATENCY_MS)
        if (!request.system.startsWith("You translate text messages")) {
            throw AiException("The debug fake only translates messages.", AiException.Kind.OTHER)
        }
        val canned = if ("into Spanish" in request.system) spanish else emptyMap()
        val out = tag.findAll(request.prompt).joinToString("\n") { m ->
            val text = m.groupValues[2].trim()
            "<m id=\"${m.groupValues[1]}\">${canned[text] ?: "≈ $text"}</m>"
        }
        return CompletionResult(out, inputTokens = 0, outputTokens = 0, reportedCostUsd = 0.0, model = request.model)
    }

    /** The demo's sample lines and replies (DemoChats), in Spanish. */
    private val spanish = mapOf(
        "Hey! Are you free Friday night?" to "¡Hola! ¿Estás libre el viernes por la noche?",
        "There's a new taco place downtown. My treat 🌮" to "Hay un sitio nuevo de tacos en el centro. Invito yo 🌮",
        "Want to meet there at 7?" to "¿Quedamos allí a las 7?",
        "Perfect, see you there!" to "¡Perfecto, nos vemos allí!",
        "Haha deal 😄" to "Jaja, trato hecho 😄",
        "Sounds good to me" to "Me parece bien",
        "I booked the flights! We land Thursday at 9 in the morning" to "¡Reservé los vuelos! Aterrizamos el jueves a las 9 de la mañana",
        "Amazing. I'll look for a place to stay near the center" to "Genial. Buscaré un sitio para quedarnos cerca del centro",
        "Don't forget your passport this time 😅" to "No olvides el pasaporte esta vez 😅",
        "Haha true" to "Jaja, es verdad",
        "Love it!" to "¡Me encanta!",
        "Count me in 🙌" to "Cuenta conmigo 🙌",
        "I can't wait" to "Qué ganas",
        "Did you finish the book I lent you?" to "¿Terminaste el libro que te presté?",
        "No spoilers then! The ending is wild 😂" to "¡Nada de spoilers, entonces! El final es una locura 😂",
        "Tell me what you think when you're done" to "Dime qué te parece cuando lo termines",
        "Call me when you get a chance. Nothing urgent ❤️" to "Llámame cuando puedas. Nada urgente ❤️",
        "Okay sweetheart. Don't forget to eat lunch" to "Vale, cariño. No te olvides de comer",
        "Love you too" to "Yo también te quiero",
        "Talk soon" to "Hablamos pronto",
    )
}
