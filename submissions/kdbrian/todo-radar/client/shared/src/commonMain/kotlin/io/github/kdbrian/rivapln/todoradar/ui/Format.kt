package io.github.kdbrian.rivapln.todoradar.ui

import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun nowSeconds(): Long = Clock.System.now().epochSeconds

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${(bytes / 104_857.6).roundToInt() / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "${(n / 100_000) / 10.0}M"
    n >= 10_000 -> "${n / 1000}k"
    n >= 1000 -> "${(n / 100) / 10.0}k"
    else -> n.toString()
}

fun timeAgo(epochSeconds: Long, now: Long = nowSeconds()): String {
    val d = (now - epochSeconds).coerceAtLeast(0)
    return when {
        d < 45 -> "just now"
        d < 3600 -> "${(d + 30) / 60} min ago"
        d < 86_400 -> "${d / 3600} h ago"
        else -> "${d / 86_400} d ago"
    }
}

/** "9:41" style countdown for the default deletion of a download. */
fun countdown(untilEpoch: Long, now: Long = nowSeconds()): String {
    val left = (untilEpoch - now).coerceAtLeast(0)
    val m = left / 60
    val s = left % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

fun stageLabel(stage: String) = when (stage) {
    "resolve" -> "Resolving"
    "download" -> "Downloading"
    "scan" -> "Scanning"
    "diff" -> "Comparing"
    "done" -> "Done"
    else -> stage.replaceFirstChar { it.uppercase() }
}
