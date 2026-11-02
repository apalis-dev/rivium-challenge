package com.pocketshrink.app

object Bridge {
    init {
        try {
            System.loadLibrary("pocket_backend")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    @JvmStatic external fun nativeStart(args: Array<String>): Int
    @JvmStatic external fun nativeStop(timeoutMillis: Long): Int
    @JvmStatic external fun nativeStatus(): Int
    @JvmStatic external fun nativeLastError(): String?
    @JvmStatic external fun nativeVersion(): String?

    // Custom PocketShrink queue helpers
    @JvmStatic external fun nativeEnqueue(rootDir: String, inputPath: String, maxWidth: Int): String
    @JvmStatic external fun nativeGetCompletedJobs(rootDir: String): String
    @JvmStatic external fun nativeDiagnoseHost(rootDir: String): String
}
