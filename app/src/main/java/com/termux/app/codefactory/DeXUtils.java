package com.termux.app.codefactory;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

/**
 * Utility class for Samsung DeX detection and desktop-mode adaptations.
 *
 * DeX turns a Samsung phone into a desktop workstation with resizable windows,
 * full keyboard/mouse support, and large external displays. This class detects
 * DeX mode and provides configuration hints for the terminal renderer:
 *
 * - Larger default font size for high-DPI desktop monitors
 * - Grid dimensions optimized for wide screens (200+ columns)
 * - Whether to hide the soft keyboard toolbar (hardware keyboard attached)
 *
 * Detection uses the standard {@code Configuration.UI_MODE_TYPE_DESK} flag,
 * which Samsung sets when DeX is active. This works on all DeX-capable
 * devices without requiring the Samsung DeX SDK.
 */
public final class DeXUtils {

    private static final String TAG = "DeXUtils";

    /** Cached DeX mode state to avoid repeated configuration checks. */
    private static boolean sCachedIsDeXMode = false;

    /** Whether the cache has been initialized. */
    private static boolean sCacheInitialized = false;

    private DeXUtils() {
        // Static utility class
    }

    /**
     * Check if the device is currently in Samsung DeX mode (or any desktop UI mode).
     *
     * This checks {@code Configuration.UI_MODE_TYPE_DESK} which is set by Samsung
     * when DeX is active. It also catches generic Android desktop mode (e.g., on
     * ChromeOS or Android automotive in desk mode).
     *
     * @param context any valid Context
     * @return true if in DeX/desktop mode
     */
    public static boolean isDeXMode(Context context) {
        if (context == null) return false;
        try {
            Configuration config = context.getResources().getConfiguration();
            int uiModeType = config.uiMode & Configuration.UI_MODE_TYPE_MASK;
            boolean isDeskMode = (uiModeType == Configuration.UI_MODE_TYPE_DESK);

            // Also check Samsung-specific system feature as a fallback
            if (!isDeskMode) {
                isDeskMode = hasSamsungDeXFeature(context);
            }

            sCachedIsDeXMode = isDeskMode;
            sCacheInitialized = true;

            return isDeskMode;
        } catch (Exception e) {
            Log.w(TAG, "isDeXMode: failed to check configuration", e);
            return false;
        }
    }

    /**
     * Return the cached DeX mode state without re-querying configuration.
     * Falls back to a fresh check if cache is not initialized.
     */
    public static boolean isDeXModeCached(Context context) {
        if (!sCacheInitialized && context != null) {
            return isDeXMode(context);
        }
        return sCachedIsDeXMode;
    }

    /**
     * Update the cached DeX mode state from a new Configuration.
     * Call this from {@code onConfigurationChanged} to keep the cache fresh.
     *
     * @param newConfig the new Configuration from onConfigurationChanged
     * @return true if in DeX/desktop mode
     */
    public static boolean updateFromConfiguration(Configuration newConfig) {
        if (newConfig == null) return sCachedIsDeXMode;
        int uiModeType = newConfig.uiMode & Configuration.UI_MODE_TYPE_MASK;
        sCachedIsDeXMode = (uiModeType == Configuration.UI_MODE_TYPE_DESK);
        sCacheInitialized = true;
        return sCachedIsDeXMode;
    }

    /**
     * Check if Samsung DeX feature is available via the system features list.
     * This detects DeX even before it is activated (i.e., the device supports it).
     */
    private static boolean hasSamsungDeXFeature(Context context) {
        try {
            // Samsung sets this feature when DeX is actively running
            return context.getPackageManager()
                .hasSystemFeature("com.sec.feature.desktopmode");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if a hardware keyboard is currently connected.
     * In DeX mode, a physical keyboard is almost always present.
     *
     * @param config the current Configuration
     * @return true if a hardware keyboard is attached and visible
     */
    public static boolean isHardwareKeyboardConnected(Configuration config) {
        if (config == null) return false;
        return config.keyboard == Configuration.KEYBOARD_QWERTY
            && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
    }

    /**
     * Get the recommended terminal font size for the current display mode.
     *
     * In DeX mode, we use a larger font size because the display is typically
     * a large external monitor at higher DPI. On the phone screen, the default
     * Termux font size (from properties) is used.
     *
     * @param context any valid Context
     * @param defaultFontSize the normal phone font size (from Termux properties)
     * @return the font size to use (may be adjusted for DeX)
     */
    public static float getRecommendedFontSize(Context context, float defaultFontSize) {
        if (!isDeXModeCached(context)) {
            return defaultFontSize;
        }

        // In DeX mode, scale up slightly. The external monitor has higher pixel
        // density but is farther away, so a ~20% increase is a good starting point.
        // The user can still override via pinch-to-zoom or properties.
        float dexFontSize = defaultFontSize * 1.2f;
        Log.d(TAG, "getRecommendedFontSize: DeX mode, adjusted from "
            + defaultFontSize + " to " + dexFontSize);
        return dexFontSize;
    }

    /**
     * Compute terminal grid dimensions from surface pixel size and cell metrics.
     * Provides a unified calculation used by both the SurfaceView and the Activity
     * when resizing the terminal.
     *
     * @param surfaceWidth  surface width in pixels
     * @param surfaceHeight surface height in pixels
     * @param cellWidth     width of a single terminal cell in pixels
     * @param cellHeight    height of a single terminal cell in pixels
     * @return int array: [cols, rows], both >= 1
     */
    public static int[] computeGridDimensions(int surfaceWidth, int surfaceHeight,
                                               float cellWidth, float cellHeight) {
        int cols = Math.max(1, (int) (surfaceWidth / cellWidth));
        int rows = Math.max(1, (int) (surfaceHeight / cellHeight));
        return new int[] { cols, rows };
    }

    /**
     * Get the current display metrics for the activity's window.
     * Useful for computing terminal grid dimensions after a DeX resize.
     *
     * @param activity the Activity
     * @return DisplayMetrics, or null on failure
     */
    public static DisplayMetrics getDisplayMetrics(Activity activity) {
        if (activity == null) return null;
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                Display display = wm.getDefaultDisplay();
                display.getMetrics(metrics);
                return metrics;
            }
        } catch (Exception e) {
            Log.w(TAG, "getDisplayMetrics: failed", e);
        }
        return null;
    }

    /**
     * Log the current DeX/display state for debugging.
     */
    public static void logDisplayState(Activity activity) {
        if (activity == null) return;
        Configuration config = activity.getResources().getConfiguration();
        DisplayMetrics metrics = getDisplayMetrics(activity);

        Log.i(TAG, "Display state:"
            + " dex=" + isDeXModeCached(activity)
            + " hwKeyboard=" + isHardwareKeyboardConnected(config)
            + " screenWidthDp=" + config.screenWidthDp
            + " screenHeightDp=" + config.screenHeightDp
            + " orientation=" + (config.orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait")
            + (metrics != null ? " density=" + metrics.density
                + " px=" + metrics.widthPixels + "x" + metrics.heightPixels : ""));
    }
}
