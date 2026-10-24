package io.github.kdbrian.rivapln.todoradar.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire models mirroring the Rust engine's JSON (see rust/radar-core/src/api.rs). */

@Serializable
data class AuthStatus(
    /** signed_out | pending | signed_in | error */
    val state: String,
    val login: String? = null,
    /** "device" (Device Flow) or "config" (pre-issued token). */
    val source: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val message: String? = null,
    @SerialName("device_flow_available") val deviceFlowAvailable: Boolean = false,
) {
    val signedIn get() = state == "signed_in"
    val pending get() = state == "pending"
}

@Serializable
data class Repo(
    val owner: String,
    val name: String,
    @SerialName("full_name") val fullName: String,
    val description: String? = null,
    @SerialName("size_kb") val sizeKb: Long = 0,
    val stars: Long = 0,
    val language: String? = null,
    @SerialName("pushed_at") val pushedAt: String? = null,
    @SerialName("default_branch") val defaultBranch: String = "main",
    val private: Boolean = false,
    @SerialName("too_large") val tooLarge: Boolean = false,
    /** Over the soft limit: scannable, but a long download. */
    val large: Boolean = false,
)

@Serializable
data class Scan(
    val id: Long,
    @SerialName("source_kind") val sourceKind: String,
    val owner: String? = null,
    val repo: String? = null,
    @SerialName("zip_path") val zipPath: String? = null,
    val sha: String? = null,
    /** queued | running | done | failed */
    val state: String,
    /** resolve | download | scan | diff | done */
    val stage: String,
    @SerialName("bytes_done") val bytesDone: Long = 0,
    @SerialName("bytes_total") val bytesTotal: Long? = null,
    @SerialName("entries_done") val entriesDone: Long = 0,
    @SerialName("entries_total") val entriesTotal: Long? = null,
    @SerialName("todo_count") val todoCount: Long = 0,
    @SerialName("new_count") val newCount: Long = 0,
    @SerialName("resolved_count") val resolvedCount: Long = 0,
    @SerialName("prev_scan_id") val prevScanId: Long? = null,
    val attempts: Long = 0,
    @SerialName("keep_files") val keepFiles: Boolean = false,
    @SerialName("files_deleted") val filesDeleted: Boolean = false,
    @SerialName("cleanup_at") val cleanupAt: Long? = null,
    val error: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("finished_at") val finishedAt: Long? = null,
    val title: String,
    val progress: Double = 0.0,
    @SerialName("web_url") val webUrl: String? = null,
) {
    val isActive get() = state == "queued" || state == "running"
    val isGithub get() = sourceKind == "github"
    val isDone get() = state == "done"
    val isFailed get() = state == "failed"
    val shortSha get() = sha?.take(7)
    val hasBaseline get() = prevScanId != null

    /** A finished scan whose download is still waiting for its default deletion. */
    val cleanupPending get() = isDone && !filesDeleted && !keepFiles && cleanupAt != null

    val stageIndex
        get() = when (stage) {
            "resolve" -> 0
            "download" -> 1
            "scan" -> 2
            "diff" -> 3
            else -> 4
        }
}

@Serializable
data class Todo(
    val path: String,
    val line: Long,
    val tag: String,
    val text: String,
    val fingerprint: String,
    @SerialName("is_new") val isNew: Boolean = false,
    @SerialName("web_url") val webUrl: String? = null,
    @SerialName("editor_url") val editorUrl: String? = null,
) {
    /** One-line shareable form: `TODO(src/lib.rs:42): text → link`. */
    fun shareText(): String = buildString {
        append("$tag($path:$line): $text")
        webUrl?.let { append("\n$it") }
    }
}

/** Lines of code around a TODO for the inline viewer. */
@Serializable
data class Peek(
    val path: String,
    /** Line number of `lines[0]`. */
    val start: Int,
    val highlight: Int,
    val lines: List<String>,
    /** "cache" (the scan's zip) or "github" (fetched at the scanned commit). */
    val source: String,
)

class ApiException(val status: Int, override val message: String) : Exception(message)
