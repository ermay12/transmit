package app.transmit.demo

/**
 * The reviewer demo's sample chats. Friends write in English, the language they share with the user, and the chats
 * are set to Spanish, the language being practiced: automatic translation shows their texts in Spanish. The user's
 * own messages went out in English and show the Spanish they were written in ([Line.wrote]), as sent messages do.
 *
 * The numbers are in 555-0100 to 555-0199, which North America keeps for fiction. Thread ids are negative so they
 * can't match a real conversation's.
 */
internal object DemoChats {
    const val EMMA = "+15555550142"
    const val JAKE = "+15555550187"
    const val MOM = "+15555550110"
    const val PRIYA = "+15555550123"
    const val SAM = "+15555550199"

    val names = mapOf(EMMA to "Emma", JAKE to "Jake", MOM to "Mom", PRIYA to "Priya", SAM to "Sam")

    /**
     * One sample message: from [from] (null for the user), [minutesAgo] when the demo opens. [reactions] are who
     * (null for the user) reacted with what.
     */
    data class Line(
        val from: String?,
        val text: String,
        val minutesAgo: Int,
        val wrote: String? = null,
        val reactions: List<Pair<String?, String>> = emptyList(),
    )

    /**
     * A sample chat, and the [replies] (sender to text) that answer what the user sends in it, in turn. A group's
     * [groupName] is set as the chat's name in Transmit.
     */
    data class Chat(
        val threadId: Long,
        val people: List<String>,
        val lines: List<Line>,
        val replies: List<Pair<String, String>>,
        val groupName: String? = null,
        val unread: Boolean = false,
    )

    val chats = listOf(
        Chat(
            threadId = -101,
            people = listOf(EMMA),
            unread = true,
            lines = listOf(
                Line(EMMA, "Hey! Are you free Friday night?", 95),
                Line(null, "Yes! What do you have in mind?", 90, wrote = "¡Sí! ¿Qué tienes en mente?"),
                Line(EMMA, "There's a new taco place downtown. My treat 🌮", 14),
                Line(EMMA, "Want to meet there at 7?", 13),
            ),
            replies = listOf(
                EMMA to "Perfect, see you there!",
                EMMA to "Haha deal 😄",
                EMMA to "Sounds good to me",
            ),
        ),
        Chat(
            threadId = -102,
            people = listOf(PRIYA, SAM),
            groupName = "Trip to Madrid ✈️",
            lines = listOf(
                Line(PRIYA, "I booked the flights! We land Thursday at 9 in the morning", 200, reactions = listOf(SAM to "🎉", null to "🎉")),
                Line(SAM, "Amazing. I'll look for a place to stay near the center", 190),
                Line(null, "So exciting! I'll take care of dinner on the first night.", 185, wrote = "¡Qué emoción! Yo me encargo de la cena la primera noche."),
                Line(PRIYA, "Don't forget your passport this time 😅", 170, reactions = listOf(SAM to "😂")),
                Line(SAM, "Haha true", 168),
            ),
            replies = listOf(
                PRIYA to "Love it!",
                SAM to "Count me in 🙌",
                PRIYA to "I can't wait",
            ),
        ),
        Chat(
            threadId = -103,
            people = listOf(JAKE),
            lines = listOf(
                Line(JAKE, "Did you finish the book I lent you?", 1_600),
                Line(null, "Almost. I have two chapters left.", 1_590, wrote = "Casi. Me quedan dos capítulos."),
                Line(JAKE, "No spoilers then! The ending is wild 😂", 1_580),
            ),
            replies = listOf(
                JAKE to "Tell me what you think when you're done",
                JAKE to "👍",
            ),
        ),
        Chat(
            threadId = -104,
            people = listOf(MOM),
            lines = listOf(
                Line(MOM, "Call me when you get a chance. Nothing urgent ❤️", 3_000),
                Line(null, "Will do! I'm at work until 6.", 2_990, wrote = "¡Claro! Estoy en el trabajo hasta las 6."),
                Line(MOM, "Okay sweetheart. Don't forget to eat lunch", 2_980),
            ),
            replies = listOf(
                MOM to "Love you too",
                MOM to "Talk soon",
            ),
        ),
    )
}
