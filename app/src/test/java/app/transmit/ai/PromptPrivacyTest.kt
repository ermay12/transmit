package app.transmit.ai

import app.transmit.data.Languages
import app.transmit.data.Level
import app.transmit.data.Romanization
import app.transmit.data.ScriptDisplay
import app.transmit.messaging.DeliveryStatus
import app.transmit.messaging.Message
import app.transmit.messaging.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contact names, the user's own name and phone numbers never go to the AI: every prompt is built from conversations
 * with named contacts, and none of those names or numbers may appear in it. Data safety declares no Contacts sharing
 * on the strength of this.
 */
class PromptPrivacyTest {
    /** The address book the app would show these chats with. Nothing here may reach a prompt. */
    private val contacts = mapOf(
        "+15551234567" to "Ana García",
        "+1 (555) 987-6543" to "Marco Rossi",
        "+34 612 345 678" to "Lucía Fernández",
    )
    private val me = "Eric Maynard"

    private val ana = "+15551234567"
    private val marco = "+1 (555) 987-6543"
    private val lucia = "+34 612 345 678"
    private val group = listOf(ana, marco)

    private fun msg(n: Int, sender: String?, body: String, recipients: List<String>) = Message(
        key = "g$n", id = n.toLong(), kind = MessageKind.RCS, threadId = 7, sender = sender, recipients = recipients,
        body = body, date = 1_000L * n, dateSent = 1_000L * n, outgoing = sender == null, status = DeliveryStatus.READ,
        read = true, subId = 0,
    )

    private val groupChat = listOf(
        msg(1, ana, "Who is bringing the snacks?", group),
        msg(2, marco, "I can, after work", group),
        msg(3, null, "Great, I will bring drinks", group),
        msg(4, ana, "Perfect. See you at the park", group),
        // Stored the way some carriers write it, without the country code.
        msg(5, "5559876543", "Running late, sorry", group),
        msg(6, ana, "No problem!", group),
    )

    private val oneToOne = listOf(
        msg(11, lucia, "Are you free tonight?", listOf(lucia)),
        msg(12, null, "Yes, after dinner", listOf(lucia)),
        msg(13, lucia, "Let's get tacos then", listOf(lucia)),
    )

    /** Every name, part of a name and form of a number that could identify someone. */
    private val forbidden: List<String> = buildList {
        (contacts.values + me).forEach { name ->
            add(name)
            addAll(name.split(' '))
        }
        contacts.keys.forEach { number ->
            add(number)
            val digits = number.filter { it.isDigit() }
            add(digits)
            add(digits.takeLast(10))
            add(digits.takeLast(7))
        }
        add("5559876543")
    }

    private fun assertAnonymous(prompt: String) {
        for (token in forbidden) {
            // Whole words: "Ana" must not trip on "explanation".
            val word = Regex("(?<![\\p{L}\\d])" + Regex.escape(token) + "(?![\\p{L}\\d])", RegexOption.IGNORE_CASE)
            assertFalse("\"$token\" is in the prompt:\n$prompt", word.containsMatchIn(prompt))
        }
        // Nor anything shaped like a phone number.
        assertFalse("A number is in the prompt:\n$prompt", Regex("\\d[\\d\\s().-]{5,}\\d").containsMatchIn(prompt))
    }

    private val en = Languages.get("en")
    private val es = Languages.get("es")
    private val ja = Languages.get("ja")
    private val zh = Languages.get("zh-Hans")

