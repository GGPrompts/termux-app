package com.termux.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

/**
 * Stub TerminalView that provides the API surface needed by TermuxActivity and related classes.
 * The actual terminal rendering is handled by the GPU renderer (CodefactorySurfaceView + wgpu).
 * This stub maintains session state and handles keyboard input routing but does not render
 * terminal content.
 */
public final class TerminalView extends View {

    /** Log terminal view key and IME events. */
    private static boolean TERMINAL_VIEW_KEY_LOGGING_ENABLED = false;

    /** The currently displayed terminal session, whose emulator is {@link #mEmulator}. */
    public TerminalSession mTermSession;
    /** Our terminal emulator whose session is {@link #mTermSession}. */
    public TerminalEmulator mEmulator;

    public TerminalViewClient mClient;

    public static final int TERMINAL_CURSOR_BLINK_RATE_MIN = 100;
    public static final int TERMINAL_CURSOR_BLINK_RATE_MAX = 2000;

    /** The {@link KeyEvent} is generated from a virtual keyboard. */
    public final static int KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = KeyCharacterMap.VIRTUAL_KEYBOARD;

    /** The {@link KeyEvent} is generated from a non-physical device. */
    public final static int KEY_EVENT_SOURCE_SOFT_KEYBOARD = 0;

    private String mStoredSelectedText;

    private boolean mAutoFillEnabled = false;

    private static final String LOG_TAG = "TerminalView";

    public TerminalView(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    /**
     * Set the terminal view client for callbacks.
     */
    public void setTerminalViewClient(TerminalViewClient client) {
        mClient = client;
    }

    /**
     * Attach a terminal session to this view. Returns true if the session changed.
     */
    public boolean attachSession(TerminalSession session) {
        if (session == mTermSession) return false;
        mTermSession = session;
        if (session != null) {
            mEmulator = session.getEmulator();
            // Trigger initial size update if emulator exists
            if (mEmulator != null && mClient != null) {
                mClient.onEmulatorSet();
            }
        } else {
            mEmulator = null;
        }
        // Notify session of initial size
        updateSize();
        return true;
    }

    /** Get the currently attached session. */
    @Nullable
    public TerminalSession getCurrentSession() {
        return mTermSession;
    }

    /** Called when the terminal screen content has changed. */
    public void onScreenUpdated() {
        // Stub: GPU renderer handles display updates
        if (mTermSession != null) {
            mEmulator = mTermSession.getEmulator();
        }
    }

    /** Set the text/font size. */
    public void setTextSize(int size) {
        // Stub: rendering handled by GPU renderer
        // Trigger size recalculation if needed
        updateSize();
    }

    /** Set the typeface for rendering. */
    public void setTypeface(Typeface typeface) {
        // Stub: rendering handled by GPU renderer
    }

    /** Enable or disable terminal view key logging. */
    public void setIsTerminalViewKeyLoggingEnabled(boolean enabled) {
        TERMINAL_VIEW_KEY_LOGGING_ENABLED = enabled;
    }

    /** Set the cursor blinker rate. Returns true if successful. */
    public boolean setTerminalCursorBlinkerRate(int rate) {
        if (rate != 0 && (rate < TERMINAL_CURSOR_BLINK_RATE_MIN || rate > TERMINAL_CURSOR_BLINK_RATE_MAX))
            return false;
        return true;
    }

    /** Set the cursor blinker state. */
    public void setTerminalCursorBlinkerState(boolean start, boolean startOnlyIfCursorEnabled) {
        // Stub: cursor blinking handled by GPU renderer
    }

    /** Get column and row for a touch event. */
    public int[] getColumnAndRow(MotionEvent event, boolean clamp) {
        // Stub: return 0,0 as we don't have renderer metrics
        return new int[]{0, 0};
    }

    /** Get stored selected text. */
    @Nullable
    public String getStoredSelectedText() {
        return mStoredSelectedText;
    }

    /** Check if autofill is enabled. */
    public boolean isAutoFillEnabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        return mAutoFillEnabled;
    }

    /** Request autofill for username field. */
    public void requestAutoFillUsername() {
        // Stub: autofill not supported in GPU renderer
    }

    /** Request autofill for password field. */
    public void requestAutoFillPassword() {
        // Stub: autofill not supported in GPU renderer
    }

    public void onContextMenuClosed(Menu menu) {
        // Stub: no text selection mode to manage
    }

