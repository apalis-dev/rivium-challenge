package io.github.kdbrian.rivapln.todoradar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kdbrian.rivapln.todoradar.api.Peek
import io.github.kdbrian.rivapln.todoradar.ui.theme.RadarColors
import io.github.kdbrian.rivapln.todoradar.ui.theme.Radar

private val KEYWORDS = setOf(
    "fn", "let", "mut", "pub", "use", "mod", "impl", "struct", "enum", "trait", "match", "async", "await", "const", "static",
    "val", "var", "fun", "class", "object", "interface", "when", "data", "sealed", "override", "private", "public", "internal",
    "if", "else", "for", "while", "loop", "return", "in", "is", "as", "import", "package", "def", "function", "export",
    "type", "self", "this", "true", "false", "null", "None", "new", "try", "catch", "throw", "where", "func", "go", "defer",
)
private val TOKEN = Regex("""("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b[A-Za-z_][A-Za-z0-9_]*\b|\b\d+(?:\.\d+)?\b)""")
private val MARKER = Regex("""\b(TODO|FIXME|HACK|XXX)\b""")

/** Tiny language-agnostic highlighter: comments, strings, keywords, numbers and the marker. */
internal fun highlight(line: String, c: RadarColors): AnnotatedString = buildAnnotatedString {
    val comment = listOf("//", "#", "--", "/*", "*", "<!--").mapNotNull { mark ->
        val i = line.indexOf(mark)
        if (i >= 0 && (mark != "*" || line.trimStart().startsWith("*")) && (mark != "#" || i == line.indexOfFirst { !it.isWhitespace() })) i else null
    }.minOrNull() ?: -1
    val code = if (comment >= 0) line.substring(0, comment) else line
    var last = 0
    TOKEN.findAll(code).forEach { m ->
        append(code.substring(last, m.range.first))
        val t = m.value
        when {
            t.startsWith('"') || t.startsWith('\'') -> withStyle(SpanStyle(color = c.skyDeep)) { append(t) }
            t in KEYWORDS -> withStyle(SpanStyle(color = c.lavenderDeep, fontWeight = FontWeight.SemiBold)) { append(t) }
            t.first().isDigit() -> withStyle(SpanStyle(color = c.peachDeep)) { append(t) }
            else -> append(t)
        }
        last = m.range.last + 1
    }
    append(code.substring(last))
    if (comment >= 0) {
        val text = line.substring(comment)
        var at = 0
        withStyle(SpanStyle(color = c.inkSoft, fontStyle = FontStyle.Italic)) {
            MARKER.findAll(text).forEach { m ->
                append(text.substring(at, m.range.first))
                withStyle(SpanStyle(color = c.tagInk(m.value), fontWeight = FontWeight.Bold, fontStyle = FontStyle.Normal)) { append(m.value) }
                at = m.range.last + 1
            }
            append(text.substring(at))
        }
    }
}

/** Read-only code viewer: gutter with line numbers, the TODO line tinted in its tag colour. */
@Composable
fun CodePeek(peek: Peek, tag: String, modifier: Modifier = Modifier) {
    val c = Radar.colors
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
    val highlighted = remember(peek) { peek.lines.map { highlight(it, c) } }
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(Color(0xFFFBFBF9)).border(1.dp, c.hairline, shape)) {
        Row(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(peek.path.substringAfterLast('/'), style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = c.ink, modifier = Modifier.weight(1f))
            Text(if (peek.source == "github") "from GitHub" else "from download", style = MaterialTheme.typography.labelSmall, color = c.inkSoft)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            Column {
                peek.lines.indices.forEach { i ->
                    val n = peek.start + i
                    Text(
                        "$n",
                        style = mono.copy(color = if (n == peek.highlight) c.ink else c.inkSoft.copy(alpha = 0.7f), textAlign = TextAlign.End),
                        modifier = Modifier.width(44.dp).padding(end = 10.dp),
                    )
                }
            }
            Column {
                highlighted.forEachIndexed { i, line ->
                    val n = peek.start + i
                    Text(
                        line,
                        style = mono.copy(color = c.ink),
                        softWrap = false,
                        modifier = Modifier
                            .then(if (n == peek.highlight) Modifier.background(c.tagFill(tag).copy(alpha = 0.55f)) else Modifier)
                            .padding(end = 16.dp),
                    )
                }
            }
        }
    }
}
