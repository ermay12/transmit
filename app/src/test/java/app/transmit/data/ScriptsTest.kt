package app.transmit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptsTest {
    @Test
    fun recognizesPinyin() {
        val p = Romanization.PINYIN
        assertTrue(Scripts.isRomanized("wo3 xiang3 qu4 bei3jing", p))
        assertTrue(Scripts.isRomanized("Wǒ xiǎng qù Běijīng!", p))
        assertTrue(Scripts.isRomanized("wo xiang qu Beijing", p))
        assertTrue(Scripts.isRomanized("xie4xie5, ni3 zai4 nar3?", p))
        assertTrue(Scripts.isRomanized("lv4se4 de, nu:3 de 😊", p))
        assertTrue(Scripts.isRomanized("dui4", p))
        assertTrue(Scripts.isRomanized("Xi'an hen3 hao3", p))
        // A stray English word doesn't stop it.
        assertTrue(Scripts.isRomanized("ok hao3 de", p))
        // Characters, English and things without words aren't pinyin.
        assertFalse(Scripts.isRomanized("我想去北京", p))
        assertFalse(Scripts.isRomanized("我 xiang3 去", p))
        assertFalse(Scripts.isRomanized("I love you", p))
        assertFalse(Scripts.isRomanized("hello", p))
        assertFalse(Scripts.isRomanized("lol", p))
        assertFalse(Scripts.isRomanized("12:30 👍", p))
        assertFalse(Scripts.isRomanized("", p))
    }

    @Test
    fun recognizesRomaji() {
        val r = Romanization.ROMAJI
        assertTrue(Scripts.isRomanized("watashi wa genki desu", r))
        assertTrue(Scripts.isRomanized("Tōkyō ni ikimasu.", r))
        assertTrue(Scripts.isRomanized("kitte, matcha, konnichiwa, shimbun", r))
        assertTrue(Scripts.isRomanized("arigatou gozaimasu", r))
        assertFalse(Scripts.isRomanized("東京に行きます", r))
        assertFalse(Scripts.isRomanized("I love you", r))
        assertFalse(Scripts.isRomanized("hello world", r))
    }

    @Test
    fun toneNumbersBecomeToneMarks() {
        assertEquals("duì", Scripts.toneMarks("dui4"))
        assertEquals("Wǒ xiǎng qù.", Scripts.toneMarks("Wo3 xiang3 qu4."))
        assertEquals("nǐhǎo", Scripts.toneMarks("ni3hao3"))
        assertEquals("xièxie", Scripts.toneMarks("xie4xie5"))
        assertEquals("Zhōngguó", Scripts.toneMarks("Zhong1guo2"))
    }

    @Test
    fun toneMarksGoWherePinyinPutsThem() {
        assertEquals("gǒu", Scripts.toneMarks("gou3")) // o of "ou"
        assertEquals("guì", Scripts.toneMarks("gui4")) // last vowel
        assertEquals("liú", Scripts.toneMarks("liu2"))
        assertEquals("xué", Scripts.toneMarks("xue2")) // e before the last vowel
        assertEquals("lǜ nǚ nǚ", Scripts.toneMarks("lv4 nu:3 nü3"))
        assertEquals("nàr", Scripts.toneMarks("nar4"))
        assertEquals("ÀI", Scripts.toneMarks("AI4"))
    }

    @Test
    fun otherTextIsLeftAlone() {
        assertEquals("Route 66, hello5 and 2024", Scripts.toneMarks("Route 66, hello5 and 2024"))
        assertEquals("nǐ hǎo", Scripts.toneMarks("nǐ hǎo"))
        assertEquals("ok 👍", Scripts.toneMarks("ok 👍"))
    }

    @Test
    fun charactersAndKanaNeedTheAi() {
        assertTrue(Scripts.needsReading("你好"))
        assertTrue(Scripts.needsReading("ok 好的"))
        assertTrue(Scripts.needsReading("ありがとう"))
        assertTrue(Scripts.needsReading("コーヒー"))
        assertTrue(Scripts.needsReading("ㄋㄧˇ"))
        assertFalse(Scripts.needsReading("nǐ hǎo 👍 123"))
        assertFalse(Scripts.needsReading("？！。"))
    }

    @Test
    fun latinTextIsRomanizedOnTheDevice() {
        assertNull(Scripts.local("我想去", Romanization.PINYIN))
        assertEquals("wǒ xiǎng qù", Scripts.local("wo3 xiang3 qu4", Romanization.PINYIN))
        assertEquals("OK!", Scripts.local("OK!", Romanization.PINYIN))
        // Romaji has no tone numbers to convert.
        assertEquals("ikimasu 2", Scripts.local("ikimasu 2", Romanization.ROMAJI))
        assertNull(Scripts.local("行きます", Romanization.ROMAJI))
    }
}
