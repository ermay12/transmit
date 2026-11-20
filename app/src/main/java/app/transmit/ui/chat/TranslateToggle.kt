package app.transmit.ui.chat

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.transmit.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val TrackWidth = 60.dp
private val TrackHeight = 34.dp
/** Between the thumb and the track's edge; a spring's overshoot uses it. */
private val ThumbInset = 3.dp
private val Outline = 1.5.dp

/** A little bouncy, so the slide reads on camera. */
private val ThumbSpring = spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)
private val PopSpring = spring<Float>(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium)
private const val POP_SCALE = 1.3f
/** How far across the thumb gets, with no AI, before it springs back. */
private const val NUDGE = 0.45f
private val BackSpring = spring<Float>(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium)
/** How long a tap's position shows, once passed on, before the chat's setting must have caught up. */
private const val PENDING_MS = 2_000L

/**
 * The top bar's auto-translate switch: a small pill whose thumb carries the practice-language [flag]. Off, the thumb
 * sits left on an outlined track and the flag is dimmed. On, the thumb springs right, the track fills with the
 * tertiary colour behind it and the flag lights up with a small pop. Each switch gives a light haptic tick. A
 * long-press shows "Auto-translate".
 *
 * Without AI ([available] false: none set up, or it ended) it never looks on. A tap sends the thumb part way across
 * and springs it back, and then [onClick] opens the sheet that turns translation on. Otherwise [onClick] switches
 * the chat's auto-translate, once the thumb has come to rest: switching on starts translating at once, and that work
 * may hold up a frame or two, which would cut the slide short.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateToggle(on: Boolean, flag: String, available: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.chat_auto_translate)
    val onText = stringResource(R.string.chat_auto_translate_on)
    val offText = stringResource(R.string.chat_auto_translate_off)

    val actual = on && available
    // A tap moves the thumb at once; the chat's setting follows a moment later and takes over.
    var tapped by remember { mutableStateOf<Boolean?>(null) }
    // Taps not yet passed on to [onClick]; an even number changes nothing.
    var owed by remember { mutableIntStateOf(0) }
    val send by rememberUpdatedState(onClick)
    LaunchedEffect(actual) { tapped = null }
    LaunchedEffect(tapped) {
        if (tapped != null) {
            snapshotFlow { owed }.first { it == 0 }
            delay(PENDING_MS)
            tapped = null
        }
    }
    val checked = tapped ?: actual

    val position = remember { Animatable(if (checked) 1f else 0f) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(checked) {
        val to = if (checked) 1f else 0f
        if (position.targetValue == to) return@LaunchedEffect
        launch { position.animateTo(to, ThumbSpring) }
        if (checked) pop.animateTo(POP_SCALE, tween(110, easing = FastOutSlowInEasing))
        // Also settles a pop that a quick switch back cut short.
        pop.animateTo(1f, PopSpring)
    }
    LaunchedEffect(owed) {
        if (owed == 0) return@LaunchedEffect
        withFrameNanos {} // the slide has started
        snapshotFlow { position.isRunning || pop.isRunning }.first { !it }
        val flip = owed % 2 == 1
        owed = 0
        if (flip) send()
    }
    // Leaving the chat mid-slide still switches it.
    DisposableEffect(Unit) { onDispose { if (owed % 2 == 1) send() } }

    // The flag is an icon in a thumb of fixed size, so it doesn't grow with the font size.
    val flagSize = with(LocalDensity.current) { 17.dp.toSp() }
    val colors = MaterialTheme.colorScheme
    val fill = colors.tertiary
    val outline = colors.outline
    val thumbOff = colors.surfaceContainerHighest
    val thumbOn = colors.onTertiary

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .size(TrackWidth, TrackHeight)
                .clip(CircleShape)
                .toggleable(value = checked, role = Role.Switch) {
                    if (!available) {
                        view.tick(null)
                        scope.launch {
                            position.animateTo(NUDGE, tween(130, easing = FastOutSlowInEasing))
                            position.animateTo(0f, BackSpring)
                            onClick()
                        }
                    } else {
                        view.tick(!checked)
                        tapped = !checked
                        owed++
                    }
                }
                .semantics {
                    contentDescription = label
                    stateDescription = if (checked) onText else offText
                }
                .drawBehind {
                    val p = position.value.coerceIn(0f, 1f)
                    val radius = CornerRadius(size.height / 2)
                    // The fill reaches just past the thumb, so it grows behind it as it slides.
                    val inset = ThumbInset.toPx()
                    val thumbRight = inset + (size.width - size.height) * p + (size.height - 2 * inset)
                    drawRoundRect(fill.copy(alpha = (p * 1.5f).coerceAtMost(1f)), size = Size(thumbRight + inset, size.height), cornerRadius = radius)
                    val stroke = Outline.toPx()
                    drawRoundRect(
                        outline.copy(alpha = 1f - p), topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius((size.height - stroke) / 2), style = Stroke(stroke),
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .offset {
                        // A spring's overshoot may use the inset, not more.
                        val p = position.value.coerceIn(-0.1f, 1.1f)
                        IntOffset(((TrackWidth - TrackHeight).toPx() * p).roundToInt(), 0)
                    }
                    .padding(start = ThumbInset)
                    .size(TrackHeight - ThumbInset * 2)
                    .graphicsLayer {
                        val s = 1f + (pop.value - 1f) * 0.35f
                        scaleX = s
                        scaleY = s
                    }
                    .drawBehind { drawCircle(lerp(thumbOff, thumbOn, position.value.coerceIn(0f, 1f))) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    flag, fontSize = flagSize,
                    modifier = Modifier
                        .clearAndSetSemantics {}
                        .graphicsLayer {
                            val p = position.value.coerceIn(0f, 1f)
                            alpha = 0.5f + 0.5f * p
                            scaleX = pop.value
                            scaleY = pop.value
                            // Off, the flag is greyed as well as dimmed; it lights up as the thumb crosses.
                            renderEffect = if (p >= 1f) null else desaturated(0.15f + 0.85f * p)
                        },
                )
            }
        }
    }
}

private fun desaturated(saturation: Float) = RenderEffect.createColorFilterEffect(
    ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) }),
).asComposeRenderEffect()

/** A light tick: the system's toggle feedback where there is one ([on] null: a tap that changes nothing). */
private fun View.tick(on: Boolean?) {
    val type = when {
        on != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        else -> HapticFeedbackConstants.CLOCK_TICK
    }
    performHapticFeedback(type)
}
