package com.termux.app.codefactory;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

/**
 * A SurfaceView that hosts the wgpu-based terminal renderer.
 *
 * Implements SurfaceHolder.Callback to track surface lifecycle and forward
 * it to the Rust renderer via JNI. Touch and key events are also forwarded.
 *
 * This view can coexist with Termux's TerminalView -- toggle visibility to
 * switch between the Java renderer and the GPU renderer during development.
 *
 * SAFETY: All JNI calls go through CodefactoryBridge's safe wrappers which
 * check isAvailable() and catch Throwable. If the native library crashes or
 * is unavailable, this view degrades gracefully. The activity can call
 * {@link #setFallbackListener(FallbackListener)} to be notified when the
 * GPU renderer fails and should switch back to the classic terminal.
 */
public class CodefactorySurfaceView extends SurfaceView implements SurfaceHolder.Callback {

    private static final String TAG = "CodefactorySurfaceView";

    /** Whether the native library is available. */
    private boolean mNativeAvailable = false;

    /** Whether the surface is currently valid (between surfaceCreated and surfaceDestroyed). */
    private boolean mSurfaceValid = false;

    /** Number of consecutive JNI failures. Used to trigger fallback. */
    private int mJniFailureCount = 0;

    /** Max consecutive failures before triggering fallback. */
    private static final int MAX_JNI_FAILURES = 3;

    /** Callback to notify the activity that the GPU renderer has failed. */
    public interface FallbackListener {
        /** Called on the main thread when the GPU renderer has failed too many times. */
        void onGpuRendererFailed(String reason);
    }

    private FallbackListener mFallbackListener;

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

    /**
     * Set a listener to be notified when the GPU renderer fails.
     * The activity should use this to switch back to the classic TerminalView.
     */
    public void setFallbackListener(FallbackListener listener) {
        mFallbackListener = listener;
    }

    // -----------------------------------------------------------------------
    // SurfaceHolder.Callback implementation
    // -----------------------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "surfaceCreated");
        mSurfaceValid = true;

        if (mNativeAvailable) {
            try {
                CodefactoryBridge.surfaceCreated(holder.getSurface());
                mJniFailureCount = 0;
            } catch (Throwable t) {
                handleJniFailure("surfaceCreated", t);
            }
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged: " + width + "x" + height + " format=" + format);

        if (mNativeAvailable) {
            try {
                CodefactoryBridge.surfaceChanged(holder.getSurface(), width, height);
                mJniFailureCount = 0;
            } catch (Throwable t) {
                handleJniFailure("surfaceChanged", t);
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "surfaceDestroyed");
        mSurfaceValid = false;

        if (mNativeAvailable) {
            try {
                CodefactoryBridge.surfaceDestroyed();
            } catch (Throwable t) {
                // Surface is being destroyed anyway, just log it
                Log.w(TAG, "surfaceDestroyed: JNI call failed (non-critical)", t);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Input event forwarding
    // -----------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mNativeAvailable) {
            try {
                CodefactoryBridge.touchEvent(
                    event.getAction(),
                    event.getX(),
                    event.getY()
                );
                return true;
            } catch (Throwable t) {
                handleJniFailure("onTouchEvent", t);
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mNativeAvailable) {
            try {
                CodefactoryBridge.keyEvent(
                    keyCode,
                    event.getUnicodeChar(),
                    event.getMetaState()
                );
                return true;
            } catch (Throwable t) {
                handleJniFailure("onKeyDown", t);
            }
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
            try {
                CodefactoryBridge.renderFrame();
            } catch (Throwable t) {
                handleJniFailure("requestRender", t);
            }
        }
    }

    /**
     * Returns true if the native library is available for use.
     */
    public boolean isNativeAvailable() {
        return mNativeAvailable;
    }

    // -----------------------------------------------------------------------
    // Failure handling
    // -----------------------------------------------------------------------

    /**
     * Handle a JNI failure. After MAX_JNI_FAILURES consecutive failures,
     * triggers the fallback listener to switch back to the classic terminal.
     */
    private void handleJniFailure(String method, Throwable t) {
        mJniFailureCount++;
        Log.e(TAG, method + ": JNI failure #" + mJniFailureCount, t);

        if (mJniFailureCount >= MAX_JNI_FAILURES) {
            Log.e(TAG, "Too many JNI failures (" + mJniFailureCount
                + "), disabling native renderer");
            mNativeAvailable = false;

            if (mFallbackListener != null) {
                // Post to main thread to avoid calling back during event dispatch
                post(() -> {
                    if (mFallbackListener != null) {
                        mFallbackListener.onGpuRendererFailed(
                            "GPU renderer failed after " + mJniFailureCount
                            + " errors. Last error in " + method + ": " + t.getMessage());
                    }
                });
            }
        }
    }
}
