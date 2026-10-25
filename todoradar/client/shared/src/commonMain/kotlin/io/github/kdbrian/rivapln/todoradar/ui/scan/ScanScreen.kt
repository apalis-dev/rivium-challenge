package io.github.kdbrian.rivapln.todoradar.ui.scan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import io.github.kdbrian.rivapln.todoradar.ui.components.CodePeek
import io.github.kdbrian.rivapln.todoradar.ui.components.CountBadge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kdbrian.rivapln.todoradar.api.Scan
import io.github.kdbrian.rivapln.todoradar.api.Todo
import io.github.kdbrian.rivapln.todoradar.platform.LocalPlatform
import io.github.kdbrian.rivapln.todoradar.ui.components.AccentBar
import io.github.kdbrian.rivapln.todoradar.ui.components.CountTab
import io.github.kdbrian.rivapln.todoradar.ui.components.EmptyState
import io.github.kdbrian.rivapln.todoradar.ui.components.GitNode
import io.github.kdbrian.rivapln.todoradar.ui.components.GooeySwipe
import io.github.kdbrian.rivapln.todoradar.ui.components.InkProgress
import io.github.kdbrian.rivapln.todoradar.ui.components.MetaText
import io.github.kdbrian.rivapln.todoradar.ui.components.Pill
import io.github.kdbrian.rivapln.todoradar.ui.components.RadarCard
import io.github.kdbrian.rivapln.todoradar.ui.components.RadarSweep
import io.github.kdbrian.rivapln.todoradar.ui.components.RailRow
import io.github.kdbrian.rivapln.todoradar.ui.components.ShaChip
import io.github.kdbrian.rivapln.todoradar.ui.components.StageGraph
import io.github.kdbrian.rivapln.todoradar.ui.components.Stat
import io.github.kdbrian.rivapln.todoradar.ui.components.SwipeAction
import io.github.kdbrian.rivapln.todoradar.ui.components.TagChip
import io.github.kdbrian.rivapln.todoradar.ui.formatBytes
import io.github.kdbrian.rivapln.todoradar.ui.formatCount
import io.github.kdbrian.rivapln.todoradar.ui.stageLabel
import io.github.kdbrian.rivapln.todoradar.ui.theme.NodeShape
import io.github.kdbrian.rivapln.todoradar.ui.theme.Radar
import kotlinx.coroutines.launch

