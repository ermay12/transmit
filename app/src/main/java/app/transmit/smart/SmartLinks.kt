package app.transmit.smart

import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** Something tappable in a message. [start] and [end] index the text it was found in. */
sealed interface SmartEntity {
    val start: Int
    val end: Int

    data class Url(override val start: Int, override val end: Int, val url: String) : SmartEntity
    data class Code(override val start: Int, override val end: Int, val code: String) : SmartEntity
    data class Address(override val start: Int, override val end: Int, val address: String) : SmartEntity
    data class Phone(override val start: Int, override val end: Int, val number: String) : SmartEntity
    data class Email(override val start: Int, override val end: Int, val address: String) : SmartEntity
    data class Date(override val start: Int, override val end: Int, val time: DateParser.Result) : SmartEntity
}

/**
 * Finds what's tappable in message text: links, verification codes, street addresses, plans (dates and times),
 * phone numbers and email addresses. All on device, with no network or system service calls.
 *
 * Bubbles draw at once with [quick] results (links and codes, cheap enough for the main thread) and pick up the
 * rest when [detect] returns from a background thread, so finding them never holds up a message or its translation.
 * The platform text classifier isn't used: on Pixels it finds neither street addresses nor relative dates such as
 * "Friday at 7".
 */
class SmartLinks {
    private val dispatcher = Dispatchers.Default.limitedParallelism(2)
    private val results = LruCache<String, List<SmartEntity>>(400)

    /** What [detect] found for this text, if it has run. */
    fun cached(text: String, sentAt: Long): List<SmartEntity>? = results.get(key(text, sentAt))

    /** Web links and verification codes. [code] is one found in the original when [text] is a translation. */
    fun quick(text: String, code: String?): List<SmartEntity> {
        val out = ArrayList<SmartEntity>()
        for (m in URL.findAll(text)) {
            val url = m.value.let { if (it.startsWith("www.", ignoreCase = true)) "https://$it" else it }
            out += SmartEntity.Url(m.range.first, m.range.last + 1, url)
        }
        val otp = Otp.find(text)?.let { SmartEntity.Code(it.start, it.end, it.code) }
            ?: code?.let { c -> Regex("(?<![\\p{L}\\p{N}])${Regex.escape(c)}(?![\\p{L}\\p{N}])").find(text) }
                ?.let { SmartEntity.Code(it.range.first, it.range.last + 1, code) }
        if (otp != null && out.none { it.overlaps(otp) }) out += otp
        return out.sortedBy { it.start }
    }

    /** Everything tappable in [text], a message sent at [sentAt] (the day "tomorrow" counts from). */
    suspend fun detect(text: String, sentAt: Long, code: String?): List<SmartEntity> {
        val key = key(text, sentAt)
        results.get(key)?.let { return it }
        return withContext(dispatcher) { find(text, sentAt, code).also { results.put(key, it) } }
    }

    private fun find(text: String, sentAt: Long, code: String?): List<SmartEntity> {
        val ref = LocalDateTime.ofInstant(Instant.ofEpochMilli(sentAt), ZoneId.systemDefault())
        val dayFirst = Locale.getDefault().country !in MONTH_FIRST
        // In order of precedence where two overlap.
        val candidates = buildList {
            Addresses.find(text).forEach { add(SmartEntity.Address(it.first, it.last + 1, text.substring(it).replace(SPACES, " "))) }
            DateParser.find(text, ref, dayFirst).forEach { add(SmartEntity.Date(it.start, it.end, it.result)) }
            addAll(phonesAndEmails(text))
        }
        val accepted = quick(text, code).toMutableList()
        for (e in candidates) if (accepted.none { it.overlaps(e) }) accepted += e
        return accepted.sortedBy { it.start }
    }

    /** Linkify's finders: libphonenumber for phone numbers, in the device's region. */
    private fun phonesAndEmails(text: String): List<SmartEntity> {
        val spannable = SpannableString(text)
        if (!Linkify.addLinks(spannable, Linkify.PHONE_NUMBERS or Linkify.EMAIL_ADDRESSES)) return emptyList()
        return spannable.getSpans(0, spannable.length, URLSpan::class.java).mapNotNull { span ->
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val value = text.substring(start, end)
            when {
                // Short numbers are more often codes, prices or ZIP codes than phone numbers.
                span.url.startsWith("tel:") -> SmartEntity.Phone(start, end, value).takeIf { value.count(Char::isDigit) >= 7 }
                span.url.startsWith("mailto:") -> SmartEntity.Email(start, end, value)
                else -> null
            }
        }
    }

    private fun key(text: String, sentAt: Long) = "$sentAt|$text"

    private fun SmartEntity.overlaps(o: SmartEntity) = start < o.end && o.start < end

    private companion object {
        val URL = Regex("(https?://|www\\.)[^\\s<>\"]+[^\\s<>\".,;:!?)\\]]", RegexOption.IGNORE_CASE)
        val SPACES = Regex("\\s+")
        /** Where 3/4 means March 4th. */
        val MONTH_FIRST = setOf("US", "CA", "PH", "FM", "MH", "PW", "BZ")
    }
}
