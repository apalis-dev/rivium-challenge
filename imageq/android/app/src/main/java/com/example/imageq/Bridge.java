package com.example.imageq;

public final class Bridge {
    static { System.loadLibrary("imageq"); }

    public static native int nativeStart(String[] args);
    public static native int nativeStop(long timeoutMillis);
    public static native int nativeStatus();
    public static native String nativeLastError();
    public static native String nativeVersion();
}
