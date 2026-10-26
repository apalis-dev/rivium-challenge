package com.rivium.snap.ui.tabs

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class QueueMetrics(
    val pending: Long = 0,
    val running: Long = 0,
    val completed: Long = 0,
    val failed: Long = 0
) {
    val total: Long get() = pending + running + completed
    val progress: Float get() = if (total > 0) completed.toFloat() / total.toFloat() else 0f
}

/** Tracks active batch lifecycle independently so each selection gets its own 0..100% progress */
data class ActiveBatchTracker(
    val batchNumber: Int = 1,
    val totalSubmitted: Int = 0,
    val initialCompletedBaseline: Long = 0,
    val processedInBatch: Int = 0
) {
    val progress: Float
        get() = if (totalSubmitted > 0) {
            (processedInBatch.toFloat() / totalSubmitted.toFloat()).coerceIn(0f, 1f)
        } else 0f

    val isFinished: Boolean
        get() = totalSubmitted > 0 && processedInBatch >= totalSubmitted
}

data class CompletedWorkItem(
    val id: String,
    val originalUri: String,
    val outputPath: String,
    val originalSize: Long,
    val compressedSize: Long,
    val blake3Hash: String,
    val durationMs: Long,
    val processedAt: Long
) {
    val reductionPercentage: Int
        get() = if (originalSize > 0) {
            (((originalSize - compressedSize).toFloat() / originalSize.toFloat()) * 100).toInt()
        } else 0

    fun formattedSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000f)
            bytes >= 1_000 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1_000f)
            else -> "$bytes B"
        }
    }
}

/** Groups items into batches where items within 30 seconds of each other belong to the same batch. */
fun groupIntoBatches(works: List<CompletedWorkItem>): List<List<CompletedWorkItem>> {
    if (works.isEmpty()) return emptyList()
    val sorted = works.sortedByDescending { it.processedAt }
    val batches = mutableListOf<MutableList<CompletedWorkItem>>()
    var currentBatch = mutableListOf(sorted.first())

    for (i in 1 until sorted.size) {
        val timeDiff = sorted[i - 1].processedAt - sorted[i].processedAt
        if (timeDiff <= 30_000L) {
            currentBatch.add(sorted[i])
        } else {
            batches.add(currentBatch)
            currentBatch = mutableListOf(sorted[i])
        }
    }
    batches.add(currentBatch)
    return batches
}

fun formatBatchLabel(batch: List<CompletedWorkItem>): String {
    val ts = batch.maxOf { it.processedAt }
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    return sdf.format(Date(ts))
}
