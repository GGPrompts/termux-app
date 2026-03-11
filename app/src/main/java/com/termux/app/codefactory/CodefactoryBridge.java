package com.termux.app.codefactory;

import android.util.Log;
import android.view.Surface;

/**
 * JNI bridge to the codefactory Rust native library (libcodefactory.so).
 *
 * This class declares the native methods that are implemented in Rust via jni-rs.
 * The method names must match the JNI naming convention:
 *   Java_com_termux_app_codefactory_CodefactoryBridge_<methodName>
 *
 * SAFETY: All public methods check isAvailable() before calling native code.
 * The native library is loaded lazily on first access. If loading fails,
 * all methods return gracefully without calling JNI (preventing UnsatisfiedLinkError crashes).
 */
public class CodefactoryBridge {

    private static final String TAG = "CodefactoryBridge";

    /** Whether the native library loaded successfully. */
    private static boolean sLibraryLoaded = false;

    /** Whether loadLibrary() has been attempted (prevents retry loops). */
    private static boolean sLoadAttempted = false;

    /** Error message from a failed load attempt, or null. */
    private static String sLoadError = null;

    /**
     * Returns true if the native library is loaded and available.
     * This is the primary guard -- all callers should check this before
     * invoking any native method wrapper.
     */
    public static boolean isAvailable() {
        return sLibraryLoaded;
    }

    /**
     * Attempt to load the native library. Returns true if loaded successfully.
     * Safe to call multiple times -- only loads once.
     */
    public static synchronized boolean loadLibrary() {
        if (sLibraryLoaded) return true;
        if (sLoadAttempted) return false; // Already failed, don't retry
        sLoadAttempted = true;

        try {
            System.loadLibrary("codefactory");
            Log.i(TAG, "System.loadLibrary(\"codefactory\") succeeded");
        } catch (UnsatisfiedLinkError e) {
            sLoadError = "loadLibrary failed: " + e.getMessage();
            Log.e(TAG, sLoadError, e);
            return false;
        } catch (SecurityException e) {
            sLoadError = "loadLibrary security error: " + e.getMessage();
            Log.e(TAG, sLoadError, e);
            return false;
        } catch (Throwable t) {
            sLoadError = "loadLibrary unexpected error: " + t.getMessage();
            Log.e(TAG, sLoadError, t);
            return false;
        }

        // Library loaded, now call nativeInit() to set up logging
        try {
            int result = nativeInit();
            if (result != 0) {
                sLoadError = "nativeInit() returned error code: " + result;
                Log.e(TAG, sLoadError);
                // Library is loaded but init failed -- still mark as unavailable
                return false;
            }
            sLibraryLoaded = true;
            Log.i(TAG, "Native library loaded and initialized successfully");
            return true;
        } catch (Throwable t) {
            sLoadError = "nativeInit() threw: " + t.getMessage();
            Log.e(TAG, sLoadError, t);
            return false;
        }
    }

    /**
     * Returns true if the native library was loaded successfully.
     * @deprecated Use {@link #isAvailable()} instead for clearer semantics.
     */
    @Deprecated
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
    // Safe wrapper methods (check isAvailable before calling native)
    // -----------------------------------------------------------------------

    /** Safe wrapper: create wgpu surface from Android Surface. */
    public static void surfaceCreated(Surface surface) {
        if (!sLibraryLoaded) {
            Log.w(TAG, "surfaceCreated: native library not available");
            return;
        }
        try {
            nativeSurfaceCreated(surface);
        } catch (Throwable t) {
            Log.e(TAG, "surfaceCreated: JNI call failed", t);
        }
    }

    /** Safe wrapper: reconfigure wgpu surface dimensions. */
    public static void surfaceChanged(Surface surface, int width, int height) {
        if (!sLibraryLoaded) return;
        try {
            nativeSurfaceChanged(surface, width, height);
        } catch (Throwable t) {
            Log.e(TAG, "surfaceChanged: JNI call failed", t);
        }
    }

    /** Safe wrapper: release wgpu resources. */
    public static void surfaceDestroyed() {
        if (!sLibraryLoaded) return;
        try {
            nativeSurfaceDestroyed();
        } catch (Throwable t) {
            Log.e(TAG, "surfaceDestroyed: JNI call failed", t);
        }
    }

    /** Safe wrapper: forward touch event. */
    public static void touchEvent(int action, float x, float y) {
        if (!sLibraryLoaded) return;
        try {
            nativeTouchEvent(action, x, y);
        } catch (Throwable t) {
            Log.e(TAG, "touchEvent: JNI call failed", t);
        }
    }

    /** Safe wrapper: forward key event. */
    public static void keyEvent(int keyCode, int unicodeChar, int metaState) {
        if (!sLibraryLoaded) return;
        try {
            nativeKeyEvent(keyCode, unicodeChar, metaState);
        } catch (Throwable t) {
            Log.e(TAG, "keyEvent: JNI call failed", t);
        }
    }

    /** Safe wrapper: request render frame. */
    public static void renderFrame() {
        if (!sLibraryLoaded) return;
        try {
            nativeRenderFrame();
        } catch (Throwable t) {
            Log.e(TAG, "renderFrame: JNI call failed", t);
        }
    }

    /** Safe wrapper: spawn terminal pipeline. Returns true on success. */
    public static boolean spawnTerminal(String shell, int cols, int rows) {
        if (!sLibraryLoaded) {
            Log.w(TAG, "spawnTerminal: native library not available");
            return false;
        }
        try {
            int result = nativeSpawnTerminal(shell, cols, rows);
            return result == 0;
        } catch (Throwable t) {
            Log.e(TAG, "spawnTerminal: JNI call failed", t);
            return false;
        }
    }

    /** Safe wrapper: send text input. */
    public static void sendInput(String text) {
        if (!sLibraryLoaded) return;
        try {
            nativeSendInput(text);
        } catch (Throwable t) {
            Log.e(TAG, "sendInput: JNI call failed", t);
        }
    }

    /** Safe wrapper: check if pipeline is alive. */
    public static boolean isPipelineAlive() {
        if (!sLibraryLoaded) return false;
        try {
            return nativeIsAlive() == 1;
        } catch (Throwable t) {
            Log.e(TAG, "isPipelineAlive: JNI call failed", t);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Native methods implemented in Rust (src/lib.rs)
    // -----------------------------------------------------------------------
    // These are private -- callers should use the safe wrappers above.

    /** Initialize the native library (logging, etc). Called once after loading. Returns 0 on success. */
    private static native int nativeInit();

    /** Surface created -- pass the Surface to Rust to create wgpu Surface. */
    private static native void nativeSurfaceCreated(Surface surface);

    /** Surface dimensions changed -- reconfigure wgpu surface. */
    private static native void nativeSurfaceChanged(Surface surface, int width, int height);

    /** Surface destroyed -- release wgpu resources. */
    private static native void nativeSurfaceDestroyed();

    /** Forward touch events to Rust. */
    private static native void nativeTouchEvent(int action, float x, float y);

    /** Forward key events to Rust. */
    private static native void nativeKeyEvent(int keyCode, int unicodeChar, int metaState);

    /** Request a render frame. */
    private static native void nativeRenderFrame();

    /** Spawn terminal pipeline. Returns 0 on success, -1 on error, -2 if feature disabled. */
    private static native int nativeSpawnTerminal(String shell, int cols, int rows);

    /** Send raw text input to terminal. */
    private static native void nativeSendInput(String text);

    /** Check if terminal pipeline is alive. Returns 1 if alive, 0 otherwise. */
    private static native int nativeIsAlive();
}
