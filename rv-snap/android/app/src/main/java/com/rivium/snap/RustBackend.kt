package com.rivium.snap

import android.util.Log

object RustBackend {
    private const val TAG = "RustBackend"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("rivium_snap")
            isLoaded = true
            Log.i(TAG, "librivium_snap.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load librivium_snap.so", e)
        }
    }

    fun isNativeLoaded(): Boolean = isLoaded

    // These match the symbols exported in the compiled .so
    external fun startEngine(dbPath: String)
    external fun enqueueBulkJobs(dbPath: String, jsonUris: String)
    external fun getQueueStats(dbPath: String): String
    external fun getCompletedWorks(dbPath: String): String
    external fun exportZip(dbPath: String, outputPath: String): Boolean
    external fun exportZipSelected(dbPath: String, outputPath: String, jsonIds: String): Boolean
}
