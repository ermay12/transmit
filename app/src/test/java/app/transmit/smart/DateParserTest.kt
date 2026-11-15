package app.transmit.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DateParserTest {
    // Sunday, November 15, 2026, 2:00 pm
    private val ref = LocalDateTime.of(2026, 11, 15, 14, 0)

    private fun at(phrase: String, ref: LocalDateTime = this.ref, dayFirst: Boolean = false) = DateParser.parse(phrase, ref, dayFirst)
    private fun start(phrase: String, ref: LocalDateTime = this.ref, dayFirst: Boolean = false) = at(phrase, ref, dayFirst)?.start
    private fun t(day: Int, hour: Int, min: Int = 0, month: Int = 11, year: Int = 2026) = LocalDateTime.of(year, month, day, hour, min)

    @Test
    fun relativeDaysAndTimes() {
        assertEquals(t(16, 15), start("tomorrow at 3pm"))
        assertEquals(t(16, 15, 30), start("tmrw 3:30 p.m."))
        assertEquals(t(15, 20), start("tonight at 8"))
        assertEquals(t(15, 20), start("8 tonight"))
        assertEquals(t(17, 12), start("day after tomorrow at noon"))
        assertEquals(t(16, 9), start("tomorrow morning at 9"))
        assertEquals(t(16, 19), start("tomorrow at 7"))
    }

    @Test
    fun timeAloneIsTheNextOne() {
        assertEquals(t(15, 17), start("at 5"))
        assertEquals(t(16, 13), start("1pm"))
        // "at 9" sent at 6pm is 9pm.
        assertEquals(t(15, 21), start("at 9", ref = t(15, 18)))
        assertEquals(t(15, 16, 30), start("16:30"))
    }

    @Test
    fun weekdays() {
        assertEquals(t(20, 19), start("Friday at 7"))
        assertEquals(t(20, 19), start("fri 7pm"))
        assertEquals(t(16, 0), start("Monday"))
        assertTrue(at("Monday")!!.allDay)
        // Sunday 2pm: "Sunday at 1" has passed, so it's next week.
        assertEquals(t(22, 13), start("Sunday at 1pm"))
        assertEquals(t(15, 18), start("Sunday at 6pm"))
        // "next Friday" from Wednesday the 18th is the Friday of the following week.
        assertEquals(t(27, 0), start("next Friday", ref = t(18, 10)))
        assertEquals(t(16, 0), start("next Monday"))
    }

    @Test
    fun explicitDates() {
        assertEquals(t(20, 18, 30), start("Nov 20 at 6:30"))
        assertEquals(t(20, 0), start("November 20th"))
        assertEquals(t(5, 0, month = 1, year = 2027), start("Jan 5"))
        assertEquals(t(3, 0, month = 12), start("12/3"))
        assertEquals(t(12, 0, month = 3, year = 2027), start("12/3", dayFirst = true))
        assertEquals(t(20, 0), start("20/11/2026"))
        assertEquals(t(20, 0), start("2026-11-20"))
        assertEquals(t(20, 0), start("the 20th"))
        assertEquals(t(1, 0, month = 12), start("Dec 1st, 2026"))
    }

    @Test
    fun ranges() {
        val evening = at("Saturday 7-9pm")!!
        assertEquals(t(21, 19), evening.start)
        assertEquals(t(21, 21), evening.end)
        val lunch = at("tomorrow 11-1pm")!!
        assertEquals(t(16, 11), lunch.start)
        assertEquals(t(16, 13), lunch.end)
        val party = at("Friday from 6pm until 8")!!
        assertEquals(t(20, 18), party.start)
        assertEquals(t(20, 20), party.end)
        assertEquals(t(20, 21), at("vendredi de 19h à 21h")!!.end)
    }

    @Test
    fun otherLanguages() {
        assertEquals(t(20, 19), start("vendredi à 19h"))
        assertEquals(t(16, 15), start("mañana a las 3"))
        assertEquals(t(16, 9), start("mañana por la mañana a las 9"))
        assertEquals(t(16, 20), start("demain soir à 20h"))
        assertEquals(t(20, 0), start("20 de noviembre"))
        assertEquals(t(20, 18), start("Freitag um 18 Uhr"))
        assertEquals(t(20, 0), start("20.11."))
        assertEquals(t(17, 21), start("martedì alle 21"))
        assertEquals(t(16, 15), start("amanhã às 15h"))
    }

    @Test
    fun plansVersusMentions() {
        val now = ref
        assertTrue(at("tomorrow at 3pm")!!.isPlan(now))
        assertTrue(at("Friday")!!.isPlan(now))
        // A bare "today" or "tomorrow" isn't worth a link.
        assertFalse(at("tomorrow")!!.isPlan(now))
        assertFalse(at("tonight")!!.isPlan(now))
        assertFalse(at("yesterday at 3pm")!!.isPlan(now))
    }

    private fun found(text: String, ref: LocalDateTime = this.ref) =
        DateParser.find(text, ref, now = ref).map { text.substring(it.start, it.end) to it.result.start }

    @Test
    fun findsPlansInMessages() {
        assertEquals(
            listOf("Friday at 7" to t(20, 19)),
            found("Want to grab dinner Friday at 7? Let's meet at 1600 Amphitheatre Parkway, Mountain View, CA 94043"),
        )
        assertEquals(listOf("Nov 28 from 6-9pm" to t(28, 18)), found("Also the party is Nov 28 from 6-9pm at 500 Castro St"))
        assertEquals(listOf("tomorrow morning at 9" to t(16, 9)), found("Can you do tomorrow morning at 9?"))
        assertEquals(listOf("next Friday" to t(20, 0)), found("How about next Friday", ref = t(13, 10)).map { it.first to it.second })
        assertEquals(listOf("Sat 7pm" to t(21, 19)), found("Game night Sat 7pm!"))
        assertEquals(listOf("7:30 p.m." to t(15, 19, 30)), found("Dinner's at 7:30 p.m."))
        assertEquals(listOf("vendredi à 19h" to t(20, 19)), found("On se voit vendredi à 19h ?"))
        assertEquals(listOf("mañana a las 3" to t(16, 15)), found("Nos vemos mañana a las 3"))
        assertEquals(listOf("tonight at 8" to t(15, 20)), found("Movie tonight at 8, you in?"))
    }

    @Test
    fun chineseJapaneseKorean() {
        assertEquals(t(20, 19), start("金曜日の夜7時"))
        assertEquals(t(16, 15, 30), start("明日の午後3時半"))
        assertEquals(t(20, 19), start("星期五晚上7点"))
        assertEquals(t(16, 15), start("明天下午3点"))
        assertEquals(t(20, 19), start("금요일 오후 7시"))
        assertEquals(t(28, 0), start("11月28日"))
        assertEquals(listOf("金曜日の夜7時" to t(20, 19)), found("金曜日の夜7時に夕食はどう？"))
        assertEquals(listOf("星期五晚上7点" to t(20, 19)), found("星期五晚上7点一起吃晚饭吗？"))
        assertEquals(listOf("금요일 오후 7시" to t(20, 19)), found("금요일 오후 7시에 저녁 먹을래?"))
        assertEquals(listOf("11月28日の18時から21時" to t(28, 18)), found("パーティーは11月28日の18時から21時です"))
    }

    @Test
    fun offsetsSurviveAccents() {
        val text = "Café? Ça marche, à demain 15h à côté"
        val f = DateParser.find(text, ref, now = ref).single()
        assertEquals("demain 15h", text.substring(f.start, f.end))
    }

    @Test
    fun ignoresMentionsThatAreNotPlans() {
        assertEquals(emptyList<Any>(), found("See you tomorrow!"))
        assertEquals(emptyList<Any>(), found("I woke up at 7"))
        assertEquals(emptyList<Any>(), found("We're open 24/7"))
        assertEquals(emptyList<Any>(), found("I sat down and the sun came out"))
        assertEquals(emptyList<Any>(), found("for the 3rd time"))
        assertEquals(emptyList<Any>(), found("it was great yesterday at 3pm"))
        assertEquals(emptyList<Any>(), found("won 3-2 lol"))
        assertEquals(emptyList<Any>(), found("Call me at (650) 253-0000"))
    }

    @Test
    fun unreadablePhrases() {
        assertNull(at("next week"))
        assertNull(at("soon"))
        assertNull(at("in a bit"))
    }
}
