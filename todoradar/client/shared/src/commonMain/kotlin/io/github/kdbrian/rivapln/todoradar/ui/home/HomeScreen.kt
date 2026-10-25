package io.github.kdbrian.rivapln.todoradar.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kdbrian.rivapln.todoradar.api.AuthStatus
import io.github.kdbrian.rivapln.todoradar.api.Scan
import io.github.kdbrian.rivapln.todoradar.engine.EngineState
import io.github.kdbrian.rivapln.todoradar.platform.LocalPlatform
import io.github.kdbrian.rivapln.todoradar.ui.components.ActionRow
import io.github.kdbrian.rivapln.todoradar.ui.components.EmptyState
import io.github.kdbrian.rivapln.todoradar.ui.components.GitNode
import io.github.kdbrian.rivapln.todoradar.ui.components.GooeySwipe
import io.github.kdbrian.rivapln.todoradar.ui.components.GraphDoodle
import io.github.kdbrian.rivapln.todoradar.ui.components.InkProgress
import io.github.kdbrian.rivapln.todoradar.ui.components.MetaText
import io.github.kdbrian.rivapln.todoradar.ui.components.Pill
import io.github.kdbrian.rivapln.todoradar.ui.components.PlayButton
import io.github.kdbrian.rivapln.todoradar.ui.components.PlayGlyph
import io.github.kdbrian.rivapln.todoradar.ui.components.RadarCard
import io.github.kdbrian.rivapln.todoradar.ui.components.RadarSweep
import io.github.kdbrian.rivapln.todoradar.ui.components.RailRow
import io.github.kdbrian.rivapln.todoradar.ui.components.SectionTitle
import io.github.kdbrian.rivapln.todoradar.ui.components.ShaChip
import io.github.kdbrian.rivapln.todoradar.ui.components.StageGraph
import io.github.kdbrian.rivapln.todoradar.ui.components.SwipeAction
import io.github.kdbrian.rivapln.todoradar.ui.components.Wordmark
import io.github.kdbrian.rivapln.todoradar.ui.countdown
import io.github.kdbrian.rivapln.todoradar.ui.formatBytes
import io.github.kdbrian.rivapln.todoradar.ui.formatCount
import io.github.kdbrian.rivapln.todoradar.ui.nowSeconds
import io.github.kdbrian.rivapln.todoradar.ui.stageLabel
import io.github.kdbrian.rivapln.todoradar.ui.theme.NodeShape
import io.github.kdbrian.rivapln.todoradar.ui.theme.Radar
import io.github.kdbrian.rivapln.todoradar.ui.timeAgo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(vm: HomeViewModel, onScanRepo: () -> Unit, onOpenScan: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val now by produceState(nowSeconds()) { while (true) { delay(1_000); value = nowSeconds() } }
    val c = Radar.colors

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = c.paper,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            AnimatedVisibility(
                state.engine == EngineState.Running && state.auth?.signedIn == true,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                ScanFab(onScanRepo)
            }
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        LazyColumn(
            Modifier.fillMaxSize(),
            // Edge to edge: the list scrolls under the system bars while content stays inside them.
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + 20.dp,
                end = padding.calculateEndPadding(direction) + 20.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header") {
                Header(
                    signedInWithDevice = state.auth?.signedIn == true && state.auth?.source == "device",
                    onSignOut = vm::signOut,
                )
            }
            item(key = "engine") { EngineCard(state, onPause = vm::pause, onResume = vm::resume) }
            if (state.engine == EngineState.Running || state.paused) {
                item(key = "account") {
                    AccountCard(state.auth, onSignIn = vm::signIn, onCopy = platform::copy, onOpen = platform::openUrl)
                }
            }
            if (state.active.isNotEmpty()) {
                item(key = "active-title") { SectionTitle("Scanning now") }
                itemsIndexed(state.active, key = { _, s -> "active-${s.id}" }) { _, scan ->
                    ActiveScanCard(scan, paused = state.paused, onClick = { onOpenScan(scan.id) })
                }
            }
            if (state.pendingCleanup.isNotEmpty()) {
                item(key = "cleanup-title") {
                    SectionTitle("Downloads") { MetaText("swipe → keep · ← delete") }
                }
                itemsIndexed(state.pendingCleanup, key = { _, s -> "cleanup-${s.id}" }) { _, scan ->
                    GooeySwipe(
                        startAction = SwipeAction(NodeShape.Check, c.mintDeep) { vm.keep(scan.id) },
                        endAction = SwipeAction(NodeShape.Trash, c.rose, dismiss = true) { vm.deleteNow(scan.id) },
                    ) {
                        CleanupCard(scan, now, onKeep = { vm.keep(scan.id) }, onDelete = { vm.deleteNow(scan.id) })
                    }
                }
            }
            item(key = "history-title") {
                SectionTitle("Recent scans") {
                    TextButton(onClick = { scope.launch { platform.pickZip()?.let { vm.scanZip(it, onOpenScan) } } }) {
                        Icon(Icons.Rounded.FolderZip, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Scan a .zip")
                    }
                }
            }
            val history = state.history
            if (history.isEmpty() && state.loaded) {
                item(key = "empty") {
                    EmptyState(
                        "No scans yet",
                        "Pick a GitHub repo. The Rust worker downloads it, scans every file for TODO, FIXME, HACK and XXX, and compares with the last scan.",
                    )
                }
            }
            itemsIndexed(history, key = { _, s -> "scan-${s.id}" }) { index, scan ->
                // → open, ← delete the download (only while there is one to delete).
                GooeySwipe(
                    startAction = SwipeAction(NodeShape.Arrow, c.mintDeep) { onOpenScan(scan.id) },
                    endAction = if (scan.isDone && !scan.filesDeleted) {
                        SwipeAction(NodeShape.Trash, c.rose) { vm.deleteNow(scan.id) }
                    } else null,
                ) {
                    HistoryRow(scan, now, first = index == 0, last = index == history.lastIndex, onClick = { onOpenScan(scan.id) })
                }
            }
        }
    }
}