private val TAGS = listOf("TODO", "FIXME", "HACK", "XXX")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanScreen(vm: ScanViewModel, onBack: () -> Unit, onOpenScan: (Long) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val scan = state.scan
    val c = Radar.colors

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, containerColor = c.paper) { padding ->
        val direction = LocalLayoutDirection.current
        val grouped = state.grouped
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + 20.dp,
                end = padding.calculateEndPadding(direction) + 20.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "top") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Spacer(Modifier.weight(1f))
                    // Rescan: GitHub sources always; zips only while the file is still on disk.
                    if (scan != null && !scan.isActive && (scan.isGithub || !scan.filesDeleted)) {
                        IconButton(onClick = { vm.rescan(onOpenScan) }) { Icon(Icons.Rounded.Refresh, "Rescan") }
                    }
                    scan?.webUrl?.let { url ->
                        IconButton(onClick = { platform.openUrl(url) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open on GitHub") }
                    }
                    if (scan?.isDone == true) {
                        IconButton(onClick = { scope.launch { platform.share(vm.report(), "TODO Radar · ${scan.title}") } }) {
                            Icon(Icons.Rounded.Description, "Share report")
                        }
                    }
                }
            }
            if (scan == null) {
                item { EmptyState("Loading", state.error ?: "Asking the engine…") }
                return@LazyColumn
            }
            item(key = "title") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(scan.repo ?: scan.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        scan.owner?.let { MetaText(it); Spacer(Modifier.width(8.dp)) }
                        scan.shortSha?.let { ShaChip(it) }
                    }
                }
            }
            item(key = "header") {
                when {
                    scan.isActive -> ProgressHeader(scan)
                    scan.isFailed -> FailedHeader(scan)
                    else -> SummaryHeader(scan, state.tagCounts)
                }
            }
            if (scan.isDone && !scan.filesDeleted) {
                item(key = "files") { FilesRow(scan, onKeep = vm::keep, onDelete = vm::deleteNow) }
            }
            if (state.todos.isNotEmpty()) {
                item(key = "filters") {
                    FilterBar(state, scan, vm)
                }
                if (state.visible.isEmpty()) {
                    item(key = "no-match") {
                        EmptyState("No TODOs match", "Try fewer filters.") {
                            TextButton(onClick = vm::clearFilters) { Text("Clear filters") }
                        }
                    }
                }
            } else if (scan.isDone && state.todosLoaded) {
                item(key = "clean") { EmptyState("No TODOs", "This commit is clean. Rare, and impressive.") }
            }
            grouped.forEach { (path, todos) ->
                item(key = "file-$path") { FileHeader(path, todos.size) }
                itemsIndexed(todos, key = { _, t -> "${t.path}:${t.line}:${t.fingerprint}" }) { index, todo ->
                    GooeySwipe(
                        startAction = todo.webUrl?.let { url -> SwipeAction(NodeShape.Arrow, c.mintDeep) { platform.openUrl(url) } },
                        endAction = SwipeAction(NodeShape.Check, c.skyDeep) { platform.share(todo.shareText(), "${todo.tag} in ${todo.path}") },
                    ) {
                        TodoRow(
                            todo,
                            last = index == todos.lastIndex,
                            peek = state.peeks[todo.peekKey()],
                            onTogglePeek = { vm.togglePeek(todo) },
                            onOpen = { todo.webUrl?.let(platform::openUrl) },
                            onEditor = { todo.editorUrl?.let(platform::openUrl) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressHeader(scan: Scan) {
    val c = Radar.colors
    RadarCard(accent = c.ink) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadarSweep(size = 72.dp, blips = minOf(5, scan.todoCount.toInt()))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(stageLabel(scan.stage), style = MaterialTheme.typography.headlineSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    MetaText(
                        when (scan.stage) {
                            "download" -> "${formatBytes(scan.bytesDone)} of the repo zip"
                            "scan" -> "${formatCount(scan.entriesDone)} of ${formatCount(scan.entriesTotal ?: 0)} files · ${scan.todoCount} found"
                            else -> "Rust worker on the job"
                        },
                    )
                    scan.error?.let { MetaText(it, color = c.peachDeep) }
                }
                Text("${(scan.progress * 100).toInt()}%", style = MaterialTheme.typography.headlineMedium)
            }
            InkProgress(scan.progress.toFloat())
            StageGraph(scan.stageIndex)
        }
    }
}

@Composable
private fun FailedHeader(scan: Scan) {
    val c = Radar.colors
    RadarCard(accent = c.rose) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            GitNode(NodeShape.Cross, c.peach, size = 40.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Scan failed", style = MaterialTheme.typography.headlineSmall)
                Text(scan.error ?: "Unknown error", style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryHeader(scan: Scan, tags: Map<String, Int>) {
    val c = Radar.colors
    RadarCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Stat(formatCount(scan.todoCount), "TODOs")
                if (scan.hasBaseline) {
                    Stat("+${scan.newCount}", "new", color = c.mintDeep)
                    Stat("−${scan.resolvedCount}", "resolved", color = c.inkSoft)
                } else {
                    Stat(formatCount(scan.entriesTotal ?: 0), "files")
                }
            }
            if (tags.isNotEmpty()) {
                val total = tags.values.sum().toFloat()
                Row(
                    Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))
                        .border(1.5.dp, c.ink, RoundedCornerShape(7.dp)),
                ) {
                    TAGS.forEach { tag ->
                        val n = tags[tag] ?: 0
                        if (n > 0) Box(Modifier.weight(n / total).fillMaxHeight().background(c.tagFill(tag)))
                    }
                }
                // Wraps on narrow phones instead of squeezing "XXX 17" onto two lines.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TAGS.forEach { tag -> tags[tag]?.let { TagChip(tag, count = it) } }
                }
            }
            if (!scan.hasBaseline) MetaText("First scan of this source; the next one shows what changed.")
        }
    }
}

@Composable
private fun FilesRow(scan: Scan, onKeep: () -> Unit, onDelete: () -> Unit) {
    val c = Radar.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GitNode(if (scan.keepFiles) NodeShape.Check else NodeShape.Square, if (scan.keepFiles) c.mint else c.peach, size = 22.dp)
        Spacer(Modifier.width(10.dp))
        MetaText(if (scan.keepFiles) "Download kept on this device" else "Download deletes itself soon", Modifier.weight(1f))
        if (!scan.keepFiles) TextButton(onClick = onKeep) { Text("Keep") }
        TextButton(onClick = onDelete) { Text("Delete now", color = c.rose) }
    }
}