    @Test
    fun automaticTranslationOfAGroupUsesNumberedPeople() {
        // As TranslationManager does for an incoming message (the notification worker too): the newest one, with the
        // earlier ones as context.
        val newest = groupChat.last()
        val context = PromptContext.lines(groupChat, group, count = 8, before = newest)
        val prompt = Prompts.translateUser(listOf("1" to newest.body), context)
        assertAnonymous(prompt)
        assertAnonymous(Prompts.translateSystem(es))
        assertTrue(prompt.contains("Person 1: Who is bringing the snacks?"))
        assertTrue(prompt.contains("Person 2: I can, after work"))
        assertTrue(prompt.contains("Me: Great, I will bring drinks"))
        // The same person, however the carrier wrote the number.
        assertTrue(prompt.contains("Person 2: Running late, sorry"))
        // The message being translated isn't part of its own context.
        assertFalse(prompt.contains(": No problem!"))
        assertTrue(prompt.contains("<m id=\"1\">No problem!</m>"))
    }

    @Test
    fun oneToOneContextSaysThem() {
        val context = PromptContext.lines(oneToOne, listOf(lucia), count = 8, before = oneToOne.last())
        val prompt = Prompts.translateUser(listOf("1" to oneToOne.last().body), context)
        assertAnonymous(prompt)
        assertTrue(prompt.contains("Them: Are you free tonight?"))
        assertTrue(prompt.contains("Me: Yes, after dinner"))
    }

    @Test
    fun draftTranslationContextIsAnonymous() {
        // As the compose box does: the chat's newest messages, newest first, then the draft in either direction.
        val newestFirst = groupChat.sortedByDescending { it.date }
        val context = PromptContext.lines(newestFirst, group, count = 8)
        assertEquals(groupChat.map { it.body }, context.map { it.text })
        assertAnonymous(Prompts.translateUser(listOf("1" to "I will be there at noon"), context))
        assertAnonymous(Prompts.translateUser(listOf("1" to "Estaré allí al mediodía"), context))
    }

    @Test
    fun tutorPromptsAreAnonymous() {
        val original = oneToOne.first().body
        val translation = "¿Estás libre esta noche?"
        assertAnonymous(Prompts.tutorSystem(en, es, Level.BEGINNER))
        assertAnonymous(Prompts.explainUser(original, translation, es))
        assertAnonymous(Prompts.feedbackSystem(en, es, Level.INTERMEDIATE))
        assertAnonymous(Prompts.feedbackSystem(en, zh, Level.BEGINNER, ScriptDisplay.ROMANIZED))
        assertAnonymous(Prompts.feedbackUser("Yes, after dinner", "Sí, después de cenar", en, es))
        assertAnonymous(Prompts.feedbackUser(null, "Sí, después de cenar", en, es))
    }

    @Test
    fun romanizationPromptsAreAnonymous() {
        assertAnonymous(Prompts.romanizeSystem(ja, Romanization.ROMAJI))
        assertAnonymous(Prompts.translateUser(listOf("1" to "今夜は暇？"), emptyList()))
        assertAnonymous(Prompts.charactersSystem(zh, Romanization.PINYIN))
        assertAnonymous(Prompts.charactersUser(listOf("1" to "ni hao"), mapOf("1" to "Hi there")))
    }

    @Test
    fun labelsStayWithTheirPersonAcrossRequests() {
        // A later window that starts with Marco still calls him Person 2, and the list's order doesn't matter.
        val later = PromptContext.lines(groupChat.drop(1), group.reversed(), count = 8)
        assertEquals("Person 2", later.first().speaker)
        assertEquals("Person 1", later.first { it.text == "No problem!" }.speaker)
        // Someone who isn't among the recipients is numbered after them, never named.
        val stranger = msg(20, "+15550001111", "Hi all", group)
        assertEquals("Person 3", PromptContext.lines(groupChat + stranger, group, count = 8).last().speaker)
    }

    @Test
    fun contextKeepsTheNewestAndSkipsReactions() {
        val withReaction = oneToOne + msg(14, null, "Loved “Let's get tacos then”", listOf(lucia))
        val lines = PromptContext.lines(withReaction, listOf(lucia), count = 2)
        assertEquals(listOf("Yes, after dinner", "Let's get tacos then"), lines.map { it.text })
        assertTrue(PromptContext.lines(oneToOne, listOf(lucia), count = 0).isEmpty())
    }
}
