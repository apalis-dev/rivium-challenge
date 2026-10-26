package com.rivium.snap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.rivium.snap.ui.tabs.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : ComponentActivity() {
    private val dbPath by lazy { "${filesDir.absolutePath}/apalis.db" }
    private val gson = Gson()

    private var queueMetrics by mutableStateOf(QueueMetrics())
    private var completedWorks by mutableStateOf<List<CompletedWorkItem>>(emptyList())
    private var apalisObserver by mutableStateOf(ApalisQueueObserver())
    private var activeBatch by mutableStateOf(ActiveBatchTracker())
    private var currentBatchCounter = 0
    private var previousPendingJobs = 0L
    private val selectedWorkIds = mutableStateListOf<String>()

    // Photo Picker launcher supporting bulk multi-selection
    private val pickImagesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val stagingDir = File(cacheDir, "picked_images").apply { mkdirs() }

                val resolvedPaths = mutableListOf<String>()
                // Bounded chunk processing (2 at a time) to prevent memory & I/O pressure on 100MB+ images
                for (chunk in uris.chunked(2)) {
                    val batch = chunk.map { uri ->
                        async(Dispatchers.IO) {
                            resolveUri(this@MainActivity, uri, stagingDir)
                        }
                    }.awaitAll()
                    resolvedPaths.addAll(batch)
                }

                if (resolvedPaths.isNotEmpty()) {
                    currentBatchCounter += 1
                    val baseCompleted = CompletedWorksRepository.getCompletedWorksCount(dbPath)

                    // Start fresh batch tracker from 0% immediately
                    withContext(Dispatchers.Main) {
                        activeBatch = ActiveBatchTracker(
                            batchNumber = currentBatchCounter,
                            totalSubmitted = resolvedPaths.size,
                            initialCompletedBaseline = baseCompleted,
                            processedInBatch = 0
                        )
                    }

                    val jsonPayload = gson.toJson(resolvedPaths)
                    RustBackend.enqueueBulkJobs(dbPath, jsonPayload)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Batch #$currentBatchCounter started: ${resolvedPaths.size} images",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    // Notification permission launcher for Android 13+
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Notification channel
        NotificationHelper.createNotificationChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Boot up the Rust core engine
        RustBackend.startEngine(dbPath)

        // Start background polling for queue metrics, completed works, and Apalis tables
        startMetricsPolling()

        setContent {
            val customColorScheme = darkColorScheme().copy(
                primary = Color(0xFF0090FF),
                onPrimary = Color.White,
                primaryContainer = Color(0xFF003A6B),
                onPrimaryContainer = Color(0xFFBBDEFB),
                secondary = Color(0xFF0090FF),
                secondaryContainer = Color(0xFF003356),
                onSecondaryContainer = Color(0xFFBBDEFB),
            )
            MaterialTheme(colorScheme = customColorScheme) {
                RiviumSnapApp(
                    metrics = queueMetrics,
                    works = completedWorks,
                    activeBatch = activeBatch,
                    apalisObserver = apalisObserver,
                    dbPath = dbPath,
                    selectedIds = selectedWorkIds,
                    onToggleSelect = { id ->
                        if (selectedWorkIds.contains(id)) selectedWorkIds.remove(id) else selectedWorkIds.add(id)
                    },
                    onImportClick = { pickImagesLauncher.launch("image/*") },
                    onExportAllClick = { handleExportZip(emptyList()) },
                    onExportSelectedClick = { ids -> handleExportZip(ids) },
                    onExportItem = { id -> handleExportSingleItem(id) },
                    onClearCache = {
                        CompletedWorksRepository.clearCache(this@MainActivity, dbPath)
                        Toast.makeText(this@MainActivity, "Cache cleared successfully", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    private fun startMetricsPolling() {
        lifecycleScope.launch(Dispatchers.IO) {
            var cycle = 0
            while (isActive) {
                cycle++
                try {
                    // 1. Poll queue stats from Rust
                    val statsJson = RustBackend.getQueueStats(dbPath)
                    if (statsJson.isNotEmpty()) {
                        val metrics = gson.fromJson(statsJson, QueueMetrics::class.java)

                        // Detect batch completion transition
                        if (previousPendingJobs > 0 && metrics.pending == 0L && metrics.running == 0L && metrics.completed > 0) {
                            withContext(Dispatchers.Main) {
                                val prefs = getSharedPreferences("rivium_snap_prefs", Context.MODE_PRIVATE)
                                if (prefs.getBoolean("notifications_enabled", true)) {
                                    NotificationHelper.showCompletionNotification(
                                        this@MainActivity,
                                        activeBatch.totalSubmitted.takeIf { it > 0 } ?: metrics.completed.toInt()
                                    )
                                }
                            }
                        }
                        previousPendingJobs = metrics.pending + metrics.running

                        withContext(Dispatchers.Main) {
                            queueMetrics = metrics
                        }
                    }

                    // 2. Poll completed works from SQLite (bounded limit for UI performance)
                    val works = CompletedWorksRepository.queryCompletedWorks(dbPath, limit = 500)

                    // 3. Inspect Apalis jobs table every 3rd cycle (~1.2s) to prevent any SQLite contention
                    val observer = if (cycle % 3 == 0) {
                        CompletedWorksRepository.observeApalisJobs(dbPath)
                    } else null

                    withContext(Dispatchers.Main) {
                        completedWorks = works
                        if (observer != null) {
                            apalisObserver = observer
                        }

                        // Update current batch tracker progress using database metrics count
                        if (activeBatch.totalSubmitted > 0) {
                            val newProcessed = (queueMetrics.completed - activeBatch.initialCompletedBaseline)
                                .coerceAtLeast(0)
                                .coerceAtMost(activeBatch.totalSubmitted.toLong())
                                .toInt()

                            if (newProcessed != activeBatch.processedInBatch) {
                                activeBatch = activeBatch.copy(processedInBatch = newProcessed)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Polling error: ${e.message}")
                }

                // Adaptive delay: 150ms when batch is active for snappy UI responsiveness, 400ms when idle
                val pollDelay = if (activeBatch.totalSubmitted > 0 && !activeBatch.isFinished) 150L else 400L
                delay(pollDelay)
            }
        }
    }

    private fun handleExportZip(selectedIds: List<String>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val itemsToExport = if (selectedIds.isEmpty()) {
                completedWorks
            } else {
                completedWorks.filter { it.id in selectedIds }
            }

            if (itemsToExport.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "No processed images to export", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val zipFile = File(downloadsDir, "RiviumSnap_${System.currentTimeMillis()}.zip")
            val success = CompletedWorksRepository.exportZip(itemsToExport, zipFile)

            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(
                        this@MainActivity,
                        "Exported ${itemsToExport.size} images to Downloads/${zipFile.name}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to create ZIP archive", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleExportSingleItem(workId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val item = completedWorks.find { it.id == workId } ?: return@launch
            val file = File(item.outputPath)
            if (!file.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "File not found on disk", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destFile = File(downloadsDir, file.name)
            try {
                file.copyTo(destFile, overwrite = true)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Saved to Downloads/${destFile.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resolveUri(context: Context, uri: Uri, targetDir: File): String {
        if (uri.scheme == "file") {
            val path = uri.path
            if (path != null && File(path).exists()) return path
        }
        if (uri.scheme == "content") {
            try {
                val proj = arrayOf(android.provider.MediaStore.Images.Media.DATA)
                context.contentResolver.query(uri, proj, null, null, null)?.use { cursor ->
                    val colIdx = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.DATA)
                    if (colIdx >= 0 && cursor.moveToFirst()) {
                        val path = cursor.getString(colIdx)
                        if (!path.isNullOrEmpty() && File(path).exists()) {
                            return path
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return copyUriToInternalFile(context, uri, targetDir) ?: uri.toString()
    }

    private fun copyUriToInternalFile(context: Context, uri: Uri, targetDir: File): String? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val mimeType = context.contentResolver.getType(uri)
            val ext = when (mimeType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/bmp", "image/x-ms-bmp" -> "bmp"
                "image/tiff" -> "tiff"
                "image/vnd.microsoft.icon", "image/x-icon" -> "ico"
                "image/heic", "image/heif" -> "heic"
                "image/jpeg", "image/jpg" -> "jpg"
                else -> {
                    val pathExt = uri.path?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 4 }
                    pathExt ?: (android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "jpg")
                }
            }
            val fileName = "img_${System.currentTimeMillis()}_${(1000..9999).random()}.$ext"
            val targetFile = File(targetDir, fileName)
            inputStream?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(65536) // 64 KB buffer for high I/O throughput
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
            targetFile.absolutePath
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to cache picked URI: ${e.message}")
            null
        }
    }
}

@Composable
fun RiviumSnapApp(
    metrics: QueueMetrics,
    works: List<CompletedWorkItem>,
    activeBatch: ActiveBatchTracker,
    apalisObserver: ApalisQueueObserver,
    dbPath: String,
    selectedIds: List<String>,
    onToggleSelect: (String) -> Unit,
    onImportClick: () -> Unit,
    onExportAllClick: () -> Unit,
    onExportSelectedClick: (List<String>) -> Unit,
    onExportItem: (String) -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.CheckCircle, contentDescription = "Completed") },
                    label = { Text("Completed") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (selectedTab) {
                0 -> HomeScreen(
                    metrics = metrics,
                    works = works,
                    activeBatch = activeBatch,
                    selectedIds = selectedIds,
                    onToggleSelect = onToggleSelect,
                    onImportClick = onImportClick,
                    onExportBatch = onExportSelectedClick,
                    onExportItem = onExportItem
                )
                1 -> CompletedWorksScreen(
                    works = works,
                    selectedIds = selectedIds,
                    onToggleSelect = onToggleSelect,
                    onExportAllClick = onExportAllClick,
                    onExportSelectedClick = onExportSelectedClick
                )
                2 -> SettingsScreen(
                    apalisObserver = apalisObserver,
                    dbPath = dbPath,
                    onClearCache = onClearCache
                )
            }
        }
    }
}
