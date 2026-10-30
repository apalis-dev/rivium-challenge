package io.github.samuel0kello.mchoro

/**
 * The five functions `rivium_jni::export!` registers in `rust/src/lib.rs`.
 *
 * Package and name must match `class = "io/github/samuel0kello/mchoro/RustBridge"` there.
 * JNI registers them as `static native` methods, which a Kotlin `object` only provides
 * when each one is marked `@JvmStatic`.
 */
internal object RustBridge {
    init {
        System.loadLibrary("mchoro") // loads libmchoro.so and runs its JNI_OnLoad
    }

    /** Blocks until the worker is ready (or failed). Returns 0 on success. */
    @JvmStatic external fun nativeStart(args: Array<String>): Int

    @JvmStatic external fun nativeStop(timeoutMillis: Long): Int

    /** Idle=0, Starting=1, Running=2, Stopping=3, Restarting=4, Failed=5. */
    @JvmStatic external fun nativeStatus(): Int

    @JvmStatic external fun nativeLastError(): String

    @JvmStatic external fun nativeVersion(): String
}
