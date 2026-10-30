package com.example.linkqueue

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = LinkQueueDatabase.getInstance(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LinkQueueScreen(
                        database = database,
                        dataDirectory = filesDir.resolve("linkqueue-worker").absolutePath,
                        initialSharedText = extractSharedText(intent)
                    )
                }
            }
        }
    }

    private fun extractSharedText(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_SEND) return ""
        return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
    }
}

@Composable
private fun LinkQueueScreen(
    database: LinkQueueDatabase,
    dataDirectory: String,
    initialSharedText: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember(database) { database.savedLinkDao() }
    val links by dao.observeAll().collectAsState(initial = emptyList())

    var input by remember { mutableStateOf(initialSharedText) }
    var status by remember { mutableStateOf("Starting Rust worker…") }
    var workerReady by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(dataDirectory) {
        try {
            val code = withContext(Dispatchers.IO) {
                NativeBridge.nativeStart(
                    arrayOf("--root", dataDirectory, "--set", "data_dir=$dataDirectory")
                )
            }
            if (code == 0 || code == 1) {
                workerReady = true
                status = "Rust worker is running. Queued jobs are processed in the background."
            } else {
                val detail = withContext(Dispatchers.IO) { NativeBridge.nativeLastError() }
                status = detail.ifBlank { "Could not start the Rust worker (code $code)." }
            }
        } catch (error: LinkageError) {
            status = "Rust library could not load: ${error.message ?: "check the packaged device ABI"}"
        } catch (error: Exception) {
            status = "Could not start Rust worker: ${error.message ?: "unknown error"}"
        }

        while (isActive && workerReady) {
            try {
                val pending = withContext(Dispatchers.IO) { dao.findPending() }
                for (link in pending) {
                    val jobId = link.jobId ?: continue
                    val payload = withContext(Dispatchers.IO) {
                        NativeBridge.nativeGetResult(jobId, dataDirectory)
                    }
                    if (payload.isBlank()) continue
                    if (payload.startsWith("ERROR:")) {
                        withContext(Dispatchers.IO) {
                            dao.markJobFailed(jobId, payload.removePrefix("ERROR:").trim())
                        }
                        continue
                    }

                    val result = JSONObject(payload)
                    val resultStatus = result.optString("status", "failed")
                    if (resultStatus == "completed") {
                        val updated = withContext(Dispatchers.IO) {
                            dao.updateJobResult(
                                jobId = jobId,
                                normalizedUrl = result.optString("normalized_url"),
                                sha256 = result.optString("sha256"),
                                status = "completed",
                                error = null
                            )
                        }
                        if (updated == 0) {
                            withContext(Dispatchers.IO) {
                                dao.markJobFailed(jobId, "This normalized link is already saved.")
                            }
                        }
                    } else {
                        withContext(Dispatchers.IO) {
                            dao.markJobFailed(jobId, result.optString("error", "URL processing failed"))
                        }
                    }
                }
            } catch (error: Exception) {
                status = "Worker is running, but result refresh failed: ${error.message ?: "unknown error"}"
            }
            delay(1_000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("LinkQueue", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Share a link or enter one to queue Rust URL processing.")
        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("URL") },
            placeholder = { Text("https://example.com") },
            singleLine = true,
            enabled = !isSaving
        )

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            enabled = workerReady && !isSaving,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val submittedUrl = input.trim()
                val uri = Uri.parse(submittedUrl)
                if (submittedUrl.isEmpty() || uri.scheme?.lowercase() !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                    status = "Enter a valid HTTP or HTTPS URL."
                    return@Button
                }

                isSaving = true
                status = "Adding URL to the durable queue…"
                scope.launch {
                    val jobId = UUID.randomUUID().toString()
                    try {
                        val alreadySaved = withContext(Dispatchers.IO) {
                            dao.findByOriginalUrl(submittedUrl) != null ||
                                dao.findByNormalizedUrl(submittedUrl) != null
                        }
                        if (alreadySaved) {
                            status = "This link is already saved."
                            return@launch
                        }

                        val saved = SavedLink(
                            originalUrl = submittedUrl,
                            normalizedUrl = submittedUrl,
                            sha256 = "",
                            jobId = jobId,
                            status = "queued"
                        )
                        val inserted = withContext(Dispatchers.IO) {
                            database.withTransaction { dao.insert(saved) }
                        }
                        if (inserted == -1L) {
                            status = "This link is already saved."
                            return@launch
                        }

                        val enqueueError = withContext(Dispatchers.IO) {
                            NativeBridge.nativeEnqueue(jobId, submittedUrl, dataDirectory)
                        }
                        if (enqueueError.isNotBlank()) {
                            dao.markJobFailed(jobId, enqueueError.removePrefix("ERROR:").trim())
                            status = enqueueError.removePrefix("ERROR:").trim()
                        } else {
                            status = "Link queued. Its result will appear below."
                            input = ""
                        }
                    } catch (error: LinkageError) {
                        status = "Rust queue call failed: ${error.message ?: "native library unavailable"}"
                    } catch (error: Exception) {
                        status = "Could not queue URL: ${error.message ?: "unknown error"}"
                    } finally {
                        isSaving = false
                    }
                }
            }
        ) {
            Text(if (isSaving) "Queuing…" else "Queue link")
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Saved links", style = MaterialTheme.typography.titleLarge)
            Text("${links.size}", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        if (links.isEmpty()) {
            Text("No links saved yet.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(links, key = { it.id }) { link ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                link.normalizedUrl,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.normalizedUrl)))
                                }
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Status: ${link.status}", style = MaterialTheme.typography.labelLarge)
                            if (link.error != null) {
                                Text(link.error, style = MaterialTheme.typography.bodySmall)
                            }
                            if (link.sha256.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "SHA-256: ${link.sha256}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                DateFormat.getDateTimeInstance().format(Date(link.createdAt)),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { dao.deleteById(link.id) }
                                    status = "Link deleted."
                                }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