/** File = a branch: diamond node, mono path, count. Its TODOs hang below on the rail. */
@Composable
private fun FileHeader(path: String, count: Int) {
    val c = Radar.colors
    RailRow(NodeShape.Diamond, c.lavender, first = true, nodeTop = 10.dp, nodeSize = 22.dp, modifier = Modifier.padding(top = 10.dp)) {
        Row(Modifier.weight(1f).padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                path,
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f),
            )
            MetaText("$count")
        }
    }
}

/** Pattern field, New/All tabs, and the expandable advanced-filter panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(state: ScanState, scan: Scan, vm: ScanViewModel) {
    val c = Radar.colors
    val filter = state.filter
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        OutlinedTextField(
            value = filter.pattern,
            onValueChange = { v -> vm.updateFilter { it.copy(pattern = v) } },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search or glob: src/**/*.rs", maxLines = 1) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (filter.pattern.isNotEmpty()) IconButton(onClick = { vm.updateFilter { it.copy(pattern = "") } }) { Icon(Icons.Rounded.Close, "Clear") }
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            CountTab("All", state.todos.size, selected = !filter.onlyNew, onClick = { vm.updateFilter { it.copy(onlyNew = false) } })
            if (scan.hasBaseline) CountTab("New", scan.newCount.toInt(), selected = filter.onlyNew, onClick = { vm.updateFilter { it.copy(onlyNew = true) } })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = vm::toggleFilters) {
                Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Filters")
                if (filter.activeCount > 0) { Spacer(Modifier.width(6.dp)); CountBadge(filter.activeCount) }
            }
        }
        AnimatedVisibility(state.filtersOpen) {
            RadarCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Marker", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TAGS.forEach { tag ->
                            val selected = tag in filter.tags
                            FilterChip(
                                selected = selected,
                                onClick = { vm.updateFilter { it.copy(tags = if (selected) it.tags - tag else it.tags + tag) } },
                                label = { TagChip(tag, count = state.tagCounts[tag] ?: 0) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.tagFill(tag)),
                            )
                        }
                    }
                    Text("File type", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.extensionCounts.take(14).forEach { (ext, n) ->
                            val selected = ext in filter.extensions
                            FilterChip(
                                selected = selected,
                                onClick = { vm.updateFilter { it.copy(extensions = if (selected) it.extensions - ext else it.extensions + ext) } },
                                label = { Text(if (ext.isEmpty()) "no ext · $n" else ".$ext · $n", fontFamily = FontFamily.Monospace) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.sky),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetaText("Showing ${state.visible.size} of ${state.todos.size}", Modifier.weight(1f))
                        if (filter.activeCount > 0) TextButton(onClick = vm::clearFilters) { Text("Clear all") }
                    }
                }
            }
        }
    }
}

/** Minimal task-style row: colour bar, text, muted location; tag node sits on the rail. Expands into a code peek. */
@Composable
private fun TodoRow(todo: Todo, last: Boolean, peek: PeekState?, onTogglePeek: () -> Unit, onOpen: () -> Unit, onEditor: () -> Unit) {
    val c = Radar.colors
    RailRow(c.tagShape(todo.tag), c.tagFill(todo.tag), last = last, nodeTop = 14.dp, nodeSize = 22.dp) {
        RadarCard(modifier = Modifier.weight(1f).padding(bottom = 2.dp), onClick = onOpen, accent = if (todo.isNew) c.mintDeep else null) {
            Column {
                Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AccentBar(c.tagInk(todo.tag))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(todo.text.ifBlank { "(no description)" }, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MetaText("${todo.tag} · line ${todo.line}")
                            if (todo.isNew) { Spacer(Modifier.width(8.dp)); Pill("new", c.mintDeep) }
                        }
                    }
                    if (todo.editorUrl != null) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onEditor), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Code, "Open in editor", tint = c.inkSoft)
                        }
                    }
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onTogglePeek), contentAlignment = Alignment.Center) {
                        Icon(
                            if (peek != null) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            if (peek != null) "Hide code" else "Peek code",
                            tint = c.ink,
                        )
                    }
                }
                AnimatedVisibility(peek != null) {
                    Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                        when (peek) {
                            is PeekState.Ready -> CodePeek(peek.peek, todo.tag)
                            is PeekState.Failed -> MetaText(peek.message, color = c.rose)
                            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                RadarSweep(size = 22.dp, blips = 1)
                                Spacer(Modifier.width(8.dp))
                                MetaText("Loading code…")
                            }
                        }
                    }
                }
            }
        }
    }
}
