package io.github.kdbrian.rivapln.todoradar.api

/** Everything a client can ask of the engine. The real implementation is [HttpRadarApi]. */
interface RadarApi {
    suspend fun health(): Boolean

    suspend fun authStatus(): AuthStatus
    suspend fun startSignIn(): AuthStatus
    suspend fun signOut()

    /** Empty [query] lists the signed-in user's repos; otherwise searches GitHub. */
    suspend fun repos(query: String = ""): List<Repo>

    suspend fun startScan(owner: String, repo: String): Scan
    suspend fun startZipScan(path: String): Scan
    suspend fun scans(activeOnly: Boolean = false): List<Scan>
    suspend fun scan(id: Long): Scan
    suspend fun todos(id: Long, onlyNew: Boolean = false): List<Todo>
    suspend fun report(id: Long): String

    /** A few lines of code around [line] in [path] for the code peek. */
    suspend fun peek(id: Long, path: String, line: Long, context: Int = 6): Peek

    /** Cancel the default deletion of this scan's download. */
    suspend fun keepFiles(id: Long): Scan

    /** Delete this scan's download right now. */
    suspend fun deleteFiles(id: Long): Scan
}
