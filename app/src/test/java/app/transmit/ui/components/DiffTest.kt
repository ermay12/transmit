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

    @Test
    fun matchingDraftHasNoChanges() {
        assertEquals(listOf(Piece("Yo soy estudiante", Op.SAME)), Diff.diff("Yo soy estudiante", "Yo soy estudiante"))
    }
}
