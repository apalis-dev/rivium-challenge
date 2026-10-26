package dev.apalis.riviumchallenge.ui.main

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.apalis.riviumchallenge.Article
import dev.apalis.riviumchallenge.Counts
import dev.apalis.riviumchallenge.ReaderHost
import dev.apalis.riviumchallenge.ReaderService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpenArticle: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var articles by remember { mutableStateOf<List<Article>?>(null) }
    var counts by remember { mutableStateOf(Counts()) }
    var hostError by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Refresh while visible; the Rust worker updates rows in the background.
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hostError = !ReaderHost.start(context)
            while (!hostError) {
                articles = ReaderHost.list()
                counts = ReaderHost.counts()
                delay(1000)
            }
        }
    }

    fun saveTyped() {
        if (link.isNotBlank()) {
            ReaderService.save(context, link.trim())
            link = ""
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Reading list", fontWeight = FontWeight.Bold)
                        Text(summary(counts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        placeholder = { Text("Paste a link to save") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { saveTyped() }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = ::saveTyped, enabled = link.isNotBlank()) { Text("Save") }
                }
            }

            if (hostError) {
                item { Notice("The reader engine didn't start. Restart the app; if it keeps happening, check logcat for RiviumBridge.") }
            }

            if (counts.failed > 0) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${counts.failed} link(s) couldn't be saved",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { ReaderService.sync(context) }) { Text("Retry all") }
                        }
                    }
                }
            }

            val list = articles
            when {
                list == null -> Unit
                list.isEmpty() -> item {
                    Notice("Nothing saved yet.\n\nIn Chrome, YouTube or any app, tap Share → Pocket Reader. The page is downloaded in the background so you can read it later, even offline.")
                }
                else -> items(list, key = { it.id }) { article ->
                    ArticleRow(
                        article = article,
                        onOpen = { onOpenArticle(article.id) },
                        onRetry = { scope.launch { ReaderHost.retry(article.id); ReaderService.sync(context) } },
                        onRemove = { scope.launch { ReaderHost.delete(article.id); articles = ReaderHost.list() } },
                    )
                }
            }
        }
    }
}

private fun summary(c: Counts): String = buildList {
    add(if (c.unread == 0) "All caught up" else "${c.unread} unread")
    if (c.pending > 0) add("${c.pending} downloading")
}.joinToString(" · ")

@Composable
private fun Notice(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(text, modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ArticleRow(article: Article, onOpen: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    val saved = article.status == "SAVED"
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = saved, onClick = onOpen)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        article.title.ifBlank { article.url },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (article.isUnread) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.padding(top = 4.dp))
                    StatusLine(article)
                }
                if (article.isUnread) {
                    Box(
                        Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp).size(10.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            if (article.status == "FAILED" || article.status == "SAVED") {
                HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (article.status == "FAILED") TextButton(onClick = onRetry) { Text("Retry") }
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
            } else {
                Spacer(Modifier.padding(bottom = 8.dp))
            }
        }
    }
}

@Composable
private fun StatusLine(a: Article) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (text, color) = when (a.status) {
        "SAVED" -> a.metaLine() to muted
        "QUEUED" -> "${a.site} · waiting to download" to muted
        "FETCHING" -> "${a.site} · downloading…" to MaterialTheme.colorScheme.primary
        "RETRYING" -> "${a.site} · ${a.lastError}, retrying (attempt ${a.attempts} of 6)" to MaterialTheme.colorScheme.tertiary
        else -> "${a.site} · couldn't save: ${a.lastError}" to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
