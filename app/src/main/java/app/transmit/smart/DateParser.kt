package app.transmit.smart

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.IsoFields

/**
 * Finds date and time phrases in a message ("tomorrow at 3pm", "Fri 7-9", "Nov 20", "vendredi à 19h") and turns
 * them into calendar times, relative to when the message was sent.
 *
 * It knows English, the common day, month and time words of Spanish, French, German, Italian and Portuguese, and
 * those of Chinese, Japanese and Korean; numeric dates and clock times work in any language.
 */
object DateParser {
    data class Result(
        val start: LocalDateTime,
        /** Null when the phrase gave no end time. */
        val end: LocalDateTime?,
        /** A day with no time of day. */
        val allDay: Boolean,
        /** The phrase names a particular day (weekday, month or numeric date), not just "today" or "tomorrow". */
        val namedDay: Boolean,
    ) {
        /** Worth offering as a calendar event: a time, or a named day, that isn't already over. */
        fun isPlan(now: LocalDateTime): Boolean = (!allDay || namedDay) && !start.toLocalDate().isBefore(now.toLocalDate())
    }

    /** A date phrase at [start] until [end] in the text. */
    data class Found(val start: Int, val end: Int, val result: Result)

    /**
     * The plans mentioned in [text]: runs of day and time words ("Friday at 7", "Nov 28 from 6-9pm") that name a
     * time, or a particular day, that hasn't passed by [now]. A lone weak word ("tomorrow", "at 7", "24/7") isn't one.
     */
    fun find(text: String, ref: LocalDateTime, dayFirst: Boolean = false, now: LocalDateTime = LocalDateTime.now()): List<Found> {
        if (text.length > MAX_TEXT || text.none { it.isLetterOrDigit() }) return emptyList()
        val s = fold(text)
        class Anchor(val start: Int, val end: Int, val strong: Boolean)
        val anchors = buildList {
            for ((re, strong) in ANCHORS) for (m in re.findAll(s)) add(Anchor(m.range.first, m.range.last + 1, strong))
        }.sortedBy { it.start }
        if (anchors.isEmpty()) return emptyList()

        // Join anchors separated only by little words ("Friday at 7", "Nov 28 from 6-9pm", "tomorrow morning at 9").
        val groups = ArrayList<MutableList<Anchor>>()
        for (a in anchors) {
            val last = groups.lastOrNull()
            val end = last?.maxOf { it.end }
            if (last != null && end != null && (a.start <= end || (a.start - end <= MAX_GAP && JOIN.matches(s.substring(end, a.start).replace(CJK_JOIN, " "))))) last += a
            else groups += mutableListOf(a)
        }
        return groups.mapNotNull { g ->
            if (g.none { it.strong } && g.size < 2) return@mapNotNull null
            var start = g.minOf { it.start }
            var end = g.maxOf { it.end }
            BEFORE_WORDS.find(s.substring(0, start))?.let { start = it.range.first }
            AFTER_WORDS.find(s.substring(end))?.let { end += it.range.last + 1 }
            val result = parse(text.substring(start, end), ref, dayFirst)?.takeIf { it.isPlan(now) } ?: return@mapNotNull null
            Found(start, end, result)
        }
    }

