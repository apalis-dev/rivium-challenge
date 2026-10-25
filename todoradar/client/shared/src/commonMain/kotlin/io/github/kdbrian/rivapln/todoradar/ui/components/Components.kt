package io.github.kdbrian.rivapln.todoradar.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kdbrian.rivapln.todoradar.ui.theme.NodeShape
import io.github.kdbrian.rivapln.todoradar.ui.theme.Radar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------- git-graph primitives ----------

/** Draw a node glyph (outlined, ink) centred in a square of [extent] px. */
fun DrawScope.nodeGlyph(shape: NodeShape, center: Offset, extent: Float, ink: Color, stroke: Float) {
    val h = extent / 2
    val style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (shape) {
        NodeShape.Circle -> drawCircle(ink, h * 0.9f, center, style = style)
        NodeShape.Dot -> drawCircle(ink, h * 0.45f, center)
        NodeShape.Square -> drawRect(ink, Offset(center.x - h * 0.8f, center.y - h * 0.8f), Size(h * 1.6f, h * 1.6f), style = style)
        NodeShape.Diamond -> drawPath(Path().apply {
            moveTo(center.x, center.y - h); lineTo(center.x + h, center.y)
            lineTo(center.x, center.y + h); lineTo(center.x - h, center.y); close()
        }, ink, style = style)
        NodeShape.Triangle -> drawPath(Path().apply {
            moveTo(center.x, center.y - h); lineTo(center.x + h * 0.95f, center.y + h * 0.75f)
            lineTo(center.x - h * 0.95f, center.y + h * 0.75f); close()
        }, ink, style = style)
        NodeShape.Cross -> {
            drawLine(ink, Offset(center.x - h * 0.7f, center.y - h * 0.7f), Offset(center.x + h * 0.7f, center.y + h * 0.7f), stroke, StrokeCap.Round)
            drawLine(ink, Offset(center.x + h * 0.7f, center.y - h * 0.7f), Offset(center.x - h * 0.7f, center.y + h * 0.7f), stroke, StrokeCap.Round)
        }
        NodeShape.Trash -> {
            // Lid, handle and a tapered bin with two ribs.
            drawLine(ink, Offset(center.x - h * 0.8f, center.y - h * 0.55f), Offset(center.x + h * 0.8f, center.y - h * 0.55f), stroke, StrokeCap.Round)
            drawLine(ink, Offset(center.x - h * 0.25f, center.y - h * 0.8f), Offset(center.x + h * 0.25f, center.y - h * 0.8f), stroke, StrokeCap.Round)
            drawPath(Path().apply {
                moveTo(center.x - h * 0.6f, center.y - h * 0.4f); lineTo(center.x - h * 0.45f, center.y + h * 0.85f)
                lineTo(center.x + h * 0.45f, center.y + h * 0.85f); lineTo(center.x + h * 0.6f, center.y - h * 0.4f)
            }, ink, style = style)
            drawLine(ink, Offset(center.x - h * 0.15f, center.y - h * 0.15f), Offset(center.x - h * 0.15f, center.y + h * 0.55f), stroke * 0.8f, StrokeCap.Round)
            drawLine(ink, Offset(center.x + h * 0.15f, center.y - h * 0.15f), Offset(center.x + h * 0.15f, center.y + h * 0.55f), stroke * 0.8f, StrokeCap.Round)
        }
        NodeShape.Arrow -> {
            drawLine(ink, Offset(center.x - h * 0.75f, center.y), Offset(center.x + h * 0.7f, center.y), stroke, StrokeCap.Round)
            drawPath(Path().apply {
                moveTo(center.x + h * 0.15f, center.y - h * 0.55f); lineTo(center.x + h * 0.75f, center.y)
                lineTo(center.x + h * 0.15f, center.y + h * 0.55f)
            }, ink, style = style)
        }
        NodeShape.Check -> drawPath(Path().apply {
            moveTo(center.x - h * 0.8f, center.y); lineTo(center.x - h * 0.2f, center.y + h * 0.6f)
            lineTo(center.x + h * 0.85f, center.y - h * 0.6f)
        }, ink, style = style)
    }
}

