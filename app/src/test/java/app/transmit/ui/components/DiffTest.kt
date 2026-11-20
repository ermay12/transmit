package app.transmit.ui.components

import app.transmit.ui.components.Diff.Op
import app.transmit.ui.components.Diff.Piece
import org.junit.Assert.assertEquals
import org.junit.Test

class DiffTest {
    @Test
    fun wordLevelForSpacedText() {
        assertEquals(
            listOf(Piece("Yo ", Op.SAME), Piece("es", Op.DEL), Piece("soy", Op.ADD), Piece(" estudiante", Op.SAME)),
            Diff.diff("Yo es estudiante", "Yo soy estudiante"),
        )
    }

    @Test
    fun shortCjkIsCharLevel() {
        assertEquals(listOf(Piece("我是学生", Op.SAME), Piece("。", Op.ADD)), Diff.diff("我是学生", "我是学生。"))
    }

    @Test
    fun bothSidesSplitTheSameWay() {
        // The draft has a stray space; the correction has none. Only the space should differ.
        assertEquals(
            listOf(Piece("今日は", Op.SAME), Piece(" ", Op.DEL), Piece("学校に行きました", Op.SAME)),
            Diff.diff("今日は 学校に行きました", "今日は学校に行きました"),
        )
    }

    @Test
    fun emojiAreNotSplit() {
        assertEquals(
            listOf(Piece("楽しかった", Op.SAME), Piece("😀", Op.DEL), Piece("😊", Op.ADD)),
            Diff.diff("楽しかった😀", "楽しかった😊"),
        )
    }

    /** Store screenshot: an added full stop struck the whole word out and put it back. Only the full stop is marked. */
    @Test
    fun aPunctuationOnlyChangeMarksJustThePunctuation() {
        assertEquals(listOf(Piece("Nos vemos mañana", Op.SAME), Piece(".", Op.ADD)), Diff.diff("Nos vemos mañana", "Nos vemos mañana."))
        assertEquals(
            listOf(Piece("Hola", Op.SAME), Piece("!", Op.DEL), Piece(",", Op.ADD), Piece(" ¿qué tal", Op.SAME), Piece("?", Op.ADD)),
            Diff.diff("Hola! ¿qué tal", "Hola, ¿qué tal?"),
        )
    }

    @Test
    fun apostrophesAndHyphensStayInsideTheirWord() {
        assertEquals(listOf("C'est", " ", "l'heure", " ", "d'aujourd'hui", "."), Diff.tokens("C'est l'heure d'aujourd'hui."))
        assertEquals(listOf("well-known", ",", " ", "don’t"), Diff.tokens("well-known, don’t"))
    }

    /** "~~para~~de" read as "parade": a removal and the addition right after it are kept apart by a thin space. */
    @Test
    fun aRemovalAndTheAdditionAfterItDontRunTogether() {
        val pieces = Diff.diff("Es para mí", "Es de mí")
        assertEquals(listOf(Piece("Es ", Op.SAME), Piece("para", Op.DEL), Piece("de", Op.ADD), Piece(" mí", Op.SAME)), pieces)
        assertEquals(
            listOf(Piece("Es ", Op.SAME), Piece("para", Op.DEL), Piece(Diff.GAP, Op.SAME), Piece("de", Op.ADD), Piece(" mí", Op.SAME)),
            Diff.shown(pieces),
        )
        val black = androidx.compose.ui.graphics.Color.Black
        assertEquals("Es para\u2009de mí", Diff.annotated("Es para mí", "Es de mí", black, black).text)
        // Already apart (a space between them), or a removed space that isn't shown: no gap.
        assertEquals(listOf(Piece("mi ", Op.DEL), Piece("tu", Op.ADD)), Diff.shown(listOf(Piece("mi ", Op.DEL), Piece("tu", Op.ADD))))
        assertEquals("今日は学校に行きました", Diff.annotated("今日は 学校に行きました", "今日は学校に行きました", black, black).text)
    }

    @Test
    fun matchingDraftHasNoChanges() {
        assertEquals(listOf(Piece("Yo soy estudiante", Op.SAME)), Diff.diff("Yo soy estudiante", "Yo soy estudiante"))
    }
}