    fun parse(phrase: String, ref: LocalDateTime, dayFirst: Boolean = false): Result? {
        var s = " " + fold(phrase) + " "
        val today = ref.toLocalDate()

        /** Blanks out a matched part so later patterns don't read its numbers again. */
        fun consume(m: MatchResult) {
            s = s.replaceRange(m.range, " ".repeat(m.value.length))
        }

        fun <T> take(re: Regex, read: (MatchResult) -> T?): T? {
            val m = re.find(s) ?: return null
            return read(m)?.also { consume(m) }
        }

        // Dates first, so their numbers aren't read as times.
        var date: LocalDate? = take(ISO_DATE) { m -> dateOf(m.int(1), m.int(2), m.int(3)) }
            ?: take(SLASH_DATE) { m -> numericDate(m.int(1), m.int(2), m.intOrNull(3), dayFirst, today) }
            ?: take(DOT_DATE) { m -> numericDate(m.int(1), m.int(2), m.intOrNull(3), dayFirst = true, today) }
            ?: take(MONTH_DAY) { m -> resolveYear(MONTHS.getValue(m.groupValues[1]), m.int(2), m.intOrNull(3), today) }
            ?: take(DAY_MONTH) { m -> resolveYear(MONTHS.getValue(m.groupValues[2]), m.int(1), m.intOrNull(3), today) }
            ?: take(CJK_MONTH_DAY) { m -> resolveYear(m.int(1), m.int(2), null, today) }
        var named = date != null

        val pm = PM_WORDS.containsMatchIn(s) || CJK_PM.containsMatchIn(s)
        val am = !pm && (AM_WORDS.containsMatchIn(s) || CJK_AM.containsMatchIn(s))
        val daypart = if (pm) Meridiem.PM else if (am) Meridiem.AM else null
        val next = NEXT_WORDS.containsMatchIn(s) || CJK_NEXT.containsMatchIn(s)

        // Day words carry no digits, so they stay in place: "8 tonight" still reads as a time below.
        val weekday = if (date != null) null
        else WEEKDAY.find(s)?.let { WEEKDAYS.getValue(it.groupValues[1]) } ?: CJK_WEEKDAY.find(s)?.let { CJK_WEEKDAYS.getValue(it.groupValues[1]) }
        var relative = false
        if (date == null && weekday == null) {
            date = (RELATIVE + CJK_RELATIVE).firstNotNullOfOrNull { (re, days) -> if (re.containsMatchIn(s)) today.plusDays(days) else null }
            relative = date != null
        }
        if (date == null && weekday == null) {
            date = take(ORDINAL_DAY) { m -> nextDayOfMonth(today, m.int(1)) }
            named = date != null
        }
        if (weekday != null) named = true

        val time = parseTime(s, daypart)
        if (date == null && weekday == null && time == null) return null

        var day = date ?: weekday?.let { nextWeekday(today, it, next) } ?: today
        if (time == null) return Result(day.atStartOfDay(), null, allDay = true, namedDay = named)

        var start = time.start
        val refTime = ref.toLocalTime()
        if (time.midnight) {
            day = day.plusDays(1)
        } else {
            // "at 9" sent at 6pm means 9pm.
            if (time.ambiguous && day == today && start.isBefore(refTime) && start.hour < 12 && start.plusHours(12).isAfter(refTime)) {
                start = start.plusHours(12)
            }
            // A time already past means the next one: "at 3" sent at 5pm is tomorrow, "Friday 7pm" sent Friday night is next week.
            val implicitDay = date == null && weekday == null
            val sameWeekday = weekday != null && day == today
            if ((implicitDay || sameWeekday) && !relative && day.atTime(start).isBefore(ref.minusMinutes(30))) {
                day = day.plusDays(if (sameWeekday) 7 else 1)
            }
        }
        val begin = day.atTime(start)
        val end = time.end?.let { e ->
            // "11-1pm" crosses noon; "10pm-1" crosses midnight.
            var end = day.atTime(e)
            while (!end.isAfter(begin)) end = end.plusHours(12)
            end
        }
        return Result(begin, end, allDay = false, namedDay = named)
    }

    private enum class Meridiem { AM, PM }

    private data class Time(val start: LocalTime, val end: LocalTime? = null, val ambiguous: Boolean = false, val midnight: Boolean = false)

    private data class Token(val start: Int, val end: Int, val time: Time, val lead: String = "")