    /**
     * Input a Unicode code point to the terminal.
     *
     * @param eventSource One of {@link #KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD} or {@link #KEY_EVENT_SOURCE_SOFT_KEYBOARD}.
     * @param codePoint   The code point to input.
     * @param ctrlDown    Whether ctrl is pressed.
     * @param altDown     Whether alt is pressed.
     */
    public void inputCodePoint(int eventSource, int codePoint, boolean ctrlDown, boolean altDown) {
        if (mTermSession == null || !mTermSession.isRunning()) return;

        boolean controlDown = ctrlDown || (mClient != null && mClient.readControlKey());
        boolean altKeyDown = altDown || (mClient != null && mClient.readAltKey());
        boolean shiftDown = mClient != null && mClient.readShiftKey();
        boolean fnDown = mClient != null && mClient.readFnKey();

        if (mClient != null && mClient.onCodePoint(codePoint, controlDown, mTermSession)) return;

        if (controlDown) {
            if (codePoint >= 'a' && codePoint <= 'z')
                codePoint = codePoint - 'a' + 1;
            else if (codePoint >= 'A' && codePoint <= 'Z')
                codePoint = codePoint - 'A' + 1;
            else if (codePoint == ' ' || codePoint == '2')
                codePoint = 0;
            else if (codePoint == '[' || codePoint == '3')
                codePoint = 27; // ^[ = ESC
            else if (codePoint == '\\' || codePoint == '4')
                codePoint = 28;
            else if (codePoint == ']' || codePoint == '5')
                codePoint = 29;
            else if (codePoint == '^' || codePoint == '6')
                codePoint = 30; // control-^
            else if (codePoint == '_' || codePoint == '7' || codePoint == '/')
                codePoint = 31;
            else if (codePoint == '8')
                codePoint = 127; // DEL
        }

        if (codePoint > -1) {
            mTermSession.writeCodePoint(altKeyDown, codePoint);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mTermSession == null) return false;
        if (mClient != null && mClient.onKeyDown(keyCode, event, mTermSession)) return true;

        if (event.isSystem()) return super.onKeyDown(keyCode, event);

        // Route key to terminal
        int metaState = event.getMetaState();
        boolean controlDown = event.isCtrlPressed() || (mClient != null && mClient.readControlKey());
        boolean altDown = event.isAltPressed() || (mClient != null && mClient.readAltKey());
        boolean shiftDown = event.isShiftPressed() || (mClient != null && mClient.readShiftKey());
        boolean fnDown = mClient != null && mClient.readFnKey();

        int keyMod = 0;
        if (controlDown) keyMod |= KeyHandler.KEYMOD_CTRL;
        if (altDown) keyMod |= KeyHandler.KEYMOD_ALT;
        if (shiftDown) keyMod |= KeyHandler.KEYMOD_SHIFT;
        if (fnDown) keyMod |= KeyHandler.KEYMOD_NUM_LOCK;

        if (mEmulator != null) {
            String code = KeyHandler.getCode(keyCode, keyMod, mEmulator.isCursorKeysApplicationMode(), mEmulator.isKeypadApplicationMode());
            if (code != null) {
                mTermSession.write(code);
                return true;
            }
        }

        int unicodeChar = event.getUnicodeChar(metaState);
        if ((unicodeChar & KeyCharacterMap.COMBINING_ACCENT) != 0) return true;
        if (unicodeChar != 0) {
            inputCodePoint(event.getDeviceId() == 0 ? KEY_EVENT_SOURCE_SOFT_KEYBOARD : event.getDeviceId(),
                unicodeChar, controlDown, altDown);
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mClient != null && mClient.onKeyUp(keyCode, event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Stub: no rendering, GPU renderer handles display
    }

    /** Update the terminal size based on this view's dimensions. */
    private void updateSize() {
        if (mTermSession == null) return;
        // Use reasonable defaults for cell size
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        // Approximate: 10px cell width, 20px cell height at default size
        int cellWidth = 10;
        int cellHeight = 20;
        int columns = Math.max(4, width / cellWidth);
        int rows = Math.max(4, height / cellHeight);
        mTermSession.updateSize(columns, rows, cellWidth, cellHeight);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateSize();
    }

    /** Toggle auto-scroll disabled state on the emulator. */
    public void toggleAutoScrollDisabled() {
        if (mEmulator != null) {
            mEmulator.toggleAutoScrollDisabled();
        }
    }
}
