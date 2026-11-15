package app.transmit.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpTest {
    private fun code(text: String) = Otp.find(text)?.code

    @Test
    fun findsCommonServiceFormats() {
        assertEquals("482913", code("Your verification code is 482913. Don't share it with anyone."))
        assertEquals("123456", code("123456 is your Facebook code"))
        assertEquals("123456", code("Use 123 456 to verify your Instagram account."))
        assertEquals("123456", code("Your WhatsApp code: 123-456\n\nDon't share this code with others"))
        assertEquals("123456", code("G-123456 is your Google verification code."))
        assertEquals("1234", code("Your Uber code: 1234. Never share this code. Reply STOP ALL to unsubscribe."))
        assertEquals("12345678", code("Chase: Your one-time code is 12345678. We'll never call you to ask for this code."))
        assertEquals("98765", code("Telegram code: 98765\n\nYou can also tap on this link to log in:\nhttps://t.me/login/98765"))
        assertEquals("X7K9P2", code("Your confirmation code is X7K9P2"))
        assertEquals("4821", code("Your PIN is 4821"))
    }

    @Test
    fun findsCodesInOtherLanguages() {
        assertEquals("552019", code("Tu código de verificación es 552019"))
        assertEquals("552019", code("Votre code de vérification est : 552019"))
        assertEquals("552019", code("Ihr Bestätigungscode lautet 552019"))
        assertEquals("552019", code("【微信】验证码：552019，5分钟内有效"))
        assertEquals("552019", code("認証コード：552019"))
        assertEquals("552019", code("[Web발신] 인증번호 [552019]를 입력해주세요"))
        assertEquals("552019", code("Ваш код подтверждения: 552019"))
    }

    @Test
    fun prefersTheCodeOverOtherNumbers() {
        assertEquals("482913", code("Your Example Bank code is 482913. Valid for 10 min. Questions? Call (555) 123-4567"))
        assertEquals("654321", code("Order 1234: your delivery code is 654321"))
        assertEquals("482913", code("Your code is 482913. © 2026 Example Inc."))
    }

    @Test
    fun readsDomainBoundCodes() {
        val m = Otp.find("Your Example code is 123456.\n\n@example.com #123456")!!
        assertEquals("123456", m.code)
        assertEquals(21, m.start)
        assertEquals(27, m.end)
    }

    @Test
    fun reportsWhereTheCodeIs() {
        val text = "Use G-778899 to sign in"
        val m = Otp.find(text)!!
        assertEquals("G-778899", text.substring(m.start, m.end))
        assertEquals("778899", m.code)
    }

    @Test
    fun ignoresOrdinaryMessages() {
        assertNull(code("Meet me at 1234 Main St at 7"))
        assertNull(code("Drop a pin at 1234 Main St"))
        assertNull(code("Call me at 555-123-4567"))
        assertNull(code("My zip code is 94110"))
        assertNull(code("Use promo code SAVE20 for 20% off"))
        assertNull(code("The area code is 415"))
        assertNull(code("That cost me $1234 with the code"))
        assertNull(code("I went there one time in 2019"))
        assertNull(code("Your code review is due 2026"))
        assertNull(code("See https://example.com/verify/123456 for details"))
        assertNull(code("ok see you then"))
    }
}