/** A git-graph node: pastel disc, ink ring, ink glyph inside. */
@Composable
fun GitNode(shape: NodeShape, fill: Color, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    val ink = Radar.colors.ink
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        val stroke = (r * 0.13f).coerceAtLeast(1.5f)
        drawCircle(fill, r - stroke / 2)
        drawCircle(ink, r - stroke / 2, style = Stroke(stroke))
        nodeGlyph(shape, center, r * 0.75f, ink, stroke * 0.9f)
    }
}

/**
 * A row hanging off a vertical rail, like a commit graph. The rail runs the full row height
 * (trimmed above the first / below the last item) and the node sits at the top of the row.
 */
@Composable
fun RailRow(
    shape: NodeShape,
    fill: Color,
    modifier: Modifier = Modifier,
    first: Boolean = false,
    last: Boolean = false,
    nodeSize: Dp = 26.dp,
    nodeTop: Dp = 18.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val ink = Radar.colors.ink
    Row(
        modifier.drawBehind {
            val x = 18.dp.toPx()
            val nodeY = nodeTop.toPx() + nodeSize.toPx() / 2
            val top = if (first) nodeY else 0f
            // Overdraw into the list gap below so consecutive rows read as one rail.
            val bottom = if (last) nodeY else size.height + 16.dp.toPx()
            drawLine(ink, Offset(x, top), Offset(x, bottom), 2.dp.toPx())
        },
    ) {
        Box(Modifier.width(36.dp).padding(top = nodeTop), contentAlignment = Alignment.TopCenter) {
            GitNode(shape, fill, size = nodeSize)
        }
        Spacer(Modifier.width(8.dp))
        content()
    }
}

/** Decorative branch doodle in the style of the brand art. Deterministic, no state. */
@Composable
fun GraphDoodle(modifier: Modifier = Modifier) {
    val c = Radar.colors
    Canvas(modifier) {
        val u = size.minDimension / 6
        val stroke = 2.dp.toPx()
        val nodes = listOf(
            Triple(Offset(u * 1.2f, u * 0.9f), NodeShape.Diamond, c.sky),
            Triple(Offset(u * 1.2f, u * 2.6f), NodeShape.Circle, c.lavender),
            Triple(Offset(u * 3.4f, u * 2.6f), NodeShape.Square, c.sky),
            Triple(Offset(u * 3.4f, u * 4.4f), NodeShape.Triangle, c.mint),
            Triple(Offset(u * 1.2f, u * 4.4f), NodeShape.Circle, c.lavender),
        )
        drawLine(c.ink, Offset(u * 1.2f, 0f), Offset(u * 1.2f, size.height), stroke)
        drawPath(Path().apply {
            moveTo(u * 1.2f, u * 1.5f)
            cubicTo(u * 1.2f, u * 2.1f, u * 3.4f, u * 1.6f, u * 3.4f, u * 2.4f)
            lineTo(u * 3.4f, size.height * 0.95f)
        }, c.ink, style = Stroke(stroke))
        nodes.forEach { (p, shape, fill) ->
            val r = u * 0.55f
            drawCircle(fill, r, p)
            drawCircle(c.ink, r, p, style = Stroke(stroke))
            nodeGlyph(shape, p, r * 0.75f, c.ink, stroke * 0.9f)
        }
    }
}

// ---------- brand ----------

