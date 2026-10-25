package io.github.kdbrian.rivapln.todoradar.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import io.github.kdbrian.rivapln.todoradar.ui.theme.NodeShape
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/** What a swipe direction does: the glyph shown in the blob, its colour, and the callback. */
data class SwipeAction(
    val glyph: NodeShape,
    val color: Color,
    /** Remove the row after triggering (e.g. Delete); otherwise it springs back. */
    val dismiss: Boolean = false,
    val onTrigger: () -> Unit,
)

/**
 * Swipe-to-act with a gooey blob that stretches out of the screen edge and follows the thumb
 * (the interaction from the inspiration video). Past the threshold the glyph turns from a
 * faint hint into a bold ink mark and a haptic tick fires; releasing triggers the action.
 */
@Composable
fun GooeySwipe(
    modifier: Modifier = Modifier,
    startAction: SwipeAction? = null,
    endAction: SwipeAction? = null,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    // Plain holder (not state): only the gesture callbacks read and write it.
    val armed = remember { booleanArrayOf(false) }

    Box(
        modifier
            .alpha(fade.value)
            .pointerInput(startAction, endAction) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val dx = offset.value
                        val action = if (dx > 0) startAction else endAction
                        scope.launch {
                            if (action != null && abs(dx) >= threshold) {
                                action.onTrigger()
                                if (action.dismiss) fade.animateTo(0f, tween(220))
                            }
                            offset.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow))
                            fade.snapTo(1f)
                            armed[0] = false
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) }; armed[0] = false },
                ) { change, amount ->
                    val next = offset.value + amount
                    val allowed = (next > 0 && startAction != null) || (next < 0 && endAction != null) || next == 0f
                    if (!allowed) return@detectHorizontalDragGestures
                    change.consume()
                    val clamped = next.coerceIn(-threshold * 1.8f, threshold * 1.8f)
                    scope.launch { offset.snapTo(clamped) }
                    val nowArmed = abs(clamped) >= threshold
                    if (nowArmed && !armed[0]) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    armed[0] = nowArmed
                }
            },
    ) {
        // Content leans a little with the drag so the blob reads as pulling it.
        Box(Modifier.graphicsLayer { translationX = offset.value * 0.18f }) { content() }

        val dx = offset.value
        val action = if (dx > 0) startAction else if (dx < 0) endAction else null
        if (action != null) {
            Canvas(Modifier.matchParentSize()) {
                val fromLeft = dx > 0
                val d = abs(dx)
                val cy = size.height / 2
                val r = min(20.dp.toPx() + d * 0.05f, 28.dp.toPx())
                val edgeX = if (fromLeft) 0f else size.width
                val dir = if (fromLeft) 1f else -1f
                val cx = edgeX + dir * (d * 0.55f + r * 0.4f)
                // Neck thins as the blob travels; the base hugs the screen edge.
                val base = r * 1.25f
                val neck = (r * 0.55f * (1f - d / (threshold * 2.4f))).coerceAtLeast(r * 0.18f)
                val mid = edgeX + dir * (abs(cx - edgeX) * 0.5f)
                val blob = Path().apply {
                    moveTo(edgeX, cy - base)
                    cubicTo(edgeX + dir * base * 0.3f, cy - base, mid - dir * r * 0.2f, cy - neck, mid, cy - neck)
                    cubicTo(mid + dir * r * 0.4f, cy - neck, cx - dir * r, cy - r, cx, cy - r)
                    lineTo(cx, cy + r)
                    cubicTo(cx - dir * r, cy + r, mid + dir * r * 0.4f, cy + neck, mid, cy + neck)
                    cubicTo(mid - dir * r * 0.2f, cy + neck, edgeX + dir * base * 0.3f, cy + base, edgeX, cy + base)
                    close()
                }
                drawPath(blob, action.color)
                drawCircle(action.color, r, Offset(cx, cy))
                val reached = d >= threshold
                nodeGlyph(
                    if (reached) action.glyph else NodeShape.Dot,
                    Offset(cx, cy),
                    r * (if (reached) 0.9f else 0.5f),
                    if (reached) Color.White else Color.White.copy(alpha = 0.8f),
                    2.5.dp.toPx(),
                )
            }
        }
    }
}
