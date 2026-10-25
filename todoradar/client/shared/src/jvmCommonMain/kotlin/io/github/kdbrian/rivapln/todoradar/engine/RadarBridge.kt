package io.github.kdbrian.rivapln.todoradar.engine

/**
 * Native entry points registered by `rivium_jni::export!` in rust/radar-jni. The class name is
 * part of the contract: `io/github/kdbrian/rivapln/todoradar/engine/RadarBridge`, static methods only.
 */
object RadarBridge {
    /** Start a Rivium generation with CLI-style args (`--root`, `--set key=value`); 0 on success. */
    @JvmStatic external fun nativeStart(args: Array<String>): Int

    /** Graceful stop, waiting up to [timeoutMillis] for services to drain; 0 on success. */
    @JvmStatic external fun nativeStop(timeoutMillis: Long): Int

    @JvmStatic external fun nativeStatus(): Int

    @JvmStatic external fun nativeLastError(): String

    @JvmStatic external fun nativeVersion(): String
}
