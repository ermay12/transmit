package app.transmit.telephony

/**
 * Reactions travel over SMS/MMS as "tapback" text, e.g. `Loved “See you soon”`. iPhones and
 * Google Messages both render these as reactions, so Transmit parses and emits the same format.
 */
object Reactions {
    /** The quick-reaction row, in iMessage tapback order. */
    val QUICK = listOf("❤️", "👍", "👎", "😂", "‼️", "❓")

    private val verbToEmoji = linkedMapOf(
        "Loved" to "❤️",
        "Liked" to "👍",
        "Disliked" to "👎",
        "Laughed at" to "😂",
        "Emphasized" to "‼️",
        "Questioned" to "❓",
    )

    private val removedNounToEmoji = mapOf(
        "a heart" to "❤️",
        "a like" to "👍",
        "a dislike" to "👎",
        "a laugh" to "😂",
        "an exclamation" to "‼️",
        "an exclamation mark" to "‼️",
        "a question mark" to "❓",
    )

    private const val OPEN = "[“\"]"
    private const val CLOSE = "[”\"]"
    private val verbAlternation = verbToEmoji.keys.joinToString("|") { Regex.escape(it) }
    private val tapback = Regex("^($verbAlternation) $OPEN(.*)$CLOSE$", RegexOption.DOT_MATCHES_ALL)
    private val tapbackMedia = Regex("^($verbAlternation) an? (image|photo|video|attachment|audio message|GIF)$")
    private val reacted = Regex("^Reacted (.+?) to $OPEN(.*)$CLOSE$", RegexOption.DOT_MATCHES_ALL)
    private val reactedMedia = Regex("^Reacted (.+?) to an? (image|photo|video|attachment|audio message|GIF)$")
    private val removed = Regex("^Removed (.+?) from $OPEN(.*)$CLOSE$", RegexOption.DOT_MATCHES_ALL)
    private val removedMedia = Regex("^Removed (.+?) from an? (image|photo|video|attachment|audio message|GIF)$")
    private val emojiTo = Regex("^(\\S{1,8}) to $OPEN(.*)$CLOSE$", RegexOption.DOT_MATCHES_ALL)

    data class Parsed(
        val emoji: String,
        /** Quoted text of the target message; null when the target is a media message. */
        val quoted: String?,
        val removed: Boolean,
    )

    fun parse(text: String): Parsed? {
        val t = text.trim().trimStart('‎', '‏')
        if (t.length < 6) return null
        tapback.matchEntire(t)?.let { return Parsed(verbToEmoji.getValue(it.groupValues[1]), it.groupValues[2], false) }
        tapbackMedia.matchEntire(t)?.let { return Parsed(verbToEmoji.getValue(it.groupValues[1]), null, false) }
        reacted.matchEntire(t)?.let { m ->
            val emoji = m.groupValues[1]
            if (isEmoji(emoji)) return Parsed(emoji, m.groupValues[2], false)
        }
        reactedMedia.matchEntire(t)?.let { m ->
            val emoji = m.groupValues[1]
            if (isEmoji(emoji)) return Parsed(emoji, null, false)
        }
        removed.matchEntire(t)?.let { m ->
            val what = m.groupValues[1]
            val emoji = removedNounToEmoji[what] ?: what.takeIf { isEmoji(it) } ?: return@let
            return Parsed(emoji, m.groupValues[2], true)
        }
        removedMedia.matchEntire(t)?.let { m ->
            val what = m.groupValues[1]
            val emoji = removedNounToEmoji[what] ?: what.takeIf { isEmoji(it) } ?: return@let
            return Parsed(emoji, null, true)
        }
        emojiTo.matchEntire(t)?.let { m ->
            val emoji = m.groupValues[1]
            if (isEmoji(emoji)) return Parsed(emoji, m.groupValues[2], false)
        }
        return null
    }

    /** Builds the tapback text for reacting to [targetText] (or a media message when blank). */
    fun format(emoji: String, targetText: String, targetIsMedia: Boolean, remove: Boolean = false): String {
        val quote = quote(targetText)
        val verb = verbToEmoji.entries.firstOrNull { it.value == emoji }?.key
        if (remove) {
            val noun = removedNounToEmoji.entries.firstOrNull { it.value == emoji }?.key ?: emoji
            return if (quote == null) "Removed $noun from ${if (targetIsMedia) "an image" else "a message"}" else "Removed $noun from “$quote”"
        }
        return when {
            quote == null && verb != null -> "$verb an image"
            quote == null -> "Reacted $emoji to an image"
            verb != null -> "$verb “$quote”"
            else -> "Reacted $emoji to “$quote”"
        }
    }

    private fun quote(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return if (t.length > 120) t.take(119).trimEnd() + "…" else t
    }

    /**
     * The message [r] reacts to: the newest of [newestFirst] whose text it quotes, or the newest
     * media message when it quotes none.
     */
    fun target(r: Parsed, newestFirst: List<Message>): Message? = newestFirst.firstOrNull { m ->
        if (r.quoted == null) m.attachments.isNotEmpty() else matches(r.quoted, m.body)
    }

    /** True when [quoted] (possibly truncated with an ellipsis) refers to [candidate]. */
    fun matches(quoted: String, candidate: String): Boolean {
        val q = normalizeQuotes(quoted.trim())
        val c = normalizeQuotes(candidate.trim())
        if (q.isEmpty() || c.isEmpty()) return false
        if (q == c) return true
        val stem = q.removeSuffix("…").removeSuffix("...").trimEnd()
        return stem.length >= 8 && stem.length < q.length && c.startsWith(stem)
    }

    private fun normalizeQuotes(s: String) =
        s.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"').replace(Regex("\\s+"), " ")

    fun isEmoji(s: String): Boolean {
        if (s.isEmpty() || s.length > 16) return false
        if (s.any { it.isLetterOrDigit() && it.code < 0x2000 }) return false
        return s.codePoints().anyMatch { cp ->
            val type = Character.getType(cp)
            type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt() ||
                cp in 0x2600..0x27BF || cp in 0x1F000..0x1FAFF || cp == 0x203C || cp == 0x2049 || cp == 0x2753 || cp == 0x2757
        }
    }
}