/** "TODO Radar." wordmark with a mint full stop. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            append("TODO Radar")
            withStyle(SpanStyle(color = Radar.colors.mintDeep)) { append(".") }
        },
        modifier,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
    )
}

/** Radar sweep drawn in the graph style: ink rings, mint sweep, node blips. */
@Composable
fun RadarSweep(modifier: Modifier = Modifier, size: Dp = 64.dp, active: Boolean = true, blips: Int = 3) {
    val c = Radar.colors
    val transition = rememberInfiniteTransition()
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(2_400, easing = LinearEasing)))
    val pulse by transition.animateFloat(0.75f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2 - 1.dp.toPx()
        val stroke = 1.5.dp.toPx()
        drawCircle(Color.White, r)
        if (active) rotate(angle) {
            drawCircle(Brush.sweepGradient(0f to Color.Transparent, 0.72f to Color.Transparent, 1f to c.mint), r)
        }
        listOf(1f, 0.64f, 0.3f).forEach { drawCircle(c.ink.copy(alpha = if (it == 1f) 1f else 0.35f), r * it, style = Stroke(stroke)) }
        if (active) rotate(angle) { drawLine(c.ink, center, Offset(center.x + r, center.y), stroke, StrokeCap.Round) }
        val fills = listOf(c.lavender, c.sky, c.mint, c.peach, c.lavender)
        repeat(blips.coerceAtMost(5)) { i ->
            val a = (i * 137.5 + 30) * PI / 180
            val d = r * (0.38f + 0.12f * i)
            val p = Offset(center.x + d * cos(a).toFloat(), center.y + d * sin(a).toFloat())
            val br = r * 0.11f * (if (active) pulse else 1f)
            drawCircle(fills[i], br, p)
            drawCircle(c.ink, br, p, style = Stroke(stroke * 0.8f))
        }
    }
}

// ---------- buttons ----------

enum class PlayGlyph { Play, Pause, Plus }

/** The big round action button from the brand art: mint disc, ink ring, outlined glyph. */
@Composable
fun PlayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: PlayGlyph = PlayGlyph.Play,
    size: Dp = 64.dp,
    fill: Color = Radar.colors.mint,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val c = Radar.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) fill else c.hairline,
        modifier = modifier.size(size).border(2.dp, c.ink.copy(alpha = if (enabled) 1f else 0.3f), CircleShape)
            .semantics { contentDescription?.let { this.contentDescription = it } },
    ) {
        Canvas(Modifier.fillMaxWidthSquare()) {
            val s = this.size.minDimension
            val stroke = (s * 0.04f).coerceAtLeast(2.dp.toPx())
            val ink = c.ink.copy(alpha = if (enabled) 1f else 0.35f)
            val style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (glyph) {
                PlayGlyph.Play -> drawPath(Path().apply {
                    moveTo(s * 0.4f, s * 0.3f); lineTo(s * 0.7f, s * 0.5f); lineTo(s * 0.4f, s * 0.7f); close()
                }, ink, style = style)
                PlayGlyph.Pause -> {
                    drawRoundRect(ink, Offset(s * 0.36f, s * 0.31f), Size(s * 0.1f, s * 0.38f), CornerRadius(stroke), style = style)
                    drawRoundRect(ink, Offset(s * 0.54f, s * 0.31f), Size(s * 0.1f, s * 0.38f), CornerRadius(stroke), style = style)
                }
                PlayGlyph.Plus -> {
                    drawLine(ink, Offset(s * 0.5f, s * 0.32f), Offset(s * 0.5f, s * 0.68f), stroke, StrokeCap.Round)
                    drawLine(ink, Offset(s * 0.32f, s * 0.5f), Offset(s * 0.68f, s * 0.5f), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

private fun Modifier.fillMaxWidthSquare() = this.then(Modifier.fillMaxWidth().fillMaxHeight())

/** Big serif verb next to a [PlayButton], as in "Sign in ▷" / "Authorize ▷". */
@Composable
fun ActionRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: PlayGlyph = PlayGlyph.Play, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        PlayButton(onClick, glyph = glyph, enabled = enabled, contentDescription = label)
    }
}

// ---------- chips & text ----------

/** Soft pill: tinted background, deep text. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, background: Color = color.copy(alpha = 0.14f)) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(8.dp)).background(background).padding(horizontal = 9.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
    )
}

/** Commit sha chip (like the commit list inspiration): sky background, mono text, tiny commit glyph. */
@Composable
fun ShaChip(sha: String, modifier: Modifier = Modifier) {
    val c = Radar.colors
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(c.sky.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val st = 1.5.dp.toPx()
            drawLine(c.skyDeep, Offset(0f, center.y), Offset(size.width, center.y), st)
            drawCircle(Color.White, size.minDimension * 0.28f)
            drawCircle(c.skyDeep, size.minDimension * 0.28f, style = Stroke(st))
        }
        Spacer(Modifier.width(5.dp))
        Text(sha, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = c.skyDeep)
    }
}

/** Tag as a mini node + label: ○ TODO ◇ FIXME △ HACK □ XXX. */
@Composable
fun TagChip(tag: String, modifier: Modifier = Modifier, count: Int? = null) {
    val c = Radar.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GitNode(c.tagShape(tag), c.tagFill(tag), size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            if (count != null) "$tag $count" else tag,
            style = MaterialTheme.typography.labelMedium,
            color = c.ink,
        )
    }
}

/** Small round count badge, like "Tasks (9)". */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier, selected: Boolean = true) {
    val c = Radar.colors
    Box(
        modifier.size(22.dp).clip(CircleShape).background(if (selected) c.ink else c.hairline),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = if (count > 99) 8.sp else 11.sp),
            color = if (selected) Color.White else c.ink,
        )
    }
}

