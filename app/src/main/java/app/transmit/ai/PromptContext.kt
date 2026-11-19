package app.transmit.ai

import app.transmit.messaging.Message
import app.transmit.messaging.PhoneNumbers
import app.transmit.messaging.Reactions

/**
 * Earlier messages as translation context, under neutral labels: "Me", "Them", or "Person 1", "Person 2", … in a
 * group. Contact names, the user's own name and phone numbers never go into a prompt; only message text does.
 * Automatic translation, notification workers and the compose box all build their context here.
 */
object PromptContext {
    /**
     * The newest [count] messages of [messages] (in any order) that are before [before], or the newest overall without
     * it, oldest first. Reactions and messages without text are left out. [recipients] are the conversation's other
     * people, which number the speakers in a group.
     */
    fun lines(messages: List<Message>, recipients: List<String>, count: Int, before: Message? = null): List<ContextLine> {
        if (count <= 0) return emptyList()
        val picked = messages.asSequence()
            .filter { before == null || (it.date < before.date && it.key != before.key) }
            .filter { it.body.isNotBlank() && Reactions.parse(it.body) == null }
            .sortedByDescending { it.date }
            .take(count)
            .toList()
            .asReversed()
        val speakers = Speakers(recipients, picked.mapNotNull { it.sender })
        return picked.map { m -> ContextLine(m.outgoing, m.body, if (m.outgoing) 0 else speakers.number(m.sender)) }
    }
}

/**
 * Numbers a conversation's people without naming them. In a group, each person's number is their place among the
 * conversation's [recipients] sorted by phone number, so "Person 2" is the same person in every request about the chat,
 * whatever order the list comes in. A sender who isn't among them ([others]) is numbered after everyone who is.
 * A one-to-one chat has no numbers: the other person is always "Them".
 */
class Speakers(recipients: List<String>, others: List<String> = emptyList()) {
    private val known = recipients.map(PhoneNumbers::matchKey).filter { it.isNotBlank() }.distinct().sorted()
    private val order = known + others.map(PhoneNumbers::matchKey).filter { it.isNotBlank() && it !in known }.distinct().sorted()

    val group get() = known.size > 1

    /** 0 ("Them") outside a group, or for a sender that isn't known; otherwise 1, 2, … */
    fun number(address: String?): Int {
        if (!group || address.isNullOrBlank()) return 0
        return order.indexOf(PhoneNumbers.matchKey(address)) + 1
    }
}
