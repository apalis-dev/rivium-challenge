package io.github.kdbrian.rivapln.todoradar.ui.repos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kdbrian.rivapln.todoradar.api.Repo
import io.github.kdbrian.rivapln.todoradar.ui.components.CountTab
import io.github.kdbrian.rivapln.todoradar.ui.components.EmptyState
import io.github.kdbrian.rivapln.todoradar.ui.components.GitNode
import io.github.kdbrian.rivapln.todoradar.ui.components.MetaText
import io.github.kdbrian.rivapln.todoradar.ui.components.Pill
import io.github.kdbrian.rivapln.todoradar.ui.components.PlayButton
import io.github.kdbrian.rivapln.todoradar.ui.components.PlayGlyph
import io.github.kdbrian.rivapln.todoradar.ui.components.RadarCard
import io.github.kdbrian.rivapln.todoradar.ui.formatBytes
import io.github.kdbrian.rivapln.todoradar.ui.formatCount
import io.github.kdbrian.rivapln.todoradar.ui.theme.NodeShape
import io.github.kdbrian.rivapln.todoradar.ui.theme.Radar

@Composable
fun ReposScreen(vm: ReposViewModel, onBack: () -> Unit, onStarted: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Radar.colors
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, containerColor = c.paper) { padding ->
        val direction = LocalLayoutDirection.current
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + 20.dp,
                end = padding.calculateEndPadding(direction) + 20.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "top") {
                Column {
                    IconButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                    Text("Choose a repo", style = MaterialTheme.typography.displaySmall)
                    MetaText("The Rust worker downloads it (capped) and scans every file.")
                }
            }
            item(key = "search") {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::onQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search GitHub, e.g. tokio-rs/axum") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Rounded.Close, "Clear") }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color.White,
                        focusedContainerColor = Color.White,
                        unfocusedBorderColor = c.ink.copy(alpha = 0.6f),
                        focusedBorderColor = c.ink,
                    ),
                )
            }
            item(key = "label") {
                Column {
                    CountTab(if (state.query.isBlank()) "Your repositories" else "Results", state.repos.size, selected = true, onClick = {})
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp).clip(CircleShape), color = c.mintDeep)
                }
            }
            state.error?.let { message ->
                item(key = "error") { EmptyState("Couldn't load repos", message) }
            }
            if (!state.loading && state.error == null && state.repos.isEmpty()) {
                item(key = "empty") { EmptyState("Nothing found", "Try another name, or owner/repo.") }
            }
            items(state.repos, key = { it.fullName }) { repo ->
                RepoCard(repo, starting = state.starting == repo.fullName, onScan = { vm.scan(repo, onStarted) })
            }
        }
    }
}

@Composable
private fun RepoCard(repo: Repo, starting: Boolean, onScan: () -> Unit) {
    val c = Radar.colors
    RadarCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GitNode(NodeShape.Circle, languageColor(repo.language), size = 30.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(repo.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaText(repo.owner)
                repo.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                MetaText(
                    listOfNotNull(repo.language, "★ ${formatCount(repo.stars)}", formatBytes(repo.sizeKb * 1024)).joinToString("  ·  "),
                    Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            when {
                repo.tooLarge -> Pill("Too large", c.rose)
                repo.large && !starting -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PlayButton(onScan, size = 48.dp, fill = c.peach, contentDescription = "Scan ${repo.fullName} (large)")
                    Spacer(Modifier.height(4.dp))
                    Text("Large", style = MaterialTheme.typography.labelSmall, color = c.peachDeep)
                }
                starting -> CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp, color = c.mintDeep)
                else -> PlayButton(onScan, size = 48.dp, contentDescription = "Scan ${repo.fullName}")
            }
        }
    }
}

@Composable
private fun languageColor(language: String?): Color {
    val c = Radar.colors
    return when (language) {
        "Rust", "C", "C++", "Go" -> c.peach
        "Kotlin", "Java", "Scala" -> c.lavender
        "TypeScript", "JavaScript", "Dart" -> c.sky
        "Python", "Ruby", "Shell" -> c.mint
        else -> Color.White
    }
}
