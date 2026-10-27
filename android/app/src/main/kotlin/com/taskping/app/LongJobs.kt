package com.taskping.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A long-running Table job: base×1 … base×upto, one step per second, run by the Rust worker. */
data class LongJob(
  val id: String,
  val base: Long,
  val upto: Long,
  val step: Long = 0,
  val result: String? = null,
  val notified: Boolean = false,
) {
  val finished get() = result != null
}

/**
 * Remembers long-job ids across app restarts (SharedPreferences), so the UI and the
 * foreground service can ask the Rust side for each job's state with nativeCheck.
 * The jobs themselves, their progress and results live in Rust's SQLite queue.
 */
object LongJobs {
  private const val PREFS = "taskping"
  private const val KEY = "long_jobs"
  private const val KEEP = 5

  @Synchronized
  fun all(context: Context): List<LongJob> {
    val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")!!
    val array = JSONArray(json)
    return List(array.length()) { i ->
      val o = array.getJSONObject(i)
      LongJob(
        id = o.getString("id"),
        base = o.getLong("base"),
        upto = o.getLong("upto"),
        step = o.optLong("step"),
        result = if (o.has("result")) o.getString("result") else null,
        notified = o.optBoolean("notified"),
      )
    }
  }

  @Synchronized
  fun add(context: Context, job: LongJob) = save(context, (listOf(job) + all(context)).take(KEEP))

  @Synchronized
  fun update(context: Context, job: LongJob) =
    save(context, all(context).map { if (it.id == job.id) job else it })

  /** Blocking: asks Rust for the state of every unfinished job and stores the answers. */
  fun refresh(context: Context): List<LongJob> {
    val root = context.filesDir.absolutePath
    for (job in all(context).filterNot { it.finished }) {
      val state = TaskPingBridge.nativeCheck(root, job.id)
      val updated = when {
        state.startsWith("done:") -> job.copy(step = job.upto, result = state.removePrefix("done:"))
        state.startsWith("failed:") -> job.copy(result = "failed")
        state.startsWith("running:") -> job.copy(step = state.removePrefix("running:").substringBefore('/').toLong())
        else -> job // "pending" or a transient "error: ..."
      }
      if (updated != job) update(context, updated)
    }
    return all(context)
  }

  private fun save(context: Context, jobs: List<LongJob>) {
    val array = JSONArray()
    jobs.forEach { job ->
      array.put(
        JSONObject()
          .put("id", job.id).put("base", job.base).put("upto", job.upto)
          .put("step", job.step).put("notified", job.notified)
          .apply { job.result?.let { put("result", it) } }
      )
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
  }
}
