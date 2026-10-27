package com.example.linkqueue;

/** JNI lifecycle and queue bridge for the embedded Rivium host. */
public final class NativeBridge {
    static {
        System.loadLibrary("rivium_challenge");
    }

    private NativeBridge() {}

    public static native int nativeStart(String[] args);
    public static native int nativeStop(long timeoutMillis);
    public static native int nativeStatus();
    public static native String nativeLastError();
    public static native String nativeVersion();

    public static native String nativeEnqueue(
            String jobId,
            String url,
            String dataDirectory
    );

    public static native String nativeGetResult(
            String jobId,
            String dataDirectory
    );

}
