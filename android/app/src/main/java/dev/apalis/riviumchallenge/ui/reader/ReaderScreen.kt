package dev.apalis.riviumchallenge.ui.reader

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apalis.riviumchallenge.Article
import dev.apalis.riviumchallenge.ReaderHost

/** Offline reader view of a saved article's extracted text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(articleId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var article by remember { mutableStateOf<Article?>(null) }
    var missing by remember { mutableStateOf(false) }

    LaunchedEffect(articleId) {
        ReaderHost.start(context)
        article = ReaderHost.open(articleId)
        missing = article == null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(article?.site.orEmpty()) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        val a = article
        val paragraphs = remember(a) { a?.body.orEmpty().split("\n\n").filter { it.isNotBlank() } }
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (missing) {
                item { Text("This article is no longer in your list.") }
            }
            if (a != null) {
                item { Text(a.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                item {
                    Text(a.metaLine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val openOriginal = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url))) }
                if (a.isVideo) {
                    item {
                        Button(onClick = openOriginal, modifier = Modifier.padding(top = 8.dp)) {
                            Text(if (a.site.isNotBlank()) "Watch on ${a.site}" else "Watch video")
                        }
                    }
                }
                if (a.description.isNotBlank()) {
                    item { Text(a.description, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyLarge) }
                }
                if (paragraphs.isEmpty() && !a.isVideo) {
                    item { Text("No readable text was found on this page. Open the original to view it.") }
                }
                items(paragraphs) { Text(it, style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp) }
                if (!a.isVideo) {
                    item {
                        OutlinedButton(onClick = openOriginal, modifier = Modifier.padding(top = 8.dp)) { Text("Open original") }
                    }
                }
            }
        }
    }
}
