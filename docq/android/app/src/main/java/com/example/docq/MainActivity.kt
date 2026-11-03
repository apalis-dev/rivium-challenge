package com.example.docq

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

private val OFFICE_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
)

class MainActivity : ComponentActivity() {
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startEngine() }

    private val pickDocs =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) thread {
                Inbox.add(this, uris)
                startEngine()
            }
        }

    private fun startEngine() {
        ContextCompat.startForegroundService(this, Intent(this, ConvertService::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startEngine()

        setContent {
            MinimalTheme {
                Screen(onPick = { pickDocs.launch(OFFICE_TYPES) })
            }
        }
    }
}

// ---- actions -------------------------------------------------------------

private fun pdfUri(ctx: Context, p: Converted) =
    FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(p.pdfPath))

fun openPdf(ctx: Context, p: Converted) {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(pdfUri(ctx, p), "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        ctx.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No PDF viewer installed", Toast.LENGTH_SHORT).show()
    }
}

fun sharePdf(ctx: Context, p: Converted) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, pdfUri(ctx, p))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share PDF"))
}

fun savePdf(ctx: Context, p: Converted) {
    if (Build.VERSION.SDK_INT < 29) {
        Toast.makeText(ctx, "Saving needs Android 10+", Toast.LENGTH_SHORT).show()
        return
    }
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, p.source.substringBeforeLast('.') + ".pdf")
        put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/docq")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    if (uri == null) {
        Toast.makeText(ctx, "Save failed", Toast.LENGTH_SHORT).show()
        return
    }
    ctx.contentResolver.openOutputStream(uri)?.use { out ->
        File(p.pdfPath).inputStream().use { it.copyTo(out) }
    }
    Toast.makeText(ctx, "Saved to Downloads/docq", Toast.LENGTH_SHORT).show()
}

private fun sizeText(b: Long): String =
    if (b >= 1_048_576) "%.1f MB".format(b / 1_048_576.0) else "${maxOf(b / 1024, 1)} KB"

// ---- theme: quiet, one accent colour, follows the system light/dark setting -----

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F4BD8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8ECFF),
    onPrimaryContainer = Color(0xFF16226B),
    background = Color(0xFFF7F7F9),
    onBackground = Color(0xFF15161A),
    surface = Color.White,
    onSurface = Color(0xFF15161A),
    surfaceVariant = Color(0xFFEEEFF3),
    onSurfaceVariant = Color(0xFF6B6F7A),
    outlineVariant = Color(0xFFE3E4EA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB0FF),
    onPrimary = Color(0xFF0E1640),
    primaryContainer = Color(0xFF232C5C),
    onPrimaryContainer = Color(0xFFDDE3FF),
    background = Color(0xFF0E0F13),
    onBackground = Color(0xFFEDEEF2),
    surface = Color(0xFF16181D),
    onSurface = Color(0xFFEDEEF2),
    surfaceVariant = Color(0xFF1F2128),
    onSurfaceVariant = Color(0xFF9A9EAA),
    outlineVariant = Color(0xFF2A2D36),
)

@Composable
private fun MinimalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}

// ---- screen --------------------------------------------------------------

@Composable
fun Screen(onPick: () -> Unit) {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf(emptyList<Converted>()) }
    var progress by remember { mutableStateOf(Progress(0, 0, 0)) }
    var baseline by remember { mutableStateOf(-1) }

    LaunchedEffect(Unit) {
        while (true) {
            val (r, p) = withContext(Dispatchers.IO) { Store.load(ctx) to Queue.progress(ctx) }
            rows = r
            // Reset the batch counter whenever the queue is idle (or on the first look).
            if (p.waiting + p.running == 0 || baseline < 0 || p.finished < baseline) baseline = p.finished
            progress = p
            delay(1000)
        }
    }

    val remaining = progress.waiting + progress.running
    val done = (progress.finished - baseline).coerceAtLeast(0)
    val total = done + remaining

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            "docq",
            fontSize = 36.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-1).sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Documents to PDF, in the background.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        AddCard(onPick)

        AnimatedVisibility(visible = remaining > 0) {
            Column {
                Spacer(Modifier.height(12.dp))
                ProgressCard(done, total, progress.running, progress.waiting)
            }
        }

        Spacer(Modifier.height(20.dp))

        if (rows.isEmpty() && remaining == 0) {
            EmptyState()
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(remaining) { i -> PendingRow(running = i < progress.running) }
                if (rows.isNotEmpty()) {
                    item {
                        Text(
                            "READY  ·  ${rows.size}",
                            style = MaterialTheme.typography.labelMedium,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                items(rows, key = { it.rowId }) { r -> ResultCard(r, ctx) }
            }
        }
    }
}

@Composable
private fun AddCard(onPick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Add documents",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Word, PowerPoint or Excel.\nYou can also share files to docq from any app.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onPick,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)
            ) { Text("Choose files") }
        }
    }
}

@Composable
private fun ProgressCard(done: Int, total: Int, running: Int, waiting: Int) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Converting  $done of $total",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done.toFloat() / total },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "$running working  ·  $waiting waiting",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun PendingRow(running: Boolean) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Spacer(Modifier.size(18.dp))
            Text(
                if (running) "Converting…" else "Waiting…",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            "No PDFs yet.\nYour converted files will show up here.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TypeBadge(ext: String) {
    val (bg, fg) = when (ext) {
        "DOCX" -> Color(0xFFE3EBFF) to Color(0xFF2B4FC8)
        "PPTX" -> Color(0xFFFFE9DC) to Color(0xFFC2511C)
        "XLSX" -> Color(0xFFDDF3E4) to Color(0xFF1F7A45)
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(ext.take(4), color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ResultCard(r: Converted, ctx: Context) {
    val ext = r.source.substringAfterLast('.', "").uppercase()
    val subtitle = "PDF  ·  ${sizeText(r.bytes)}" +
        if (r.warnings > 0) "  ·  ${r.warnings} warning(s)" else ""
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(ext)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        r.source.substringBeforeLast('.'),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { openPdf(ctx, r) },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Open") }
                OutlinedButton(
                    onClick = { savePdf(ctx, r) },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
                OutlinedButton(
                    onClick = { sharePdf(ctx, r) },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Share") }
            }
        }
    }
}