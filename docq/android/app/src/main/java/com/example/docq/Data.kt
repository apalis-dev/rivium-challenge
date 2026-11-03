package com.example.docq

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

data class Converted(
    val rowId: Long,
    val source: String,
    val pdfPath: String,
    val bytes: Long,
    val warnings: Int,
)

object Store {
    /** Finished conversions, newest first. Read-only view of the Rust side's `converted` table. */
    fun load(ctx: Context, afterRowId: Long = 0): List<Converted> {
        val f = File(ctx.filesDir, "docq.db")
        if (!f.exists()) return emptyList()
        return try {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT rowid, source, pdf_path, bytes, warnings FROM converted WHERE rowid > ? ORDER BY rowid DESC",
                    arrayOf(afterRowId.toString())
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            add(Converted(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4)))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("docq", "db read failed", e)
            emptyList()
        }
    }
}

object Inbox {
    private val exts = setOf("docx", "pptx", "xlsx")

    /** The file's real name; the extension decides the format on the Rust side. */
    private fun displayName(ctx: Context, uri: Uri): String {
        var name: String? = null
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        var n = (name ?: "document").replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (n.substringAfterLast('.', "").lowercase() !in exts) {
            val type = ctx.contentResolver.getType(uri) ?: ""
            val ext = when {
                "presentationml" in type -> "pptx"
                "spreadsheetml" in type -> "xlsx"
                else -> "docx"
            }
            n += ".$ext"
        }
        return n
    }

    /**
     * Copy each document into files/inbox as "<time>-<i>__<name>.part", then rename it,
     * so Rust never sees a half-copied file. One file = one job. Returns how many were copied.
     */
    fun add(ctx: Context, uris: List<Uri>): Int {
        val inbox = File(ctx.filesDir, "inbox").apply { mkdirs() }
        val ts = System.currentTimeMillis()
        var n = 0
        for ((i, uri) in uris.withIndex()) {
            try {
                val name = "$ts-$i" + "__" + displayName(ctx, uri)
                val part = File(inbox, "$name.part")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    part.outputStream().use { input.copyTo(it) }
                }
                if (part.renameTo(File(inbox, name))) n++
            } catch (e: Exception) {
                Log.w("docq", "copy failed for $uri", e)
            }
        }
        return n
    }
}

data class Progress(val waiting: Int, val running: Int, val finished: Int)

object Queue {
    /** waiting = files in inbox + queued jobs, running = converting, finished = done or failed. */
    fun progress(ctx: Context): Progress {
        val inbox = File(ctx.filesDir, "inbox").listFiles()
            ?.count { it.isFile && !it.name.endsWith(".part") } ?: 0
        val f = File(ctx.filesDir, "docq.db")
        if (!f.exists()) return Progress(inbox, 0, 0)
        return try {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT status, COUNT(*) FROM Jobs GROUP BY status", null).use { c ->
                    var waiting = inbox
                    var running = 0
                    var finished = 0
                    while (c.moveToNext()) {
                        when (c.getString(0)) {
                            "Done", "Failed", "Killed" -> finished += c.getInt(1)
                            "Running" -> running += c.getInt(1)
                            else -> waiting += c.getInt(1)
                        }
                    }
                    Progress(waiting, running, finished)
                }
            }
        } catch (e: Exception) {
            Log.w("docq", "progress read failed", e)
            Progress(inbox, 0, 0)
        }
    }
}