package com.example.docq;

public final class Bridge {
    static { System.loadLibrary("docq"); }

    public static native int nativeStart(String[] args);
    public static native int nativeStop(long timeoutMillis);
    public static native int nativeStatus();
    public static native String nativeLastError();
    public static native String nativeVersion();
}