    private fun parseTime(s: String, daypart: Meridiem?): Time? {
        RANGE.find(s)?.let { m -> range(m, daypart)?.let { return it } }
        val tokens = buildList {
            NOON.findAll(s).forEach { add(Token(it.range.first, it.range.last + 1, Time(LocalTime.NOON))) }
            MIDNIGHT.findAll(s).forEach { add(Token(it.range.first, it.range.last + 1, Time(LocalTime.MIDNIGHT, midnight = true))) }
            TWELVE_HOUR.findAll(s).forEach { m ->
                val h = m.int(1)
                val min = m.intOrNull(2) ?: 0
                val mer = if ((m.groupValues[3] + m.groupValues[4]) == "a") Meridiem.AM else Meridiem.PM
                if (h in 1..12 && min < 60) add(Token(m.range.first, m.range.last + 1, Time(clock(h, min, mer))))
            }
            H_CLOCK.findAll(s).forEach { m ->
                val h = m.int(1)
                val min = m.intOrNull(2) ?: 0
                if (h <= 24 && min < 60) add(Token(m.range.first, m.range.last + 1, Time(LocalTime.of(h % 24, min))))
            }
            COLON.findAll(s).forEach { m ->
                val h = m.int(1)
                val min = m.int(2)
                if (h > 23 || min > 59) return@forEach
                val time = if (h >= 13 || h == 0 || m.groupValues[1].startsWith("0")) Time(LocalTime.of(h, min))
                else Time(clock(h, min, daypart), ambiguous = daypart == null)
                add(Token(m.range.first, m.range.last + 1, time))
            }
            CJK_TIME.findAll(s).forEach { m ->
                val h = m.int(1)
                val min = if (m.groupValues[3].isNotEmpty()) 30 else m.intOrNull(2) ?: 0
                when {
                    min > 59 || h > 24 -> Unit
                    h >= 13 || h == 0 || h == 24 -> add(Token(m.range.first, m.range.last + 1, Time(LocalTime.of(h % 24, min))))
                    else -> add(Token(m.range.first, m.range.last + 1, Time(clock(h, min, daypart), ambiguous = daypart == null)))
                }
            }
            (BARE_HOUR.findAll(s) + DAYPART_HOUR.findAll(s)).forEach { m ->
                val h = m.int(2)
                val min = m.intOrNull(3) ?: 0
                val lead = m.groupValues[1]
                when {
                    min > 59 -> Unit
                    h in 13..23 -> add(Token(m.range.first, m.range.last + 1, Time(LocalTime.of(h, min)), lead))
                    h in 1..12 -> add(Token(m.range.first, m.range.last + 1, Time(clock(h, min, daypart), ambiguous = daypart == null), lead))
                }
            }
        }.sortedBy { it.start }
        val first = tokens.firstOrNull() ?: return null
        // "7pm - 9pm", "19h à 21h", "6pm until 8"
        val second = tokens.firstOrNull { it.start >= first.end }
        if (second != null) {
            val gap = s.substring(first.end, second.start)
            if (RANGE_GAP.matches(gap) || (gap.isBlank() && second.lead in RANGE_WORDS)) return first.time.copy(end = second.time.start)
        }
        return first.time
    }

    private fun range(m: MatchResult, daypart: Meridiem?): Time? {
        val h1 = m.int(1)
        val m1 = m.intOrNull(2) ?: 0
        val h2 = m.int(3)
        val m2 = m.intOrNull(4) ?: 0
        val suffix = m.groupValues[5]
        if (h1 > 23 || h2 > 23 || m1 > 59 || m2 > 59) return null
        if (suffix == "h" || suffix == "uhr" || h1 > 12 || h2 > 12) return Time(LocalTime.of(h1, m1), LocalTime.of(h2, m2))
        val explicit = when {
            suffix.startsWith("a") -> Meridiem.AM
            suffix.startsWith("p") -> Meridiem.PM
            else -> null
        }
        val mer = explicit ?: daypart
        var start = clock(h1, m1, mer)
        val end = clock(h2, m2, mer)
        // "7-9pm" is 7pm to 9pm, but "11-1pm" is 11am to 1pm.
        if (explicit == Meridiem.PM && start.isAfter(end) && h1 < 12) start = LocalTime.of(h1, m1)
        return Time(start, end, ambiguous = mer == null)
    }

    /** A 12-hour clock reading. With no am/pm, 1–7 are taken as afternoon or evening and 8–11 as morning. */
    private fun clock(h: Int, min: Int, mer: Meridiem?): LocalTime {
        val hour = when (mer) {
            Meridiem.AM -> if (h == 12) 0 else h
            Meridiem.PM -> if (h == 12) 12 else h + 12
            null -> if (h in 1..7) h + 12 else h
        }
        return LocalTime.of(hour % 24, min)
    }

