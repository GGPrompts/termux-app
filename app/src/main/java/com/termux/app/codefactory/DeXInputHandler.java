package com.termux.app.codefactory;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.termux.shared.logger.Logger;

/**
 * Handles keyboard and mouse input for Samsung DeX / desktop mode.
 *
 * When PocketForge runs in DeX, the user has a full physical keyboard and mouse.
 * This class provides:
 *
 * 1. **Keyboard dispatch**: Maps Android KeyEvent to terminal input, intercepting
 *    app-level shortcuts (Ctrl+T for new tab, Ctrl+W to close, etc.) before they
 *    reach the terminal.
 *
 * 2. **Mouse handling**: Converts mouse MotionEvents to terminal mouse reports
 *    (SGR/X10 format) so TUI apps (vim, tmux, htop) can use the mouse. Also
 *    handles scroll wheel as scrollback or mouse scroll reports.
 *
 * 3. **Selection**: Click-drag with the mouse selects terminal text for copying.
 *
 * The handler works with the GPU renderer (CodefactorySurfaceView) by converting
 * pixel coordinates to terminal cell coordinates using the known cell dimensions.
 *
 * IMPORTANT: This class only handles input for the GPU-rendered terminal. When the
 * classic TerminalView is active, Termux's existing input handling is used instead.
 */
public class DeXInputHandler {

    private static final String TAG = "DeXInputHandler";

    // -----------------------------------------------------------------------
    // Cell dimensions for coordinate mapping (must match Rust renderer)
    // -----------------------------------------------------------------------

    /** Width of a terminal cell in pixels. Must match renderer.rs GlyphAtlas metrics. */
    private float mCellWidth = 18.0f;

    /** Height of a terminal cell in pixels. Must match renderer.rs GlyphAtlas metrics. */
    private float mCellHeight = 36.0f;

    /** Current terminal grid width in columns. */
    private int mGridCols = 80;

    /** Current terminal grid height in rows. */
    private int mGridRows = 24;

    // -----------------------------------------------------------------------
    // Mouse state tracking
    // -----------------------------------------------------------------------

    /** Whether terminal mouse reporting is enabled (set by the terminal app via escape codes). */
    private boolean mMouseReportingEnabled = true;

    /** Whether we are currently in a mouse drag selection. */
    private boolean mInDragSelection = false;

    /** Start column of the current drag selection. */
    private int mSelectionStartCol = -1;

    /** Start row of the current drag selection. */
    private int mSelectionStartRow = -1;

    // -----------------------------------------------------------------------
    // Mouse button constants for SGR encoding
    // -----------------------------------------------------------------------

    private static final int MOUSE_BUTTON_LEFT = 0;
    private static final int MOUSE_BUTTON_MIDDLE = 1;
    private static final int MOUSE_BUTTON_RIGHT = 2;
    private static final int MOUSE_BUTTON_RELEASE = 3;
    private static final int MOUSE_WHEEL_UP = 64;
    private static final int MOUSE_WHEEL_DOWN = 65;

    /**
     * Callback interface for the activity to handle actions triggered by
     * keyboard shortcuts (new session, close session, paste, etc.).
     */
    public interface ShortcutHandler {
        /** Create a new terminal session. */
        void onNewSession();

        /** Close the current terminal session. */
        void onCloseSession();

        /** Paste from clipboard. */
        void onPaste();

        /** Copy selected text to clipboard. */
        void onCopy();

        /** Switch to next terminal session. */
        void onNextSession();

        /** Switch to previous terminal session. */
        void onPreviousSession();

        /** Switch to the terminal session at the given 0-based index. */
        void onSwitchToSession(int index);

        /** Toggle the soft keyboard visibility. */
        void onToggleKeyboard();

        /** Open the navigation drawer. */
        void onOpenDrawer();

        /** Toggle the GPU/classic renderer. */
        void onToggleRenderer();
    }

    private ShortcutHandler mShortcutHandler;

    public DeXInputHandler() {
    }

    /**
     * Set the shortcut handler for app-level keyboard shortcuts.
     */
    public void setShortcutHandler(ShortcutHandler handler) {
        mShortcutHandler = handler;
    }

    /**
     * Update the cell dimensions used for pixel-to-cell coordinate mapping.
     *
     * @param cellWidth  cell width in pixels
     * @param cellHeight cell height in pixels
     */
    public void setCellDimensions(float cellWidth, float cellHeight) {
        mCellWidth = cellWidth;
        mCellHeight = cellHeight;
    }

    /**
     * Update the grid dimensions.
     *
     * @param cols number of columns
     * @param rows number of rows
     */
    public void setGridDimensions(int cols, int rows) {
        mGridCols = cols;
        mGridRows = rows;
    }

    /**
     * Set whether terminal mouse reporting is enabled.
     * In the future, this should be driven by escape code tracking from the Rust side.
     */
    public void setMouseReportingEnabled(boolean enabled) {
        mMouseReportingEnabled = enabled;
    }

    // -----------------------------------------------------------------------
    // Keyboard handling
    // -----------------------------------------------------------------------

