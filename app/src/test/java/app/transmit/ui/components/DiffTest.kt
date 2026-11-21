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

    /**
     * The user's report (beta9): with only punctuation missing, the word beside it showed as wrong. That happened where the
     * correction capitalizes after an added full stop, or adds an apostrophe or hyphen inside a word. Now only the marks,
     * and a capital letter, are marked.
     */
    @Test
    fun missingPunctuationLeavesTheWordsBesideItUnmarked() {
        assertEquals(
            listOf(Piece("Oui", Op.SAME), Piece(",", Op.ADD), Piece(" je viens", Op.SAME), Piece(".", Op.ADD)),
            Diff.diff("Oui je viens", "Oui, je viens."),
        )
        assertEquals(
            listOf(
                Piece("O", Op.ADD), Piece("k", Op.SAME), Piece(".", Op.ADD), Piece(" ", Op.SAME), Piece("S", Op.ADD),
                Piece("ee you tomorrow", Op.SAME), Piece(".", Op.ADD),
            ),
            Diff.diff("ok see you tomorrow", "Ok. See you tomorrow."),
        )
    }

    @Test
    fun aMissingApostropheOrHyphenMarksJustTheMark() {
        assertEquals(listOf(Piece("c", Op.SAME), Piece("'", Op.ADD), Piece("est bon", Op.SAME)), Diff.diff("cest bon", "c'est bon"))
        assertEquals(listOf(Piece("J'", Op.ADD), Piece("ai faim", Op.SAME), Piece(".", Op.ADD)), Diff.diff("jai faim", "J'ai faim."))
        // The space the hyphen replaces is a removal, and blank removals aren't shown: it reads "est-ce".
        assertEquals(
            listOf(Piece("est", Op.SAME), Piece(" ", Op.DEL), Piece("-", Op.ADD), Piece("ce que tu viens", Op.SAME), Piece(" ?", Op.ADD)),
            Diff.diff("est ce que tu viens", "est-ce que tu viens ?"),
        )
        val black = androidx.compose.ui.graphics.Color.Black
        assertEquals("est-ce que tu viens ?", Diff.annotated("est ce que tu viens", "est-ce que tu viens ?", black, black).text)
    }

    /** Typographic apostrophes and French no-break spaces are the correction's style, not the learner's mistakes. */
    @Test
    fun apostropheStyleAndKindOfSpaceAreNoChange() {
        assertEquals(listOf(Piece("C’est bon", Op.SAME)), Diff.diff("C'est bon", "C’est bon"))
        assertEquals(listOf(Piece("Tu viens ?", Op.SAME)), Diff.diff("Tu viens ?", "Tu viens ?"))
    }

    /** A space added or removed on its own can't be seen, so joining or splitting words still replaces the word. */
    @Test
    fun aSpaceChangeOnItsOwnStaysAReplacedWord() {
        assertEquals(listOf(Piece("alot", Op.DEL), Piece("a lot", Op.ADD)), Diff.diff("alot", "a lot"))
    }

    /** An accent is a letter, so a spelling fix still replaces its word; the comma beside it is marked on its own. */
    @Test
    fun spellingFixesStillReplaceTheWord() {
        assertEquals(
            listOf(
                Piece("S", Op.ADD), Piece("alut", Op.SAME), Piece(",", Op.ADD), Piece(" ", Op.SAME), Piece("ca", Op.DEL),
                Piece("ça", Op.ADD), Piece(" va", Op.SAME), Piece(" ?", Op.ADD),
            ),
            Diff.diff("salut ca va", "Salut, ça va ?"),
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
