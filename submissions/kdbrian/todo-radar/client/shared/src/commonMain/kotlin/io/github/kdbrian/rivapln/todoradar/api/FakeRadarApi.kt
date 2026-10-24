package io.github.kdbrian.rivapln.todoradar.api

import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * In-memory stand-in for the engine, used when the native library is not bundled (the `main`
 * branch) so every screen can be explored. Scans advance through the real stages over ~12 s.
 */
@OptIn(ExperimentalTime::class)
class FakeRadarApi : RadarApi {
    private var signedIn = false
    private var nextId = 1L
    private val scans = mutableListOf<Scan>()
    private val started = mutableMapOf<Long, Long>()

    private fun now() = Clock.System.now().epochSeconds

    private val sampleRepos = listOf(
        Repo("rivium-rs", "rivium", "rivium-rs/rivium", "Owned services and explicit resource lifetimes on Tokio", 2_400, 120, "Rust"),
        Repo("tokio-rs", "axum", "tokio-rs/axum", "Ergonomic and modular web framework", 9_800, 23_000, "Rust"),
        Repo("nushell", "nushell", "nushell/nushell", "A new type of shell", 71_000, 34_000, "Rust"),
        Repo("torvalds", "linux", "torvalds/linux", "Linux kernel source tree", 5_600_000, 190_000, "C", tooLarge = true),
    )

    private val sampleTodos = listOf(
        Todo("src/rt.rs", 148, "TODO", "(ben) surface restart reason in the host report", "a1", isNew = true),
        Todo("src/rt.rs", 311, "FIXME", "deadline overflow when timeout is u64::MAX", "a2"),
        Todo("src/scope.rs", 52, "HACK", "spin until the join set drains on shutdown", "a3", isNew = true),
        Todo("crates/jni/src/lib.rs", 90, "XXX", "attach config ignores daemon threads", "a4"),
        Todo("examples/basic-service.rs", 12, "TODO", "show readiness probes", "a5"),
    )

    override suspend fun health() = true

    override suspend fun authStatus() =
        if (signedIn) AuthStatus("signed_in", login = "demo-user", source = "device", deviceFlowAvailable = true)
        else AuthStatus("signed_out", deviceFlowAvailable = true)

    override suspend fun startSignIn(): AuthStatus {
        signedIn = true
        return AuthStatus(
            "pending", userCode = "WDJB-MJHT", verificationUri = "https://github.com/login/device",
            expiresIn = 900, deviceFlowAvailable = true,
        )
    }

    override suspend fun signOut() {
        signedIn = false
    }

    override suspend fun repos(query: String): List<Repo> {
        delay(300)
        return if (query.isBlank()) sampleRepos
        else sampleRepos.filter { it.fullName.contains(query.trim(), ignoreCase = true) }
    }

    override suspend fun startScan(owner: String, repo: String) = create("github", owner, repo, null)
    override suspend fun startZipScan(path: String) = create("zip", null, null, path)

    private fun create(kind: String, owner: String?, repo: String?, zip: String?): Scan {
        val id = nextId++
        val title = if (owner != null) "$owner/$repo" else zip!!.substringAfterLast('/').substringAfterLast('\\')
        val scan = Scan(
            id = id, sourceKind = kind, owner = owner, repo = repo, zipPath = zip, state = "queued",
            stage = "resolve", title = title, createdAt = now(),
            prevScanId = scans.lastOrNull { it.title == title }?.id,
        )
        scans += scan
        started[id] = now()
        return scan
    }

    /** Derive each scan's state from elapsed time so polling sees real progress. */
    private fun advance(scan: Scan): Scan {
        if (!scan.isActive && scan.cleanupAt == null) return scan
        val t = now() - (started[scan.id] ?: now())
        val updated = when {
            t < 1 -> scan.copy(state = "running", stage = "resolve", progress = 0.02, sha = null)
            t < 6 -> scan.copy(
                state = "running", stage = "download", sha = "86aa6153e47b",
                bytesDone = t * 1_100_000, bytesTotal = 6_400_000, progress = 0.05 + 0.45 * (t / 6.0),
            )
            t < 11 -> scan.copy(
                state = "running", stage = "scan", bytesDone = 6_400_000, bytesTotal = 6_400_000,
                entriesDone = (t - 6) * 620, entriesTotal = 3097, todoCount = (t - 6) * 64,
                progress = 0.5 + 0.45 * ((t - 6) / 5.0),
            )
            !scan.isDone -> scan.copy(
                state = "done", stage = "done", entriesDone = 3097, todoCount = 320, progress = 1.0,
                newCount = if (scan.prevScanId != null) 3 else 0,
                resolvedCount = if (scan.prevScanId != null) 5 else 0,
                finishedAt = now(), cleanupAt = now() + 600,
            )
            else -> scan
        }
        scans[scans.indexOfFirst { it.id == scan.id }] = updated
        return updated
    }

    override suspend fun scans(activeOnly: Boolean): List<Scan> =
        scans.map(::advance).filter { !activeOnly || it.isActive || it.cleanupPending }.reversed()

    override suspend fun scan(id: Long) = scans.first { it.id == id }.let(::advance)

    override suspend fun todos(id: Long, onlyNew: Boolean): List<Todo> {
        val scan = scan(id)
        return sampleTodos
            .map { it.copy(webUrl = scan.webUrl, editorUrl = scan.webUrl, isNew = it.isNew && scan.hasBaseline) }
            .filter { !onlyNew || it.isNew }
    }

    override suspend fun report(id: Long) =
        "# TODO Radar · ${scan(id).title}\n\n" + todos(id).joinToString("\n") { "- **${it.tag}** `${it.path}:${it.line}` — ${it.text}" }

    override suspend fun peek(id: Long, path: String, line: Long, context: Int): Peek {
        val todo = sampleTodos.firstOrNull { it.path == path && it.line == line }
        val start = (line - context).coerceAtLeast(1).toInt()
        val lines = (start..line.toInt() + context).map { n ->
            when (n.toLong()) {
                line -> "    // ${todo?.tag ?: "TODO"}: ${todo?.text ?: "demo"}"
                line - 1 -> "fn handle(ctx: &Context) -> Result<()> {"
                line + 1 -> "    let ready = ctx.ready();"
                line + 2 -> "    ctx.stopped().await;"
                line + 3 -> "}"
                else -> ""
            }
        }
        return Peek(path, start, line.toInt(), lines, "cache")
    }

    override suspend fun keepFiles(id: Long) = update(id) { it.copy(keepFiles = true) }
    override suspend fun deleteFiles(id: Long) = update(id) { it.copy(keepFiles = false, filesDeleted = true) }

    private fun update(id: Long, change: (Scan) -> Scan): Scan {
        val index = scans.indexOfFirst { it.id == id }
        scans[index] = change(scans[index])
        return scans[index]
    }
}
