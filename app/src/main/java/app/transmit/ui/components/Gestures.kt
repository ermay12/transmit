package app.transmit.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/**
 * Waits for a touch to show its direction: true once it has moved past the touch slop within about 27°
 * of horizontal; false once it has moved that far vertically (a scroll, left to the list) or ends.
 * With [rightOnly], a move to the left is false too.
 */
suspend fun AwaitPointerEventScope.awaitSideways(id: PointerId, rightOnly: Boolean = false): Boolean {
    val slop = viewConfiguration.touchSlop
    var total = Offset.Zero
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: return false
        if (!change.pressed || change.isConsumed) return false
        total += change.positionChange()
        if (abs(total.y) >= slop) return false
        if (abs(total.x) >= slop && abs(total.x) > abs(total.y) * 2) {
            if (rightOnly && total.x < 0) return false
            change.consume()
            return true
        }
    }
}
