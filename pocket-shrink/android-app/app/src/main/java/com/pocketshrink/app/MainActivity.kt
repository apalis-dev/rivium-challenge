package com.pocketshrink.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class JobDisplayItem(
    val id: String,
    val inputPath: String,
    val outputPath: String,
    val originalSizeBytes: Long,
    val processedSizeBytes: Long,
    val sha256Hash: String,
    val width: Int,
    val height: Int,
    val savedPercent: Int,
    val completedAt: Long
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Start background worker service automatically
        PocketBackendService.start(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF64B5F6),
                    secondary = androidx.compose.ui.graphics.Color(0xFF81C784),
                    background = androidx.compose.ui.graphics.Color(0xFF121212),
                    surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PocketShrinkScreen(this)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketShrinkScreen(context: Context) {
    val rootDir = remember {
        try {
            File(context.applicationInfo.dataDir, "files/pocket-data").canonicalFile
        } catch (_: Exception) {
            File(context.filesDir, "pocket-data")
        }.apply { mkdirs() }
    }
    var jobs by remember { mutableStateOf(listOf<JobDisplayItem>()) }
    var isWorkerRunning by remember { mutableStateOf(true) }

    // Periodically refresh completed jobs list from SQLite via JNI
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val jsonStr = Bridge.nativeGetCompletedJobs(rootDir.absolutePath)
                if (jsonStr.isNotEmpty() && jsonStr != "[]") {
                    val array = JSONArray(jsonStr)
                    val items = mutableListOf<JobDisplayItem>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        items.add(
                            JobDisplayItem(
                                id = obj.getString("id"),
                                inputPath = obj.getString("input_path"),
                                outputPath = obj.getString("output_path"),
                                originalSizeBytes = obj.optLong("original_size_bytes", 0),
                                processedSizeBytes = obj.optLong("processed_size_bytes", 0),
                                sha256Hash = obj.optString("sha256_hash", ""),
                                width = obj.optInt("width", 0),
                                height = obj.optInt("height", 0),
                                savedPercent = obj.optInt("saved_percent", 0),
                                completedAt = obj.optLong("completed_at", 0)
                            )
                        )
                    }
                    jobs = items
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            delay(1500)
        }
    }

    // Photo picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val cacheDir = File(context.cacheDir, "picked_photos").apply { mkdirs() }
                val targetFile = File(cacheDir, "picked_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Bridge.nativeEnqueue(rootDir.absolutePath, targetFile.absolutePath, 1280)
                PocketBackendService.start(context)
                Toast.makeText(context, "Photo added to background queue!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Error queuing photo: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "PocketShrink",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Offline Privacy Sanitizer (Rivium + Apalis)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isWorkerRunning) androidx.compose.ui.graphics.Color(0xFF4CAF50) else androidx.compose.ui.graphics.Color.Gray)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isWorkerRunning) "Worker Active" else "Idle",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Simple explanation card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "🛡️ Privacy Photo Sanitizer",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Strips hidden GPS coordinates & compresses images safely using a Rust background engine.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "⚡ How to Test the Challenge:",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "1. Tap 'Queue 3 Photos' below.\n" +
                                    "2. Swipe-kill the app immediately from Recent Apps.\n" +
                                    "3. Reopen: All jobs were saved in SQLite and will finish processing automatically!",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                }
            }

            // Quick Actions Button Bar
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { photoPickerLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pick Photo")
                    }

                    OutlinedButton(
                        onClick = {
                            // Enqueue 3 synthetic sample photos to test batch & persistence
                            val testDir = File(context.cacheDir, "sample_tests").apply { mkdirs() }
                            for (idx in 1..3) {
                                val testFile = File(testDir, "test_photo_$idx.jpg")
                                val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(bitmap)
                                val paint = Paint().apply {
                                    color = if (idx == 1) Color.RED else if (idx == 2) Color.BLUE else Color.MAGENTA
                                }
                                canvas.drawRect(0f, 0f, 800f, 600f, paint)
                                FileOutputStream(testFile).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                }

                                val enqueueResult = Bridge.nativeEnqueue(rootDir.absolutePath, testFile.absolutePath, 1280)
                                android.util.Log.d("PocketShrink", "nativeEnqueue photo $idx result: $enqueueResult")
                            }
                            PocketBackendService.start(context)
                            Toast.makeText(context, "Queued 3 test photos! Try force killing the app.", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Queue 3 Photos")
                    }
                }
            }

            // Share Sheet Tip
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF1E2833))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color(0xFF64B5F6)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Tip: Open Google Photos or Gallery, tap Share -> select PocketShrink to queue photos directly from outside the app!",
                            fontSize = 12.sp,
                            color = androidx.compose.ui.graphics.Color(0xFFB0BEC5)
                        )
                    }
                }
            }

            // Completed Jobs Section Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Completed Queue Results (${jobs.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            if (jobs.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "📸 No Photos Cleaned Yet",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap 'Pick Photo' or 'Queue 3 Photos' to see the background engine in action.",
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            } else {
                items(jobs) { job ->
                    JobCard(job, context)
                }
            }
        }
    }
}

@Composable
fun JobCard(job: JobDisplayItem, context: Context) {
    val dateStr = remember(job.completedAt) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.format(Date(job.completedAt * 1000))
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = job.id,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = dateStr,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Original Size",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "${job.originalSizeBytes / 1024} KB",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }

                Column {
                    Text(
                        text = "Compressed",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "${job.processedSizeBytes / 1024} KB",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Column {
                    Text(
                        text = "Data Saved",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "${job.savedPercent}%",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = androidx.compose.ui.graphics.Color(0xFF4CAF50)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // SHA-256 Hash box with tap to copy
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = androidx.compose.ui.graphics.Color(0xFF2C2C2C),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("SHA256", job.sha256Hash))
                        Toast.makeText(context, "SHA-256 hash copied!", Toast.LENGTH_SHORT).show()
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SHA-256: ${job.sha256Hash.take(16)}...${job.sha256Hash.takeLast(8)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Copy Hash",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "🛡️ Sensitive EXIF GPS metadata stripped",
                fontSize = 11.sp,
                color = androidx.compose.ui.graphics.Color(0xFF81C784)
            )
        }
    }
}
