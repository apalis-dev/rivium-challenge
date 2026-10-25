package com.horace.linkdigest

/** JNI surface exported by rivium_jni::export! in the Rust library. */
object Bridge {
    init { System.loadLibrary("link_digest") }

    @JvmStatic external fun nativeStart(args: Array<String>): Int
    @JvmStatic external fun nativeStop(timeoutMillis: Long): Int
    @JvmStatic external fun nativeStatus(): Int
    @JvmStatic external fun nativeLastError(): String
    @JvmStatic external fun nativeVersion(): String

    fun statusName(code: Int) = when (code) {
        0 -> "Idle"; 1 -> "Starting"; 2 -> "Running"; 3 -> "Stopping"
        4 -> "Restarting"; 5 -> "Failed"; else -> "Unknown($code)"
    }
}
