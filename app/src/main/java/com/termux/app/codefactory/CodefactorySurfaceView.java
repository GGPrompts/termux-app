package com.termux.app.codefactory;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * A SurfaceView that hosts the wgpu-based terminal renderer.
 *
 * Implements SurfaceHolder.Callback to track surface lifecycle and forward
 * it to the Rust renderer via JNI. Touch and key events are also forwarded.
 *
 * This view can coexist with Termux's TerminalView -- toggle visibility to
 * switch between the Java renderer and the GPU renderer during development.
 */
public class CodefactorySurfaceView extends SurfaceView implements SurfaceHolder.Callback {

    private static final String TAG = "CodefactorySurfaceView";

    /** Whether the native library is available. */
    private boolean mNativeAvailable = false;

    /** Whether the surface is currently valid (between surfaceCreated and surfaceDestroyed). */
    private boolean mSurfaceValid = false;

    public CodefactorySurfaceView(Context context) {
        super(context);
        init();
    }

    public CodefactorySurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CodefactorySurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Register ourselves for surface lifecycle callbacks
        getHolder().addCallback(this);

        // Make focusable so we can receive key events
        setFocusable(true);
        setFocusableInTouchMode(true);

        // Try to load the native library
        mNativeAvailable = CodefactoryBridge.loadLibrary();
        if (mNativeAvailable) {
            Log.i(TAG, "Native library loaded successfully");
        } else {
            Log.w(TAG, "Native library not available: " + CodefactoryBridge.getLoadError());
        }
    }

    // -----------------------------------------------------------------------
    // SurfaceHolder.Callback implementation
    // -----------------------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "surfaceCreated");
        mSurfaceValid = true;

        if (mNativeAvailable) {
            CodefactoryBridge.nativeSurfaceCreated(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged: " + width + "x" + height + " format=" + format);

        if (mNativeAvailable) {
            CodefactoryBridge.nativeSurfaceChanged(holder.getSurface(), width, height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "surfaceDestroyed");
        mSurfaceValid = false;

        if (mNativeAvailable) {
            CodefactoryBridge.nativeSurfaceDestroyed();
        }
    }

    // -----------------------------------------------------------------------
    // Input event forwarding
    // -----------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mNativeAvailable) {
            CodefactoryBridge.nativeTouchEvent(
                event.getAction(),
                event.getX(),
                event.getY()
            );
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mNativeAvailable) {
            CodefactoryBridge.nativeKeyEvent(
                keyCode,
                event.getUnicodeChar(),
                event.getMetaState()
            );
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // For now, only forward key down events to Rust.
        // Key up can be added later if needed for key repeat handling.
        return super.onKeyUp(keyCode, event);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns true if the native renderer is available and the surface is valid.
     */
    public boolean isRendererReady() {
        return mNativeAvailable && mSurfaceValid;
    }

    /**
     * Request a render frame. Call this when the terminal content has changed.
     */
    public void requestRender() {
        if (mNativeAvailable && mSurfaceValid) {
            CodefactoryBridge.nativeRenderFrame();
        }
    }
}
