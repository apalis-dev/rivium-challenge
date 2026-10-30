package com.rivium.snap

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.rivium.snap.ui.tabs.CompletedWorkItem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ApalisJobDetail(
    val id: String,
    val jobType: String,
    val status: String,
    val attempts: Int,
    val maxAttempts: Int,
    val runAt: Long,
    val doneAt: Long?,
    val lastError: String?
)

data class ApalisQueueObserver(
    val pendingCount: Long = 0,
    val queuedCount: Long = 0,
    val runningCount: Long = 0,
    val doneCount: Long = 0,
    val failedCount: Long = 0,
    val recentJobs: List<ApalisJobDetail> = emptyList()
)

object CompletedWorksRepository {
    private const val TAG = "CompletedWorksRepo"

    @Volatile
    private var cachedDb: SQLiteDatabase? = null
    private var lastDbPath: String? = null

    @Synchronized
    private fun getReadableDatabase(dbPath: String): SQLiteDatabase? {
        val dbFile = File(dbPath)
        if (!dbFile.exists()) return null

        val current = cachedDb
        if (current != null && current.isOpen && lastDbPath == dbPath) {
            return current
        }

        return try {
            try { current?.close() } catch (_: Exception) {}
            // OPEN_READONLY with NO_LOCALIZED_COLLATORS for fast, non-blocking lock-free WAL reads
            val db = SQLiteDatabase.openDatabase(
                dbPath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            cachedDb = db
            lastDbPath = dbPath
            db
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open SQLite database: ${e.message}")
            null
        }
    }

    /**
     * Total count of completed works from SQLite without fetching entire dataset.
     */
    fun getCompletedWorksCount(dbPath: String): Long {
        val db = getReadableDatabase(dbPath) ?: return 0L
        return try {
            db.rawQuery("SELECT count(*) FROM completed_works", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to count completed_works: ${e.message}")
            0L
        }
    }

    /**
     * Reads recent completed works directly from SQLite with a bounded limit to avoid OOM with large datasets.
     */
    fun queryCompletedWorks(dbPath: String, limit: Int = 500): List<CompletedWorkItem> {
        val db = getReadableDatabase(dbPath) ?: return emptyList()
        val results = mutableListOf<CompletedWorkItem>()
        try {
            val cursor = db.rawQuery(
                "SELECT id, original_uri, output_path, original_size, compressed_size, blake3_hash, duration_ms, processed_at FROM completed_works ORDER BY processed_at DESC LIMIT ?",
                arrayOf(limit.toString())
            )
            cursor.use { c ->
                val idIdx = c.getColumnIndex("id")
                val origUriIdx = c.getColumnIndex("original_uri")
                val outPathIdx = c.getColumnIndex("output_path")
                val origSizeIdx = c.getColumnIndex("original_size")
                val compSizeIdx = c.getColumnIndex("compressed_size")
                val hashIdx = c.getColumnIndex("blake3_hash")
                val durIdx = c.getColumnIndex("duration_ms")
                val procIdx = c.getColumnIndex("processed_at")

                while (c.moveToNext()) {
                    results.add(
                        CompletedWorkItem(
                            id = if (idIdx >= 0) c.getString(idIdx) else "",
                            originalUri = if (origUriIdx >= 0) c.getString(origUriIdx) else "",
                            outputPath = if (outPathIdx >= 0) c.getString(outPathIdx) else "",
                            originalSize = if (origSizeIdx >= 0) c.getLong(origSizeIdx) else 0L,
                            compressedSize = if (compSizeIdx >= 0) c.getLong(compSizeIdx) else 0L,
                            blake3Hash = if (hashIdx >= 0) c.getString(hashIdx) else "",
                            durationMs = if (durIdx >= 0) c.getLong(durIdx) else 0L,
                            processedAt = if (procIdx >= 0) c.getLong(procIdx) else 0L
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query completed_works: ${e.message}")
        }
        return results
    }

    /**
     * Directly observes Apalis Jobs table for queue monitoring.
     */
    fun observeApalisJobs(dbPath: String): ApalisQueueObserver {
        val db = getReadableDatabase(dbPath) ?: return ApalisQueueObserver()
        try {
            var pending = 0L
            var queued = 0L
            var running = 0L
            var done = 0L
            var failed = 0L

            db.rawQuery("SELECT status, count(*) as cnt FROM Jobs GROUP BY status", null).use { c ->
                val statusIdx = c.getColumnIndex("status")
                val cntIdx = c.getColumnIndex("cnt")
                while (c.moveToNext()) {
                    val status = if (statusIdx >= 0) c.getString(statusIdx) else ""
                    val count = if (cntIdx >= 0) c.getLong(cntIdx) else 0L
                    when (status) {
                        "Pending" -> pending += count
                        "Queued" -> queued += count
                        "Running" -> running += count
                        "Done", "Completed" -> done += count
                        "Failed", "Killed" -> failed += count
                    }
                }
            }

            val recent = mutableListOf<ApalisJobDetail>()
            db.rawQuery("SELECT id, job_type, status, attempts, max_attempts, run_at, done_at, last_result FROM Jobs ORDER BY run_at DESC LIMIT 15", null).use { c ->
                val idIdx = c.getColumnIndex("id")
                val typeIdx = c.getColumnIndex("job_type")
                val statusIdx = c.getColumnIndex("status")
                val attIdx = c.getColumnIndex("attempts")
                val maxAttIdx = c.getColumnIndex("max_attempts")
                val runAtIdx = c.getColumnIndex("run_at")
                val doneAtIdx = c.getColumnIndex("done_at")
                val lastResIdx = c.getColumnIndex("last_result")

                while (c.moveToNext()) {
                    recent.add(
                        ApalisJobDetail(
                            id = if (idIdx >= 0) c.getString(idIdx) else "",
                            jobType = if (typeIdx >= 0) c.getString(typeIdx) else "",
                            status = if (statusIdx >= 0) c.getString(statusIdx) else "",
                            attempts = if (attIdx >= 0) c.getInt(attIdx) else 0,
                            maxAttempts = if (maxAttIdx >= 0) c.getInt(maxAttIdx) else 25,
                            runAt = if (runAtIdx >= 0) c.getLong(runAtIdx) else 0L,
                            doneAt = if (doneAtIdx >= 0 && !c.isNull(doneAtIdx)) c.getLong(doneAtIdx) else null,
                            lastError = if (lastResIdx >= 0 && !c.isNull(lastResIdx)) c.getString(lastResIdx) else null
                        )
                    )
                }
            }

            return ApalisQueueObserver(
                pendingCount = pending,
                queuedCount = queued,
                runningCount = running,
                doneCount = done,
                failedCount = failed,
                recentJobs = recent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to observe Apalis Jobs: ${e.message}")
            return ApalisQueueObserver()
        }
    }

    /**
     * Clears processed images cache and completed works if requested by user.
     */
    fun clearCache(context: android.content.Context, dbPath: String): Boolean {
        return try {
            val pickedDir = File(context.cacheDir, "picked_images")
            if (pickedDir.exists()) {
                pickedDir.deleteRecursively()
            }
            val processedDir = File(context.filesDir, "processed_images")
            if (processedDir.exists()) {
                processedDir.deleteRecursively()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache: ${e.message}")
            false
        }
    }

    /**
     * Exports selected or all items into a ZIP archive.
     */
    fun exportZip(items: List<CompletedWorkItem>, zipOutputFile: File): Boolean {
        if (items.isEmpty()) return false
        try {
            zipOutputFile.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(zipOutputFile)).use { zos ->
                val buffer = ByteArray(32768)
                for (item in items) {
                    val file = File(item.outputPath)
                    if (file.exists() && file.isFile) {
                        val entryName = file.name
                        zos.putNextEntry(ZipEntry(entryName))
                        FileInputStream(file).use { fis ->
                            var len: Int
                            while (fis.read(buffer).also { len = it } > 0) {
                                zos.write(buffer, 0, len)
                            }
                        }
                        zos.closeEntry()
                    }
                }
            }
            return zipOutputFile.exists() && zipOutputFile.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting ZIP: ${e.message}", e)
            return false
        }
    }
}
