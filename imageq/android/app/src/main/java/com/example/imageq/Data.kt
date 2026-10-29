package com.example.imageq

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import java.io.File

data class Cleaned(
    val rowId: Long,
    val hash: String,
    val cleanPath: String,
    val thumbPath: String,
    val hadGps: Boolean,
    val camera: String,
    val origBytes: Long,
    val cleanBytes: Long,
)

object Store {
    fun load(ctx: Context, afterRowId: Long = 0): List<Cleaned> {
        val f = File(ctx.filesDir, "imageq.db")
        if (!f.exists()) return emptyList()
        return try {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT rowid, hash, clean_path, thumb, had_gps, camera, orig_bytes, clean_bytes " +
                        "FROM cleaned WHERE rowid > ? ORDER BY rowid DESC",
                    arrayOf(afterRowId.toString())
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            add(
                                Cleaned(
                                    c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                                    c.getInt(4) != 0, c.getString(5) ?: "", c.getLong(6), c.getLong(7)
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("imageq", "db read failed", e)
            emptyList()
        }
    }
}

object Inbox {
    /** Copy photos into files/inbox. Writes name.part, then renames, so Rust never sees half a file. */
    fun add(ctx: Context, uris: List<Uri>): Int {
        val inbox = File(ctx.filesDir, "inbox").apply { mkdirs() }
        var n = 0
        for ((i, uri) in uris.withIndex()) {
            try {
                val name = "${System.currentTimeMillis()}-$i.jpg"
                val part = File(inbox, "$name.part")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    part.outputStream().use { input.copyTo(it) }
                }
                if (part.renameTo(File(inbox, name))) n++
            } catch (e: Exception) {
                Log.w("imageq", "copy failed for $uri", e)
            }
        }
        return n
    }
}

data class Progress(val waiting: Int, val running: Int, val finished: Int)

object Queue {
    /** waiting = files in inbox + queued jobs, running = being cleaned, finished = done or failed. */
    fun progress(ctx: Context): Progress {
        val inbox = File(ctx.filesDir, "inbox").listFiles()?.size ?: 0
        val f = File(ctx.filesDir, "imageq.db")
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
            Log.w("imageq", "progress read failed", e)
            Progress(inbox, 0, 0)
        }
    }
}