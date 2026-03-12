package com.termux.app.codefactory;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

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

    /** Whether a terminal has been spawned via nativeSpawnTerminal. */
    private boolean mTerminalSpawned = false;

    /** Whether the Choreographer render loop is running. */
    private boolean mRenderLoopRunning = false;

    /** Number of consecutive JNI failures. Used to trigger fallback. */
    private int mJniFailureCount = 0;

    /** Max consecutive failures before triggering fallback. */
    private static final int MAX_JNI_FAILURES = 3;

    /** Choreographer callback for the render loop. */
    private final Choreographer.FrameCallback mRenderCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mRenderLoopRunning || !mSurfaceValid || !mNativeAvailable) {
                return;
            }
            try {
                CodefactoryBridge.renderFrame();
            } catch (Throwable t) {
                handleJniFailure("renderLoop", t);
            }
            // Schedule the next frame
            if (mRenderLoopRunning) {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

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

                // Spawn a terminal if we haven't already and the view is visible
                if (!mTerminalSpawned && getVisibility() == View.VISIBLE) {
                    spawnTerminalForSurface(width, height);
                }

                // Start the render loop if not already running
                startRenderLoop();
            } catch (Throwable t) {
                handleJniFailure("surfaceChanged", t);
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "surfaceDestroyed");
        stopRenderLoop();
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

                // Show soft keyboard on tap
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    requestFocus();
                    showSoftKeyboard();
                }

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

    /**
     * Show the soft keyboard for this view.
     */
    private void showSoftKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
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
            stopRenderLoop();

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

    // -----------------------------------------------------------------------
    // Terminal lifecycle
    // -----------------------------------------------------------------------

    /**
     * Spawn a terminal session for the current surface dimensions.
     * Computes grid cols/rows from pixel size and a hardcoded cell size
     * that matches the Rust renderer's font metrics (~18x36 at 32px font).
     */
    private void spawnTerminalForSurface(int width, int height) {
        // If a pipeline is already alive (e.g., surface recreated after rotation),
        // don't spawn a new shell -- just keep using the existing one.
        if (CodefactoryBridge.isPipelineAlive()) {
            Log.i(TAG, "spawnTerminalForSurface: pipeline already alive, skipping spawn");
            mTerminalSpawned = true;
            return;
        }

        // Cell size must match renderer.rs GlyphAtlas font_size=32.0
        // fontdue at 32px gives approximately 18px wide, 36px tall cells
        // (the exact values come from the Rust side, but we need a reasonable
        // estimate here to compute initial grid dimensions)
        float cellWidth = 18.0f;
        float cellHeight = 36.0f;

        int cols = Math.max(1, (int) (width / cellWidth));
        int rows = Math.max(1, (int) (height / cellHeight));

        Log.i(TAG, "spawnTerminalForSurface: surface=" + width + "x" + height
            + " -> grid=" + cols + "x" + rows);

        // null shell = let Rust auto-detect (SHELL env var or Termux bash)
        boolean ok = CodefactoryBridge.spawnTerminal(null, cols, rows);
        if (ok) {
            mTerminalSpawned = true;
            Log.i(TAG, "Terminal spawned successfully");
        } else {
            Log.e(TAG, "Failed to spawn terminal");
        }
    }

    /**
     * Start the Choreographer-driven render loop.
     * Each vsync calls nativeRenderFrame which checks the pipeline dirty flag,
     * converts the grid if needed, and renders via wgpu. Frames where nothing
     * changed are nearly free (dirty check is an atomic load).
     */
    private void startRenderLoop() {
        if (mRenderLoopRunning) return;
        mRenderLoopRunning = true;
        Choreographer.getInstance().postFrameCallback(mRenderCallback);
        Log.i(TAG, "Render loop started");
    }

    /**
     * Stop the Choreographer-driven render loop.
     */
    private void stopRenderLoop() {
        if (!mRenderLoopRunning) return;
        mRenderLoopRunning = false;
        Choreographer.getInstance().removeFrameCallback(mRenderCallback);
        Log.i(TAG, "Render loop stopped");
    }

    /**
     * Ensure the terminal is spawned when the view becomes visible.
     * Called from onVisibilityChanged or when the Activity toggles the GPU renderer.
     */
    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);

        if (changedView == this) {
            if (visibility == View.VISIBLE) {
                Log.i(TAG, "View became visible");
                if (mNativeAvailable && mSurfaceValid) {
                    if (!mTerminalSpawned) {
                        spawnTerminalForSurface(getWidth(), getHeight());
                    }
                    startRenderLoop();
                }
            } else {
                Log.i(TAG, "View became hidden");
                stopRenderLoop();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopRenderLoop();
    }
}
