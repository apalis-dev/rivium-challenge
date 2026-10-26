package com.rivium.snap.ui.tabs

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rivium.snap.ApalisJobDetail
import com.rivium.snap.ApalisQueueObserver
import com.rivium.snap.CompletedWorksRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    apalisObserver: ApalisQueueObserver,
    dbPath: String,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("rivium_snap_prefs", Context.MODE_PRIVATE) }

    var compressionQuality by remember {
        mutableFloatStateOf(sharedPrefs.getFloat("compression_quality", 80f))
    }
    var maxResolution by remember {
        mutableStateOf(sharedPrefs.getString("max_resolution", "1600x1600") ?: "1600x1600")
    }
    var autoExportToDownloads by remember {
        mutableStateOf(sharedPrefs.getBoolean("auto_export_downloads", false))
    }
    var notificationsEnabled by remember {
        mutableStateOf(sharedPrefs.getBoolean("notifications_enabled", true))
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                )
                Text(
                    text = "Configure app, image pipeline, and monitor Apalis SQLite",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.outline)
                )
            }
        }

        // Section 1: Apalis Queue Observer
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = "Apalis Observer",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Apalis Queue Observer",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Text(
                        text = "Real-time task distribution in SQLite 'Jobs' persistence table:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ObserverMetricBadge("Pending", apalisObserver.pendingCount, MaterialTheme.colorScheme.surfaceVariant)
                        ObserverMetricBadge("Queued", apalisObserver.queuedCount, MaterialTheme.colorScheme.secondaryContainer)
                        ObserverMetricBadge("Running", apalisObserver.runningCount, Color(0xFF0090FF).copy(alpha = 0.2f))
                        ObserverMetricBadge("Done", apalisObserver.doneCount, Color(0xFF2E7D32).copy(alpha = 0.2f))
                        ObserverMetricBadge("Failed", apalisObserver.failedCount, MaterialTheme.colorScheme.errorContainer)
                    }
                }
            }
        }

        // Section 2: Image Pipeline Settings
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Image Settings",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Image Processing Settings",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Output Quality / Compression", style = MaterialTheme.typography.bodyMedium)
                            Text("${compressionQuality.toInt()}%", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            text = "Supports JPEG, PNG, WebP, GIF, BMP, TIFF, ICO. Transparency and formats are preserved.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Slider(
                            value = compressionQuality,
                            onValueChange = {
                                compressionQuality = it
                                sharedPrefs.edit().putFloat("compression_quality", it).apply()
                            },
                            valueRange = 40f..100f,
                            steps = 11,
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Max Resize Constraint", style = MaterialTheme.typography.bodyMedium)
                            Text("Downscales photos via Lanczos3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        TextButton(onClick = {
                            val next = when (maxResolution) {
                                "1280x1280" -> "1600x1600"
                                "1600x1600" -> "2048x2048"
                                else -> "1280x1280"
                            }
                            maxResolution = next
                            sharedPrefs.edit().putString("max_resolution", next).apply()
                        }) {
                            Text(maxResolution, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Auto-export to Downloads", style = MaterialTheme.typography.bodyMedium)
                            Text("Saves zip on batch completion", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(
                            checked = autoExportToDownloads,
                            onCheckedChange = {
                                autoExportToDownloads = it
                                sharedPrefs.edit().putBoolean("auto_export_downloads", it).apply()
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Completion Notifications", style = MaterialTheme.typography.bodyMedium)
                            Text("Show system alert when batch is 100%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(
                            checked = notificationsEnabled,
                            onCheckedChange = {
                                notificationsEnabled = it
                                sharedPrefs.edit().putBoolean("notifications_enabled", it).apply()
                            }
                        )
                    }
                }
            }
        }

        // Section 3: App Installation & Environment Details
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Installation Info",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Installation & Environment",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    val dbFile = File(dbPath)
                    val dbSizeKb = if (dbFile.exists()) dbFile.length() / 1024 else 0

                    InfoRow("Package", context.packageName)
                    InfoRow("Android Version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    InfoRow("Device Architecture", Build.SUPPORTED_ABIS.joinToString(", "))
                    InfoRow("Database Store", "apalis.db (${dbSizeKb} KB)")
                    InfoRow("Core Architecture", "Tokio 4-thread / Lanczos3 / Blake3")
                }
            }
        }

        // Section 4: Maintenance Actions
        item {
            Button(
                onClick = onClearCache,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Cache")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear Staged & Cached Images")
            }
        }

        // Recent Apalis Tasks list
        if (apalisObserver.recentJobs.isNotEmpty()) {
            item {
                Text(
                    text = "Recent Apalis Tasks in Storage:",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }

            items(apalisObserver.recentJobs, key = { it.id }) { job ->
                ApalisJobCard(job)
            }
        }
    }
}

@Composable
private fun ObserverMetricBadge(label: String, count: Long, containerColor: Color) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = containerColor
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun ApalisJobCard(job: ApalisJobDetail) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ID: #${job.id.take(12)}...",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Attempts: ${job.attempts}/${job.maxAttempts}",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.outline)
                )
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = when (job.status) {
                    "Done", "Completed" -> Color(0xFF2E7D32)
                    "Running" -> Color(0xFF0090FF)
                    "Failed" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                }
            ) {
                Text(
                    text = job.status,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}
