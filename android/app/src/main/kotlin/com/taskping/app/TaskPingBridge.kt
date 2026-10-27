package com.taskping.app

/**
 * JNI bridge to libtaskping.so (Rust).
 * The first five methods are Rivium's fixed lifecycle ABI (registered by rivium_jni::export!).
 * nativeRunDemoJob queues a quick Add job and blocks until the worker returns its result.
 * nativeSubmitTable queues a long Table job and returns its id; nativeCheck reads a job's state.
 * All blocking calls must run off the main thread.
 */
object TaskPingBridge {
  init {
    System.loadLibrary("taskping")
  }

  @JvmStatic external fun nativeStart(args: Array<String>): Int
  @JvmStatic external fun nativeStop(timeoutMillis: Long): Int
  @JvmStatic external fun nativeStatus(): Int
  @JvmStatic external fun nativeLastError(): String
  @JvmStatic external fun nativeVersion(): String
  @JvmStatic external fun nativeRunDemoJob(root: String, a: Long, b: Long): String
  @JvmStatic external fun nativeSubmitTable(root: String, base: Long, upto: Long): String
  @JvmStatic external fun nativeCheck(root: String, id: String): String
}
