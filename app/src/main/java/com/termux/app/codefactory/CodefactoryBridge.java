package com.termux.app.codefactory;

import android.view.Surface;

/**
 * JNI bridge to the codefactory Rust native library (libcodefactory.so).
 *
 * This class declares the native methods that are implemented in Rust via jni-rs.
 * The method names must match the JNI naming convention:
 *   Java_com_termux_app_codefactory_CodefactoryBridge_<methodName>
 */
public class CodefactoryBridge {

    private static boolean sLibraryLoaded = false;
    private static String sLoadError = null;

    /**
     * Attempt to load the native library. Returns true if loaded successfully.
     * Safe to call multiple times -- only loads once.
     */
    public static synchronized boolean loadLibrary() {
        if (sLibraryLoaded) return true;
        if (sLoadError != null) return false; // Already failed, don't retry

        try {
            System.loadLibrary("codefactory");
            sLibraryLoaded = true;
            nativeInit();
            return true;
        } catch (UnsatisfiedLinkError e) {
            sLoadError = e.getMessage();
            android.util.Log.e("CodefactoryBridge",
                "Failed to load libcodefactory.so: " + sLoadError);
            return false;
        }
    }

    /**
     * Returns true if the native library was loaded successfully.
     */
    public static boolean isLoaded() {
        return sLibraryLoaded;
    }

    /**
     * Returns the error message if library loading failed, or null if not attempted or succeeded.
     */
    public static String getLoadError() {
        return sLoadError;
    }

    // -----------------------------------------------------------------------
    // Native methods implemented in Rust (src/lib.rs)
    // -----------------------------------------------------------------------

    /** Initialize the native library (logging, etc). Called once after loading. */
    static native void nativeInit();

    /** Surface created -- pass the Surface to Rust to create wgpu Surface. */
    public static native void nativeSurfaceCreated(Surface surface);

    /** Surface dimensions changed -- reconfigure wgpu surface. */
    public static native void nativeSurfaceChanged(Surface surface, int width, int height);

    /** Surface destroyed -- release wgpu resources. */
    public static native void nativeSurfaceDestroyed();

    /** Forward touch events to Rust. */
    public static native void nativeTouchEvent(int action, float x, float y);

    /** Forward key events to Rust. */
    public static native void nativeKeyEvent(int keyCode, int unicodeChar, int metaState);

    /** Request a render frame. */
    public static native void nativeRenderFrame();
}