    /**
     * Process a key event from Activity.dispatchKeyEvent().
     *
     * This method is called BEFORE the key event reaches any view. It intercepts
     * app-level shortcuts and passes remaining keys to the GPU renderer via JNI.
     *
     * @param event the KeyEvent
     * @return true if the event was consumed, false to let it propagate
     */
    public boolean handleKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            // Only process key down events for shortcuts and terminal input.
            // Key up events are not needed for terminal input (the terminal
            // only cares about the character/escape sequence, not key release).
            return false;
        }

        // Check for app-level shortcuts first
        if (handleAppShortcut(event)) {
            return true;
        }

        // Forward to GPU renderer via JNI
        if (CodefactoryBridge.isAvailable()) {
            int keyCode = event.getKeyCode();
            int unicodeChar = event.getUnicodeChar();
            int metaState = event.getMetaState();

            CodefactoryBridge.keyEvent(keyCode, unicodeChar, metaState);
            return true;
        }

        return false;
    }

    /**
     * Check for and handle app-level keyboard shortcuts.
     *
     * These shortcuts are intercepted before reaching the terminal:
     * - Ctrl+Shift+T: New session
     * - Ctrl+Shift+W: Close session
     * - Ctrl+Shift+V: Paste
     * - Ctrl+Shift+C: Copy
     * - Ctrl+Shift+N/P or Ctrl+Tab/Ctrl+Shift+Tab: Next/prev session
     * - Ctrl+Shift+1-9: Switch to session by index
     * - Ctrl+Shift+K: Toggle keyboard
     * - Ctrl+Shift+D: Open drawer
     * - Ctrl+Shift+G: Toggle GPU renderer
     *
     * We use Ctrl+Shift prefix to avoid conflicts with common terminal shortcuts
     * (Ctrl+C, Ctrl+V in some terminals, etc.).
     *
     * @param event the KeyEvent to check
     * @return true if a shortcut was handled
     */
    private boolean handleAppShortcut(KeyEvent event) {
        if (mShortcutHandler == null) return false;

        boolean ctrl = event.isCtrlPressed();
        boolean shift = event.isShiftPressed();
        int keyCode = event.getKeyCode();

        // Ctrl+Shift shortcuts
        if (ctrl && shift) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_T:
                    mShortcutHandler.onNewSession();
                    return true;
                case KeyEvent.KEYCODE_W:
                    mShortcutHandler.onCloseSession();
                    return true;
                case KeyEvent.KEYCODE_V:
                    mShortcutHandler.onPaste();
                    return true;
                case KeyEvent.KEYCODE_C:
                    mShortcutHandler.onCopy();
                    return true;
                case KeyEvent.KEYCODE_N:
                    mShortcutHandler.onNextSession();
                    return true;
                case KeyEvent.KEYCODE_P:
                    mShortcutHandler.onPreviousSession();
                    return true;
                case KeyEvent.KEYCODE_K:
                    mShortcutHandler.onToggleKeyboard();
                    return true;
                case KeyEvent.KEYCODE_D:
                    mShortcutHandler.onOpenDrawer();
                    return true;
                case KeyEvent.KEYCODE_G:
                    mShortcutHandler.onToggleRenderer();
                    return true;
            }

            // Ctrl+Shift+1 through Ctrl+Shift+9 for session switching
            if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9) {
                int index = keyCode - KeyEvent.KEYCODE_1;
                mShortcutHandler.onSwitchToSession(index);
                return true;
            }
        }

        // Ctrl+Tab / Ctrl+Shift+Tab for session cycling
        if (ctrl && keyCode == KeyEvent.KEYCODE_TAB) {
            if (shift) {
                mShortcutHandler.onPreviousSession();
            } else {
                mShortcutHandler.onNextSession();
            }
            return true;
        }

        return false;
    }

    // -----------------------------------------------------------------------
    // Mouse handling
    // -----------------------------------------------------------------------

    /**
     * Handle a generic motion event (mouse move, scroll wheel, hover).
     * Called from Activity.onGenericMotionEvent().
     *
     * @param event the MotionEvent
     * @return true if the event was consumed
     */
    public boolean handleGenericMotionEvent(MotionEvent event) {
        if (!isMouseEvent(event)) {
            return false;
        }

        int action = event.getActionMasked();

        // Handle scroll wheel
        if (action == MotionEvent.ACTION_SCROLL) {
            float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (scrollY != 0) {
                return handleScrollWheel(event, scrollY);
            }
        }

        // Handle mouse hover (for hover-aware TUI apps)
        if (action == MotionEvent.ACTION_HOVER_MOVE) {
            if (mMouseReportingEnabled) {
                int col = pixelToCol(event.getX());
                int row = pixelToRow(event.getY());
                // SGR mouse motion report (button=35 for no button, motion)
                String report = String.format("\033[<35;%d;%dM", col + 1, row + 1);
                CodefactoryBridge.sendInput(report);
                return true;
            }
        }

        return false;
    }

    /**
     * Handle a mouse button event from the CodefactorySurfaceView's onTouchEvent.
     * This is called instead of the normal touch handler when a mouse is detected.
     *
     * @param event the MotionEvent (from a mouse, not touch)
     * @return true if the event was consumed
     */
    public boolean handleMouseButtonEvent(MotionEvent event) {
        if (!isMouseEvent(event)) {
            return false;
        }

        int action = event.getActionMasked();
        int col = pixelToCol(event.getX());
        int row = pixelToRow(event.getY());
        int button = motionEventButtonToTerminal(event);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_BUTTON_PRESS:
                if (mMouseReportingEnabled) {
                    sendSgrMouseReport(button, col, row, true);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (mMouseReportingEnabled) {
                    sendSgrMouseReport(button, col, row, false);
                }
                mInDragSelection = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (mMouseReportingEnabled) {
                    // Motion event: button + 32
                    sendSgrMouseReport(button + 32, col, row, true);
                }
                return true;

            default:
                return false;
        }
    }

    /**
     * Handle scroll wheel events. Sends terminal scroll reports if mouse reporting
     * is enabled, otherwise could be used for scrollback navigation.
     *
     * @param event   the MotionEvent
     * @param scrollY positive for scroll up, negative for scroll down
     * @return true if consumed
     */
    private boolean handleScrollWheel(MotionEvent event, float scrollY) {
        int col = pixelToCol(event.getX());
        int row = pixelToRow(event.getY());

        // Number of lines to scroll (each detent is typically 1.0 or 3.0)
        int lines = Math.max(1, Math.abs(Math.round(scrollY)));

        if (mMouseReportingEnabled) {
            // Send SGR mouse scroll reports
            int button = scrollY > 0 ? MOUSE_WHEEL_UP : MOUSE_WHEEL_DOWN;
            for (int i = 0; i < lines; i++) {
                sendSgrMouseReport(button, col, row, true);
            }
        } else {
            // When mouse reporting is off, send arrow keys for scroll
            // Up arrow for scroll up, down arrow for scroll down
            String arrowSeq = scrollY > 0 ? "\033[A" : "\033[B";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines; i++) {
                sb.append(arrowSeq);
            }
            CodefactoryBridge.sendInput(sb.toString());
        }

        return true;
    }

    /**
     * Send an SGR-format mouse report to the terminal.
     *
     * SGR format: ESC [ < button ; col ; row M (press) or m (release)
     * Coordinates are 1-based.
     *
     * @param button the button code (0=left, 1=middle, 2=right, 64=wheelup, 65=wheeldown)
     * @param col    0-based column
     * @param row    0-based row
     * @param press  true for press/motion, false for release
     */
    private void sendSgrMouseReport(int button, int col, int row, boolean press) {
        if (!CodefactoryBridge.isAvailable()) return;

        // Clamp to grid bounds
        col = Math.max(0, Math.min(col, mGridCols - 1));
        row = Math.max(0, Math.min(row, mGridRows - 1));

        // SGR format uses 1-based coordinates
        String report = String.format("\033[<%d;%d;%d%c",
            button, col + 1, row + 1, press ? 'M' : 'm');

        CodefactoryBridge.sendInput(report);
    }

    // -----------------------------------------------------------------------
    // Coordinate conversion
    // -----------------------------------------------------------------------

    /**
     * Convert a pixel X coordinate to a terminal column (0-based).
     */
    private int pixelToCol(float x) {
        int col = (int) (x / mCellWidth);
        return Math.max(0, Math.min(col, mGridCols - 1));
    }

    /**
     * Convert a pixel Y coordinate to a terminal row (0-based).
     */
    private int pixelToRow(float y) {
        int row = (int) (y / mCellHeight);
        return Math.max(0, Math.min(row, mGridRows - 1));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Check if a MotionEvent is from a mouse (not a touchscreen finger).
     */
    private static boolean isMouseEvent(MotionEvent event) {
        return event.isFromSource(InputDevice.SOURCE_MOUSE);
    }

    /**
     * Check if a MotionEvent is from a mouse (static version for use by other classes).
     */
    public static boolean isMouseInput(MotionEvent event) {
        return event != null && event.isFromSource(InputDevice.SOURCE_MOUSE);
    }

    /**
     * Map MotionEvent button state to terminal button code.
     */
    private int motionEventButtonToTerminal(MotionEvent event) {
        int buttonState = event.getButtonState();
        if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0) {
            return MOUSE_BUTTON_LEFT;
        } else if ((buttonState & MotionEvent.BUTTON_TERTIARY) != 0) {
            return MOUSE_BUTTON_MIDDLE;
        } else if ((buttonState & MotionEvent.BUTTON_SECONDARY) != 0) {
            return MOUSE_BUTTON_RIGHT;
        }
        return MOUSE_BUTTON_LEFT; // Default to left button
    }

    /**
     * Check if a key event is from a hardware/physical keyboard (not the on-screen keyboard).
     * Useful for deciding whether to forward to the GPU renderer directly.
     */
    public static boolean isHardwareKeyEvent(KeyEvent event) {
        if (event == null) return false;
        InputDevice device = event.getDevice();
        if (device == null) return false;
        // Hardware keyboards have KEYBOARD_TYPE_ALPHABETIC
        return device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }
}