/** Text tab with a count badge; the selected tab is ink, others muted. */
@Composable
fun CountTab(label: String, count: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Radar.colors
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = if (selected) c.ink else c.inkSoft)
        Spacer(Modifier.width(6.dp))
        CountBadge(count, selected = selected)
    }
}

/** Big serif number + caption. */
@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier, color: Color = Radar.colors.ink) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.displaySmall, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Radar.colors.inkSoft)
    }
}

/** Ink-outlined progress bar with a mint fill. */
@Composable
fun InkProgress(progress: Float, modifier: Modifier = Modifier) {
    val c = Radar.colors
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(450))
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val h = size.height
        val st = 1.5.dp.toPx()
        drawRoundRect(Color.White, cornerRadius = CornerRadius(h / 2))
        if (animated > 0f) {
            drawRoundRect(c.mint, size = Size((size.width * animated).coerceAtLeast(h), h), cornerRadius = CornerRadius(h / 2))
        }
        drawRoundRect(c.ink, topLeft = Offset(st / 2, st / 2), size = Size(size.width - st, h - st), cornerRadius = CornerRadius(h / 2), style = Stroke(st))
    }
}

/** Resolve ◇ — Download □ — Scan △ — Diff ○ as a horizontal branch of nodes. */
@Composable
fun StageGraph(current: Int, modifier: Modifier = Modifier) {
    val c = Radar.colors
    val stages = listOf("Resolve" to NodeShape.Diamond, "Download" to NodeShape.Square, "Scan" to NodeShape.Triangle, "Diff" to NodeShape.Circle)
    val pulse by rememberInfiniteTransition().animateFloat(1f, 1.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse))
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        stages.forEachIndexed { i, (label, shape) ->
            val fill by animateColorAsState(
                when {
                    i < current -> c.mint
                    i == current -> c.sky
                    else -> Color.White
                },
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    GitNode(shape, fill, size = if (i == current) (26 * pulse).dp else 26.dp)
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i <= current) c.ink else c.inkSoft,
                )
            }
            if (i < stages.lastIndex) {
                Box(
                    Modifier.weight(1f).padding(top = 16.dp).height(2.dp)
                        .background(if (i < current) c.ink else c.ink.copy(alpha = 0.18f)),
                )
            }
        }
    }
}

// ---------- surfaces & text ----------

/** White card with a hairline and a soft shadow. */
@Composable
fun RadarCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val c = Radar.colors
    val shape = MaterialTheme.shapes.large
    val decorated = modifier
        .shadow(1.dp, shape, ambientColor = c.ink.copy(alpha = 0.08f), spotColor = c.ink.copy(alpha = 0.08f))
        .border(if (accent != null) 1.5.dp else 1.dp, accent ?: c.hairline, shape)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = decorated, shape = shape, color = c.card) { content() }
    } else {
        Surface(modifier = decorated, shape = shape, color = c.card) { content() }
    }
}

/** Serif section title with optional trailing content. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GraphDoodle(Modifier.size(110.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Radar.colors.inkSoft, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun MetaText(text: String, modifier: Modifier = Modifier, color: Color = Radar.colors.inkSoft) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Short vertical colour bar used as a list accent (tag colour), as in the task-list inspiration. */
@Composable
fun AccentBar(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(color))
}
