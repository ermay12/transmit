package app.transmit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.transmit.messaging.DeliveryStatus

/**
 * An outgoing message's receipt, drawn like Google Messages': a clock while sending, a check in a
 * circle once sent, two overlapping check circles once delivered, filled in once read, and an
 * error mark when it failed. Nothing for incoming messages. [size] is the height, as for an [Icon].
 */
@Composable
fun ReceiptIcon(
    status: DeliveryStatus,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    tint: Color = LocalContentColor.current,
) {
    when (status) {
        DeliveryStatus.RECEIVED -> Unit
        DeliveryStatus.SENDING -> Icon(Icons.Outlined.Schedule, "Sending", modifier.size(size), tint = tint)
        DeliveryStatus.FAILED -> Icon(Icons.Outlined.ErrorOutline, "Not sent", modifier.size(size), tint = MaterialTheme.colorScheme.error)
        DeliveryStatus.SENT -> CheckCircles("Sent", count = 1, filled = false, size, tint, modifier)
        DeliveryStatus.DELIVERED -> CheckCircles("Delivered", count = 2, filled = false, size, tint, modifier)
        DeliveryStatus.READ -> CheckCircles("Read", count = 2, filled = true, size, tint, modifier)
    }
}

// Geometry in a 24-unit-high box, like a Material icon: circles of radius 10, the second one
// [SPACING] to the right of the first and covering it, with a [GAP] around it.
private const val UNIT = 24f
private const val RADIUS = 10f
private const val RING = 1.75f
private const val SPACING = 12f
private const val GAP = 1.4f
private const val CHECK_WIDTH = 2.2f

@Composable
private fun CheckCircles(label: String, count: Int, filled: Boolean, size: Dp, tint: Color, modifier: Modifier) {
    val width = size * (UNIT + SPACING * (count - 1)) / UNIT
    Canvas(
        modifier
            .size(width, size)
            // Its own layer, so clearing the gap and the filled checks leaves what's behind showing.
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .semantics {
                contentDescription = label
                role = Role.Image
            },
    ) {
        val u = this.size.height / UNIT
        repeat(count) { i ->
            val center = Offset((UNIT / 2 + SPACING * i) * u, UNIT / 2 * u)
            if (i > 0) drawCircle(Color.Black, (RADIUS + GAP) * u, center, blendMode = BlendMode.Clear)
            checkCircle(center, u, filled, tint)
        }
    }
}

private fun DrawScope.checkCircle(center: Offset, u: Float, filled: Boolean, tint: Color) {
    if (filled) drawCircle(tint, RADIUS * u, center)
    else drawCircle(tint, (RADIUS - RING / 2) * u, center, style = Stroke(RING * u))
    val check = Path().apply {
        moveTo(center.x - 5f * u, center.y + 0.6f * u)
        lineTo(center.x - 1.6f * u, center.y + 4f * u)
        lineTo(center.x + 5.2f * u, center.y - 2.8f * u)
    }
    drawPath(
        check, tint, style = Stroke(CHECK_WIDTH * u, cap = StrokeCap.Butt, join = StrokeJoin.Miter),
        // On a filled circle the check is cut out, like Google's.
        blendMode = if (filled) BlendMode.Clear else BlendMode.SrcOver,
    )
}
