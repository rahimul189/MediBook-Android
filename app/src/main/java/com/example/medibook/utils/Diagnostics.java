package com.example.medibook.utils;

/** Technical detail stays in Logcat, never normal screen copy. Logging cannot break an operation. */
public final class Diagnostics {
    private Diagnostics() { }
    public static void failure(String operation, Exception error) {
        try { android.util.Log.w("MediBook", operation, error); }
        catch (RuntimeException unavailableLogger) { /* Also supports local JVM policy tests. */ }
    }
}
