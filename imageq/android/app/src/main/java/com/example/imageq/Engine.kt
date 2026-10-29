package com.example.imageq

import android.content.Context

object Engine {
    // status 2 = running, as observed on the phone (not confirmed in Rivium's docs)
    @Synchronized
    fun ensureStarted(ctx: Context): Int {
        if (Bridge.nativeStatus() == 2) return 0
        return Bridge.nativeStart(arrayOf("--root", ctx.filesDir.absolutePath))
    }
}