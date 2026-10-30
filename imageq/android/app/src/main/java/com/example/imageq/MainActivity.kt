package com.example.imageq

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.ui.Alignment

class MainActivity : ComponentActivity() {
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startEngine() }

    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
            if (uris.isNotEmpty()) thread { Inbox.add(this, uris); startEngine() }
        }

    private fun startEngine() {
        ContextCompat.startForegroundService(this, Intent(this, CleanerService::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startEngine()

        setContent {
            MaterialTheme {
                Screen(onPick = {
                    pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
            }
        }
    }
}

fun shareClean(ctx: Context, r: Cleaned) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(r.cleanPath))
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share clean copy"))
}

fun saveClean(ctx: Context, r: Cleaned) {
    if (Build.VERSION.SDK_INT < 29) {
        Toast.makeText(ctx, "Saving needs Android 10+", Toast.LENGTH_SHORT).show()
        return
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "clean-${r.hash.take(8)}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/imageq")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri == null) {
        Toast.makeText(ctx, "Save failed", Toast.LENGTH_SHORT).show()
        return
    }
    ctx.contentResolver.openOutputStream(uri)?.use { out ->
        File(r.cleanPath).inputStream().use { it.copyTo(out) }
    }
    Toast.makeText(ctx, "Saved to Pictures/imageq", Toast.LENGTH_SHORT).show()
}


@Composable
fun Screen(onPick: () -> Unit) {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf(emptyList<Cleaned>()) }
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

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Text("imageq", style = MaterialTheme.typography.headlineMedium)
        Text("Share photos here. Location and camera info are removed, rotation is fixed.")
        Spacer(Modifier.height(8.dp))
        Button(onClick = onPick) { Text("Pick photos") }
        Spacer(Modifier.height(8.dp))

        if (remaining > 0) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Cleaning… $done of $total done", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${progress.running} working, ${progress.waiting} waiting")
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Text("${rows.size} cleaned, ${rows.count { it.hadGps }} had a location")
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(remaining) { i ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (i < progress.running) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                        else Spacer(Modifier.size(32.dp))
                        Text(if (i < progress.running) "Cleaning…" else "Waiting…")
                    }
                }
            }
            items(rows, key = { it.rowId }) { r ->
                val bmp = remember(r.thumbPath) { BitmapFactory.decodeFile(r.thumbPath)?.asImageBitmap() }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (bmp != null) {
                            Image(bmp, null, Modifier.size(80.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop)
                        }
                        Column {
                            Text(
                                if (r.hadGps) "Location removed" else "No location found",
                                fontWeight = FontWeight.Bold
                            )
                            Text(if (r.camera.isBlank()) "No camera info" else "Camera info removed: ${r.camera}")
                            Text("${r.origBytes / 1024} KB to ${r.cleanBytes / 1024} KB")
                            Row {
                                TextButton(onClick = { saveClean(ctx, r) }) { Text("Save to phone") }
                                TextButton(onClick = { shareClean(ctx, r) }) { Text("Share") }
                            }
                        }
                    }
                }
            }
        }
    }
}