@Composable
private fun Header(signedInWithDevice: Boolean, onSignOut: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Wordmark(Modifier.weight(1f))
            if (signedInWithDevice) {
                IconButton(onClick = onSignOut) { Icon(Icons.AutoMirrored.Rounded.Logout, "Sign out") }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Every TODO,\non your radar.", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(6.dp))
                MetaText("Rust engine · Rivium + Apalis")
            }
            GraphDoodle(Modifier.size(104.dp))
        }
    }
}

@Composable
private fun ScanFab(onClick: () -> Unit) {
    val c = Radar.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(32.dp),
        color = c.mint,
        modifier = Modifier.border(2.dp, c.ink, RoundedCornerShape(32.dp)),
    ) {
        Row(Modifier.padding(start = 22.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Scan a repo", style = MaterialTheme.typography.titleMedium, color = c.ink)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).border(2.dp, c.ink, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                GitNode(NodeShape.Arrow, c.mint, size = 28.dp)
            }
        }
    }
}

@Composable
private fun EngineCard(state: HomeState, onPause: () -> Unit, onResume: () -> Unit) {
    val c = Radar.colors
    val (shape, fill, title) = when (state.engine) {
        EngineState.Running -> Triple(NodeShape.Check, c.mint, if (state.isDemo) "Demo mode" else "Engine running")
        EngineState.Starting -> Triple(NodeShape.Dot, c.sky, "Starting engine…")
        EngineState.Paused -> Triple(NodeShape.Square, c.peach, "Paused")
        EngineState.Stopped -> Triple(NodeShape.Square, c.hairline, "Stopped")
        is EngineState.Failed -> Triple(NodeShape.Cross, c.peach, "Engine error")
    }
    val body = when (val e = state.engine) {
        EngineState.Paused -> "Rivium stopped the engine gracefully. Queued scans resume where they left off."
        is EngineState.Failed -> e.message
        else -> if (state.isDemo) "Native library not bundled; showing simulated data." else "Scans run in the background and survive closing the app."
    }
    RadarCard(accent = if (state.paused) c.peachDeep else null) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            GitNode(shape, fill, size = 34.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
            }
            Spacer(Modifier.width(12.dp))
            when (state.engine) {
                EngineState.Running -> PlayButton(onPause, glyph = PlayGlyph.Pause, size = 54.dp, fill = c.sky, contentDescription = "Pause engine")
                EngineState.Paused, EngineState.Stopped, is EngineState.Failed ->
                    PlayButton(onResume, glyph = PlayGlyph.Play, size = 54.dp, contentDescription = "Resume engine")
                EngineState.Starting -> RadarSweep(size = 40.dp)
            }
        }
    }
}

@Composable
private fun AccountCard(auth: AuthStatus?, onSignIn: () -> Unit, onCopy: (String) -> Unit, onOpen: (String) -> Unit) {
    val c = Radar.colors
    when {
        auth == null -> Unit
        auth.signedIn -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            GitNode(NodeShape.Check, c.sky, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Text("@${auth.login}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            MetaText(if (auth.source == "config") "token from config" else "signed in with GitHub")
        }
        auth.pending || auth.state == "error" -> RadarCard(accent = c.skyDeep) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Enter this code", style = MaterialTheme.typography.headlineSmall)
                        MetaText("at github.com/login/device")
                    }
                    RadarSweep(size = 44.dp, blips = 2)
                }
                CodeBoxes(auth.userCode.orEmpty())
                auth.message?.let { Text(it, color = c.rose, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { auth.userCode?.let(onCopy) }) { Text("Copy code") }
                    if (auth.state == "error") TextButton(onClick = onSignIn) { Text("New code") }
                    Spacer(Modifier.weight(1f))
                    PlayButton(
                        { onOpen(auth.verificationUri ?: "https://github.com/login/device") },
                        glyph = PlayGlyph.Play,
                        size = 54.dp,
                        contentDescription = "Open GitHub",
                    )
                }
                MetaText("The Rust engine is polling GitHub and finishes sign-in on its own.")
            }
        }
        else -> RadarCard {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("TODO Radar by Rivium", style = MaterialTheme.typography.titleSmall, color = c.inkSoft)
                Text("wants to read your GitHub", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                val scopes = listOf(
                    Triple(NodeShape.Square, c.sky, "Repositories" to "Public, read only"),
                    Triple(NodeShape.Circle, c.lavender, "Profile" to "Your login name"),
                    Triple(NodeShape.Cross, c.peach, "Write access" to "Never requested"),
                )
                scopes.forEachIndexed { i, (shape, fill, text) ->
                    RailRow(shape, fill, first = i == 0, last = i == scopes.lastIndex, nodeTop = 6.dp, nodeSize = 24.dp) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(text.first, style = MaterialTheme.typography.titleSmall)
                            MetaText(text.second)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                ActionRow("Sign in", onSignIn, enabled = auth.deviceFlowAvailable)
                if (!auth.deviceFlowAvailable) MetaText("Set github_client_id in the engine config (see README).")
                MetaText("GitHub Device Flow · the token never leaves the Rust engine")
            }
        }
    }
}