    private fun nextWeekday(today: LocalDate, target: DayOfWeek, next: Boolean): LocalDate {
        var day = today.plusDays(((target.value - today.dayOfWeek.value + 7) % 7).toLong())
        if (next) {
            if (day == today) day = day.plusDays(7)
            // "next Friday" is the Friday of next week.
            if (day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) == today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)) day = day.plusDays(7)
        }
        return day
    }

    /** "the 15th": this month's, or next month's once this month's has passed. */
    private fun nextDayOfMonth(today: LocalDate, day: Int): LocalDate? = (0L..2L).firstNotNullOfOrNull { k ->
        val month = today.withDayOfMonth(1).plusMonths(k)
        if (day in 1..month.lengthOfMonth()) month.withDayOfMonth(day).takeIf { !it.isBefore(today) } else null
    }

    private fun numericDate(a: Int, b: Int, year: Int?, dayFirst: Boolean, today: LocalDate): LocalDate? {
        val (day, month) = when {
            a > 12 -> a to b
            b > 12 -> b to a
            dayFirst -> a to b
            else -> b to a
        }
        return resolveYear(month, day, year, today)
    }

    private fun dateOf(year: Int, month: Int, day: Int): LocalDate? = runCatching { LocalDate.of(year, month, day) }.getOrNull()

    /** A date written without a year is this year's, or next year's once this year's is well past. */
    private fun resolveYear(month: Int, day: Int, year: Int?, today: LocalDate): LocalDate? {
        if (year != null) return dateOf(if (year < 100) 2000 + year else year, month, day)
        val d = dateOf(today.year, month, day) ?: return dateOf(today.year + 1, month, day)
        return if (d.isBefore(today.minusDays(30))) dateOf(today.year + 1, month, day) else d
    }

    private fun MatchResult.int(group: Int) = groupValues[group].toInt()
    private fun MatchResult.intOrNull(group: Int) = groupValues[group].takeIf { it.isNotEmpty() }?.toInt()

    /**
     * Lowercase, without accents, one character for one, so that positions in the result are positions in [text]:
     * "Mañana à 19h" becomes "manana a 19h".
     */
    internal fun fold(text: String): String {
        val out = CharArray(text.length)
        for (i in text.indices) {
            val c = text[i]
            out[i] = when {
                c.code < 128 -> c.lowercaseChar()
                c in DASHES -> '-'
                c == '’' || c == '‘' -> '\''
                c.isWhitespace() -> ' '
                else -> {
                    val lower = c.lowercaseChar()
                    val d = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)
                    if (d.length > 1 && d.substring(1).all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }) d[0] else lower
                }
            }
        }
        return String(out)
    }

    private const val DASHES = "‐‑‒–—−~"
    private const val MAX_TEXT = 2000
    private const val MAX_GAP = 24

    /**
     * Not inside a word or number. Only alphabetic scripts count as word characters: Chinese, Japanese and Korean
     * put digits right against their words ("金曜日の19:30").
     */
    private const val WORD_CHARS = "a-z\\u00C0-\\u024F\\u0370-\\u03FF\\u0400-\\u04FF\\p{N}"
    private const val B = "(?<![$WORD_CHARS])"
    private const val E = "(?![$WORD_CHARS])"

    private val MONTHS: Map<String, Int> = buildMap {
        fun add(month: Int, vararg names: String) = names.forEach { put(it, month) }
        add(1, "january", "jan", "enero", "janvier", "janv", "januar", "gennaio", "janeiro")
        add(2, "february", "feb", "febrero", "fevrier", "fevr", "februar", "febbraio", "fevereiro")
        add(3, "march", "mar", "marzo", "mars", "marz", "maerz", "marco")
        add(4, "april", "apr", "abril", "avril", "aprile")
        add(5, "may", "mayo", "mai", "maggio", "maio")
        add(6, "june", "jun", "junio", "juin", "juni", "giugno", "junho")
        add(7, "july", "jul", "julio", "juillet", "juil", "juli", "luglio", "julho")
        add(8, "august", "aug", "agosto", "aout")
        add(9, "september", "sep", "sept", "septiembre", "setiembre", "septembre", "settembre", "setembro")
        add(10, "october", "oct", "octubre", "octobre", "oktober", "ottobre", "outubro")
        add(11, "november", "nov", "noviembre", "novembre", "novembro")
        add(12, "december", "dec", "diciembre", "dic", "decembre", "dezember", "dicembre", "dezembro")
    }
    private val MONTH_NAMES = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")
    private const val YEAR = "(?:,?\\s*(?:de\\s+)?(\\d{4}))?"
    private const val ORD = "(?:st|nd|rd|th|er|º|°)?"
    private val MONTH_DAY = Regex("$B($MONTH_NAMES)\\.?\\s+(?:the\\s+)?(\\d{1,2})$ORD$E$YEAR")
    private val DAY_MONTH = Regex("$B(\\d{1,2})$ORD\\.?\\s*(?:de\\s+|of\\s+)?($MONTH_NAMES)\\.?$E$YEAR")
    private val ISO_DATE = Regex("$B(\\d{4})-(\\d{1,2})-(\\d{1,2})$E")
    private val SLASH_DATE = Regex("$B(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}|\\d{2}))?$E")
    // "20.11." or "20.11.2026"; a bare "20.30" is a time.
    private val DOT_DATE = Regex("$B(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4}|\\d{2})?(?![\\p{N}])")
    private val ORDINAL_DAY = Regex("$B(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)$E")

    private val WEEKDAYS: Map<String, DayOfWeek> = buildMap {
        fun add(day: DayOfWeek, vararg names: String) = names.forEach { put(it, day) }
        add(DayOfWeek.MONDAY, "monday", "mon", "lunes", "lundi", "montag", "lunedi", "segunda")
        add(DayOfWeek.TUESDAY, "tuesday", "tues", "tue", "martes", "mardi", "dienstag", "martedi", "terca")
        add(DayOfWeek.WEDNESDAY, "wednesday", "weds", "wed", "miercoles", "mercredi", "mittwoch", "mercoledi", "quarta")
        add(DayOfWeek.THURSDAY, "thursday", "thurs", "thur", "thu", "jueves", "jeudi", "donnerstag", "giovedi", "quinta")
        add(DayOfWeek.FRIDAY, "friday", "fri", "viernes", "vendredi", "freitag", "venerdi", "sexta")
        add(DayOfWeek.SATURDAY, "saturday", "sat", "sabado", "samedi", "samstag", "sonnabend", "sabato")
        add(DayOfWeek.SUNDAY, "sunday", "sun", "domingo", "dimanche", "sonntag", "domenica")
    }
    private val WEEKDAY = Regex("$B(${WEEKDAYS.keys.sortedByDescending { it.length }.joinToString("|")})\\.?$E")
    private val NEXT_WORDS = Regex("$B(?:next|coming|prochaine?|proxim[oa]|nachste[nm]?|naechste[nm]?|kommende[nm]?|prossim[oa]|que vem)$E")

    /** Relative day words, longest phrases first ("day after tomorrow" before "tomorrow"). */
    private val RELATIVE: List<Pair<Regex, Long>> = listOf(
        "day after tomorrow|pasado manana|apres-?demain|apres demain|ubermorgen|uebermorgen|dopodomani|depois de amanha" to 2L,
        "tomorrow|tmrw|tmr|tmw|2morrow|demain|domani|amanha" to 1L,
        // "mañana" and "Morgen" also mean "morning": "esta mañana", "heute Morgen".
        "(?<!(?:la|esta|por la|de la) )manana|(?<!(?:heute|gestern|jeden) )morgen" to 1L,
        "today|tonight|tonite|hoy|esta noche|aujourd'?hui|ce soir|heute|oggi|stasera|hoje" to 0L,
        "yesterday|ayer|hier|gestern|ieri|ontem" to -1L,
    ).map { (words, days) -> Regex("$B(?:$words)$E") to days }

    private val PM_WORDS = Regex(
        "$B(?:afternoon|evening|night|tonight|tonite|tarde|noche|apres-midi|soir|soiree|nachmittags?|abends?|pomeriggio|sera|stasera|noite|nuit)$E",
    )
    private val AM_WORDS = Regex(
        "$B(?:morning|matin|morgens|fruh|frueh|vormittags?|mattin[ao]|manha|(?:la|esta|por la|de la) manana|(?:heute|gestern) morgen)$E",
    )

    private val NOON = Regex("$B(?:noon|midday|mediodia|midi|mittags?|mezzogiorno|meio-dia|meio dia)$E")
    private val MIDNIGHT = Regex("$B(?:midnight|medianoche|minuit|mitternacht|mezzanotte|meia-noite)$E")
    private const val MERIDIEM = "[ap]\\.?\\s?m\\.?"
    // "3pm", "3:30 p.m.", "7p"; a bare "a" needs no space ("3 a 5" is a Spanish range).
    private val TWELVE_HOUR = Regex("$B(\\d{1,2})(?:[:.](\\d{2}))?(?:\\s?([ap])\\.?\\s?m\\.?|([ap]))(?![\\p{L}\\p{N}])")
    // "19h", "19h30", "7 Uhr"
    private val H_CLOCK = Regex("$B(\\d{1,2})\\s?(?:h|uhr)(?:\\s?(\\d{2}))?$E")
    private val COLON = Regex("$B(\\d{1,2})[:.](\\d{2})(?!\\p{N})(?!\\s?$MERIDIEM(?!\\p{L})|[ap]$E|\\s?h$E|\\s?uhr)")
    private const val NOT_A_CLOCK = "(?![\\p{N}:./]|\\s?$MERIDIEM(?!\\p{L})|\\s?(?:h|uhr)$E|[ap]$E)"
    // "at 7", "a las 7", "um 7:30"
    private val BARE_HOUR = Regex(
        "$B(at|@|around|by|from|after|before|until|till|las|la|a|as|alle|um|ore|vers|des|ab|de)\\s+(\\d{1,2})(?:[:.](\\d{2}))?$NOT_A_CLOCK$E",
    )
    // "8 tonight", "7 in the evening"
    private val DAYPART_HOUR = Regex(
        "$B()(\\d{1,2})(?:[:.](\\d{2}))?(?=\\s+(?:tonight|tonite|this (?:morning|afternoon|evening)|in the (?:morning|afternoon|evening)|at night" +
            "|de la (?:manana|tarde|noche)|du (?:matin|soir)|di sera|da (?:manha|tarde|noite))$E)",
    )
    private val RANGE = Regex(
        "$B(\\d{1,2})(?:[:.](\\d{2}))?\\s?(?:-|to|till|until|a|al|hasta|bis|alle|ate)\\s?(\\d{1,2})(?:[:.](\\d{2}))?\\s?($MERIDIEM|h|uhr)?(?![\\p{L}\\p{N}])",
    )
    private val RANGE_WORDS = setOf("until", "till", "a", "alle")

    // ---- Chinese, Japanese, Korean ------------------------------------------------------------------

    private val CJK_MONTH_DAY = Regex("(?<!\\p{N})(\\d{1,2})\\s?[月월]\\s?(\\d{1,2})\\s?[日일号號]")
    private val CJK_WEEKDAYS: Map<String, DayOfWeek> = buildMap {
        fun add(day: DayOfWeek, vararg names: String) = names.forEach { put(it, day) }
        add(DayOfWeek.MONDAY, "月曜", "星期一", "周一", "週一", "礼拜一", "禮拜一", "월요일")
        add(DayOfWeek.TUESDAY, "火曜", "星期二", "周二", "週二", "礼拜二", "禮拜二", "화요일")
        add(DayOfWeek.WEDNESDAY, "水曜", "星期三", "周三", "週三", "礼拜三", "禮拜三", "수요일")
        add(DayOfWeek.THURSDAY, "木曜", "星期四", "周四", "週四", "礼拜四", "禮拜四", "목요일")
        add(DayOfWeek.FRIDAY, "金曜", "星期五", "周五", "週五", "礼拜五", "禮拜五", "금요일")
        add(DayOfWeek.SATURDAY, "土曜", "星期六", "周六", "週六", "礼拜六", "禮拜六", "토요일")
        add(DayOfWeek.SUNDAY, "日曜", "星期日", "星期天", "周日", "週日", "周天", "礼拜天", "禮拜天", "礼拜日", "일요일")
    }
    private val CJK_WEEKDAY = Regex("(${CJK_WEEKDAYS.keys.sortedByDescending { it.length }.joinToString("|")})日?")
    private val CJK_NEXT = Regex("来週|來週|下周|下週|下星期|下礼拜|다음\\s?주")
    private val CJK_RELATIVE: List<Pair<Regex, Long>> = listOf(
        "明後日|あさって|后天|後天|모레" to 2L,
        "明日|あした|あす|明天|내일" to 1L,
        "今日|きょう|今夜|今晩|今晚|今天|오늘" to 0L,
        "昨日|きのう|昨天|어제" to -1L,
    ).map { (words, days) -> Regex(words) to days }
    private const val CJK_PM_WORDS = "午後|夜|晩|下午|晚上|今晚|傍晚|오후|저녁|밤"
    private const val CJK_AM_WORDS = "午前|朝|上午|早上|早晨|오전|아침"
    private val CJK_PM = Regex(CJK_PM_WORDS)
    private val CJK_AM = Regex(CJK_AM_WORDS)
    // 7時, 19時30分, 7時半, 7点, 7点半, 7시 30분
    private val CJK_TIME = Regex("(?<!\\p{N})(\\d{1,2})\\s?[時时点點시](?:\\s?(\\d{1,2})\\s?[分분]|\\s?(半|반))?")
    /** Particles and daypart words that sit between the parts of one phrase ("金曜日の夜7時"). */
    private val CJK_JOIN = Regex("[、の에의的]|から|まで|부터|까지|到|至|~|〜|$CJK_PM_WORDS|$CJK_AM_WORDS")

    // ---- finding phrases --------------------------------------------------------------------------

    /** "sat", "sun", "wed": a weekday only next to a time or date ("Sat 7pm"), not in "I sat down". */
    private val WEEKDAY_ABBREVIATIONS = setOf("mon", "tue", "tues", "wed", "weds", "thu", "thur", "thurs", "fri", "sat", "sun")
    private val WEEKDAY_FULL = Regex("$B(?:${(WEEKDAYS.keys - WEEKDAY_ABBREVIATIONS).sortedByDescending { it.length }.joinToString("|")})$E")
    private val WEEKDAY_SHORT = Regex("$B(?:${WEEKDAY_ABBREVIATIONS.sortedByDescending { it.length }.joinToString("|")})\\.?$E")
    private val SLASH_DATE_WITH_YEAR = Regex("$B\\d{1,2}/\\d{1,2}/(?:\\d{4}|\\d{2})$E")
    // "19h", "19h30", "7 Uhr"; "2 h" is more likely two hours.
    private val H_TIGHT = Regex("$B\\d{1,2}(?:h(?:\\d{2})?|\\s?uhr)$E")

    /** Day and time words with whether each can stand alone as a plan. */
    private val ANCHORS: List<Pair<Regex, Boolean>> = listOf(
        ISO_DATE to true, SLASH_DATE_WITH_YEAR to true, DOT_DATE to true, MONTH_DAY to true, DAY_MONTH to true,
        WEEKDAY_FULL to true, TWELVE_HOUR to true, H_TIGHT to true, COLON to true, DAYPART_HOUR to true,
        SLASH_DATE to false, ORDINAL_DAY to false, WEEKDAY_SHORT to false, NOON to false, MIDNIGHT to false,
        H_CLOCK to false, BARE_HOUR to false, RANGE to false,
    ) + RELATIVE.map { it.first to false } +
        listOf(CJK_MONTH_DAY to true, CJK_WEEKDAY to true, CJK_TIME to true) + CJK_RELATIVE.map { it.first to false }

    private const val LINK_WORDS = "at|on|from|the|this|next|by|around|about|to|till|until|and|or|in the|of|y|e|et|und|ou" +
        "|de|del|el|la|las|a|al|alle|um|ab|le|les|o|os|as|em|no|na|ore|vers|des|du|am|ce|cette|este|esta|questo|questa" +
        "|morning|afternoon|evening|night|manana|tarde|noche|matin|soir|apres-midi|abend|nachmittag|morgen|fruh|sera|mattina|pomeriggio|noite"

    /** What may come between the parts of one phrase. */
    private val JOIN = Regex("[\\s,@-]*(?:(?:$LINK_WORDS)[\\s,@-]+)*(?:$LINK_WORDS)?[\\s,@-]*")
    private val BEFORE_WORDS = Regex(
        "(?:$B(?:next|this|coming|el proximo|la proxima|proximo|proxima|este|esta|ce|cette|nachsten|nachste|naechsten|kommenden|diesen" +
            "|questo|prossimo|prossima) |(?:来週|來週|下周|下週|下星期|下礼拜|다음\\s?주|午後|午前|下午|上午|晚上|早上|오후|오전|저녁|아침|夜|朝)の?\\s?)$",
    )
    private val AFTER_WORDS = Regex(
        "^ (?:prochaine?|que vem|qui vient|(?:in the|de la|du|da|di) (?:morning|afternoon|evening|manana|tarde|noche|matin|soir|manha|noite|sera)" +
            "|morning|afternoon|evening|night" +
            "|matin|soir|apres-midi|abend|nachmittag|morgen|fruh|noche|tarde|sera|mattina|pomeriggio|noite)$E",
    )
    private val RANGE_GAP = Regex("\\s*(?:-|to|till|until|a|al|hasta|bis|alle|ate|jusqu'?a|から|到|至|부터|~|〜)\\s*")
}
