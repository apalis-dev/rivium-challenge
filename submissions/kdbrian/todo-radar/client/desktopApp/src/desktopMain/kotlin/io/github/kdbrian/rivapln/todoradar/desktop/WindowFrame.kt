package io.github.kdbrian.rivapln.todoradar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import io.github.kdbrian.rivapln.todoradar.ui.components.RadarSweep
import io.github.kdbrian.rivapln.todoradar.ui.theme.Radar
import io.github.kdbrian.rivapln.todoradar.ui.theme.RadarTheme
import java.awt.Cursor

private val Corner = 18.dp
private val ShadowRoom = 10.dp

/**
 * Replaces the OS frame: rounded card with a soft shadow, our own title bar (drag to move,
 * double-click to maximise) and a resize grip. Corners square off when maximised.
 */
@Composable
fun FrameWindowScope.FramedWindow(state: WindowState, onClose: () -> Unit, content: @Composable () -> Unit) {
    val maximized = state.placement == WindowPlacement.Maximized
    val shape = if (maximized) RectangleShape else RoundedCornerShape(Corner)
    RadarTheme {
        val c = Radar.colors
        Box(Modifier.fillMaxSize().padding(if (maximized) 0.dp else ShadowRoom)) {
            Surface(
                modifier = Modifier.fillMaxSize()
                    .shadow(if (maximized) 0.dp else 14.dp, shape, ambientColor = c.ink.copy(alpha = 0.25f), spotColor = c.ink.copy(alpha = 0.25f))
                    .border(1.dp, c.ink.copy(alpha = 0.12f), shape),
                shape = shape,
                color = c.paper,
            ) {
                Column {
                    TitleBar(state, onClose)
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                }
            }
            if (!maximized) ResizeGrip(state, Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun FrameWindowScope.TitleBar(state: WindowState, onClose: () -> Unit) {
    val c = Radar.colors
    val toggleMax = {
        state.placement = if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
    }
    WindowDraggableArea(Modifier.fillMaxWidth().height(44.dp).pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggleMax() }) }) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            // The page already shows the wordmark; the bar keeps a quiet brand mark.
            RadarSweep(size = 20.dp, active = false, blips = 2)
            Spacer(Modifier.weight(1f))
            FrameButton(c.mint, "Minimise", onClick = { state.isMinimized = true }) { color ->
                drawLine(color, Offset(size.width * 0.3f, center.y), Offset(size.width * 0.7f, center.y), 2.dp.toPx(), StrokeCap.Round)
            }
            Spacer(Modifier.width(10.dp))
            FrameButton(c.sky, if (state.placement == WindowPlacement.Maximized) "Restore" else "Maximise", onClick = toggleMax) { color ->
                val s = size.width * 0.38f
                drawRect(color, Offset(center.x - s / 2, center.y - s / 2), androidx.compose.ui.geometry.Size(s, s), style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx()))
            }
            Spacer(Modifier.width(10.dp))
            FrameButton(c.rose.copy(alpha = 0.85f), "Close", onClick = onClose) { color ->
                val a = size.width * 0.33f
                val b = size.width * 0.67f
                drawLine(color, Offset(a, a), Offset(b, b), 2.dp.toPx(), StrokeCap.Round)
                drawLine(color, Offset(b, a), Offset(a, b), 2.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

/** Brand-style window button: pastel disc with ink ring; the glyph appears on hover. */
@Composable
private fun FrameButton(
    fill: Color,
    label: String,
    onClick: () -> Unit,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    val c = Radar.colors
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier.size(20.dp).clip(CircleShape).background(fill).border(1.5.dp, c.ink, CircleShape)
            .hoverable(hover)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .semantics { contentDescription = label },
    ) {
        if (hovered) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { glyph(c.ink) }
    }
}

/** Bottom-right grip: drag to resize the undecorated window. */
@Composable
private fun ResizeGrip(state: WindowState, modifier: Modifier) {
    val c = Radar.colors
    val density = LocalDensity.current
    Box(
        modifier.size(22.dp)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    with(density) {
                        state.size = DpSize(
                            (state.size.width + drag.x.toDp()).coerceAtLeast(480.dp),
                            (state.size.height + drag.y.toDp()).coerceAtLeast(520.dp),
                        )
                    }
                }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val stroke = 1.5.dp.toPx()
            listOf(0.35f, 0.7f).forEach { f ->
                drawLine(c.ink.copy(alpha = 0.35f), Offset(size.width * f, size.height), Offset(size.width, size.height * f), stroke, StrokeCap.Round)
            }
        }
    }
}