/** The device code as individual ink-outlined boxes, easy to read across a room. */
@Composable
private fun CodeBoxes(code: String) {
    val c = Radar.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        code.forEach { ch ->
            if (ch == '-') {
                Box(Modifier.width(10.dp).height(2.dp).background(c.ink))
            } else {
                Box(
                    Modifier.size(width = 32.dp, height = 44.dp).clip(RoundedCornerShape(10.dp))
                        .background(c.sky.copy(alpha = 0.45f)).border(1.5.dp, c.ink, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ch.toString(), style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 22.sp))
                }
            }
        }
    }
}

@Composable
private fun ActiveScanCard(scan: Scan, paused: Boolean, onClick: () -> Unit) {
    val c = Radar.colors
    RadarCard(onClick = onClick, accent = c.ink) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadarSweep(size = 52.dp, active = !paused, blips = minOf(5, scan.todoCount.toInt()))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(scan.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        scan.shortSha?.let { ShaChip(it); Spacer(Modifier.width(8.dp)) }
                        MetaText((if (paused) "Paused · " else "") + stageLabel(scan.stage) + if (scan.attempts > 1) " · attempt ${scan.attempts}" else "")
                    }
                }
                Text("${(scan.progress * 100).toInt()}%", style = MaterialTheme.typography.headlineMedium)
            }
            InkProgress(scan.progress.toFloat())
            StageGraph(scan.stageIndex)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (scan.bytesDone > 0) Pill(formatBytes(scan.bytesDone), c.skyDeep)
                scan.entriesTotal?.let { Pill("${formatCount(scan.entriesDone)}/${formatCount(it)} files", c.lavenderDeep) }
                if (scan.todoCount > 0) Pill("${formatCount(scan.todoCount)} found", c.mintDeep)
            }
            scan.error?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CloudOff, null, Modifier.size(16.dp), tint = c.peachDeep)
                    Spacer(Modifier.width(6.dp))
                    MetaText(it)
                }
            }
        }
    }
}

@Composable
private fun CleanupCard(scan: Scan, now: Long, onKeep: () -> Unit, onDelete: () -> Unit) {
    val c = Radar.colors
    RadarCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GitNode(NodeShape.Square, c.peach, size = 28.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(scan.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                MetaText("Download deleted in ${countdown(scan.cleanupAt ?: now, now)} unless kept")
            }
            TextButton(onClick = onKeep) { Text("Keep") }
            TextButton(onClick = onDelete) { Text("Delete", color = c.rose) }
        }
    }
}

/** History as a commit graph: one node per scan on a shared ink rail. */
@Composable
private fun HistoryRow(scan: Scan, now: Long, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val c = Radar.colors
    val (shape, fill) = when {
        scan.isFailed -> NodeShape.Cross to c.peach
        scan.hasBaseline && scan.newCount > 0 -> NodeShape.Triangle to c.lavender
        else -> NodeShape.Circle to c.mint
    }
    RailRow(shape, fill, first = first, last = last, nodeTop = 22.dp) {
        RadarCard(onClick = onClick, modifier = Modifier.weight(1f).padding(bottom = 4.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(scan.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        scan.shortSha?.let { ShaChip(it); Spacer(Modifier.width(8.dp)) }
                        MetaText(
                            timeAgo(scan.finishedAt ?: scan.createdAt, now) + when {
                                scan.keepFiles && !scan.filesDeleted -> " · kept"
                                scan.isDone && !scan.filesDeleted -> " · zip on device"
                                else -> ""
                            },
                        )
                    }
                    if (scan.isFailed) {
                        MetaText(scan.error ?: "failed", color = c.rose)
                    } else if (scan.hasBaseline) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill("+${scan.newCount} new", c.mintDeep)
                            Pill("−${scan.resolvedCount} resolved", c.inkSoft)
                        }
                    }
                }
                if (scan.isDone) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatCount(scan.todoCount), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                        MetaText("TODOs")
                    }
                }
            }
        }
    }
}
