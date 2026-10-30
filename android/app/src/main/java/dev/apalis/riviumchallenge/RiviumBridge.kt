package dev.apalis.riviumchallenge

import android.util.Log

/** Raw JNI surface of libpocket_backend.so. Use [ReaderHost] instead of calling this directly. */
object RiviumBridge {
    init {
        try {
            System.loadLibrary("pocket_backend")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("RiviumBridge", "Failed to load libpocket_backend.so", e)
        }
    }

    // Rivium embedded host lifecycle (registered by rivium-jni in JNI_OnLoad)
    @JvmStatic external fun nativeStart(args: Array<String>): Int
    @JvmStatic external fun nativeStop(timeoutMillis: Long): Int
    @JvmStatic external fun nativeStatus(): Int
    @JvmStatic external fun nativeLastError(): String?
    @JvmStatic external fun nativeVersion(): String?

    // Reading list (src/jni_bridge.rs). Strings are JSON.
    @JvmStatic external fun nativeSaveShared(text: String): String
    @JvmStatic external fun nativeListArticles(limit: Int): String
    @JvmStatic external fun nativeOpenArticle(id: String): String
    @JvmStatic external fun nativeRetryArticle(id: String): Boolean
    @JvmStatic external fun nativeRetryFailed(): Int
    @JvmStatic external fun nativeDeleteArticle(id: String): Boolean
    @JvmStatic external fun nativeTakeFinished(): String
    @JvmStatic external fun nativeCounts(): String
}
