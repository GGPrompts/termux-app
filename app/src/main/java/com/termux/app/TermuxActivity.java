package com.termux.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.app.codefactory.BackendObserver;
import com.termux.app.codefactory.CodefactoryBridge;
import com.termux.app.codefactory.CodefactorySurfaceView;
import com.termux.app.codefactory.DeXInputHandler;
import com.termux.app.codefactory.DeXUtils;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

import java.util.Arrays;
import java.util.List;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     * The {@link CodefactorySurfaceView} for GPU-accelerated terminal rendering via wgpu.
     * Initially hidden (GONE); toggle with {@link #toggleGpuRenderer()}.
     */
    CodefactorySurfaceView mCodefactorySurfaceView;

    /**
     * Whether the GPU renderer surface view is currently active (visible).
     */
    private boolean mGpuRendererActive = false;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;

    private float mTerminalToolbarDefaultHeight;

    /**
     * Whether the device is currently in Samsung DeX / desktop mode.
     */
    private boolean mIsDeXMode = false;

    /**
     * Input handler for keyboard shortcuts and mouse events in DeX mode.
     * Also used whenever the GPU renderer is active (not just DeX).
     */
    private DeXInputHandler mDeXInputHandler;


    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_TOGGLE_GPU_RENDERER = 12;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";

    private static final String LOG_TAG = "TermuxActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        setActivityTheme();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_termux);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients();

        setCodefactorySurfaceView();

        setupDeXSupport();

        setTerminalToolbarView(savedInstanceState);

        setNewSessionButtonView();

        setToggleKeyboardView();

        setDashboardButtonView();

        setBackendLogsButtonView();

        registerForContextMenu(mTerminalView);
        if (mCodefactorySurfaceView != null) {
            registerForContextMenu(mCodefactorySurfaceView);
        }

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        registerTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        mIsOnResumeAfterOnCreate = false;
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiver();
        getDrawer().closeDrawers();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        setTermuxSessionsListView();

        final Intent intent = getIntent();
        setIntent(null);

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    try {
                        boolean launchFailsafe = false;
                        if (intent != null && intent.getExtras() != null) {
                            launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null);
            } else {
                mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);

        // GPU renderer activation is opt-in via Settings > Advanced > GPU Renderer toggle.
        // No auto-activation here — the Java terminal path must remain the default.

        // Check if the backend has failed and show error dialog if needed.
        checkAndShowBackendErrorDialog();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }






    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    private void setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    private void setCodefactorySurfaceView() {
        mCodefactorySurfaceView = findViewById(R.id.codefactory_surface_view);
        if (mCodefactorySurfaceView != null) {
            // Ensure the SurfaceView starts GONE (classic terminal is the default)
            mCodefactorySurfaceView.setVisibility(View.GONE);
            mGpuRendererActive = false;

            // Register fallback listener: if the GPU renderer crashes or PTY attach
            // fails, ensure the Java reader is resumed and classic terminal restored
            mCodefactorySurfaceView.setFallbackListener(new CodefactorySurfaceView.FallbackListener() {
                @Override
                public void onGpuRendererFailed(String reason) {
                    Logger.logError(LOG_TAG, "GPU renderer failed, falling back to classic: " + reason);
                    if (mGpuRendererActive) {
                        mGpuRendererActive = false;

                        // Resume the Java reader if we were sharing a PTY
                        if (mCodefactorySurfaceView.isAttachedToExternalPty()) {
                            mCodefactorySurfaceView.detachFromExternalPty();
                        }
                        resumeJavaReaderIfPaused("onGpuRendererFailed");

                        mCodefactorySurfaceView.setVisibility(View.GONE);
                        mTerminalView.setVisibility(View.VISIBLE);
                        mTerminalView.requestFocus();
                        showToast("GPU renderer crashed, switched to classic terminal", true);
                    }
                }

                @Override
                public void onPtyAttachFailed(int fd, String reason) {
                    Logger.logError(LOG_TAG, "PTY attach failed (fd=" + fd + "): " + reason
                        + " -- resuming Java reader");
                    resumeJavaReaderIfPaused("onPtyAttachFailed");
                }
            });

            Logger.logDebug(LOG_TAG, "CodefactorySurfaceView found in layout, native lib available: "
                + CodefactoryBridge.isAvailable());
        } else {
            Logger.logDebug(LOG_TAG, "CodefactorySurfaceView not found in layout");
        }
    }

    /**
     * Toggle between the classic Java TerminalView and the GPU-accelerated
     * CodefactorySurfaceView. For development/testing purposes.
     *
     * Session sharing: When switching to GPU renderer, the active TerminalSession's
     * PTY fd is passed to the Rust pipeline. The Java reader thread is paused so
     * only the Rust side reads from the PTY. When switching back, the Rust pipeline
     * is detached and the Java reader resumes.
     *
     * SAFETY: If the native library is not available, refuses to switch to GPU
     * renderer and shows a Toast explaining why. The classic Java terminal is
     * ALWAYS the fallback. If PTY sharing fails, falls back to spawning a new shell.
     */
    public void toggleGpuRenderer() {
        if (mCodefactorySurfaceView == null || mTerminalView == null) {
            Logger.logError(LOG_TAG, "Cannot toggle GPU renderer: views not initialized");
            return;
        }

        if (!mGpuRendererActive) {
            // Trying to switch TO GPU renderer -- check if native lib is available
            if (!CodefactoryBridge.isAvailable()) {
                String error = CodefactoryBridge.getLoadError();
                String msg = "GPU renderer not available"
                    + (error != null ? ": " + error : " (native library failed to load)");
                showToast(msg, true);
                Logger.logWarn(LOG_TAG, msg);
                return;
            }

            // Also check if the SurfaceView reports native support
            if (!mCodefactorySurfaceView.isNativeAvailable()) {
                showToast("GPU renderer not available (native support disabled)", true);
                Logger.logWarn(LOG_TAG, "toggleGpuRenderer: SurfaceView reports native unavailable");
                return;
            }
        }

        mGpuRendererActive = !mGpuRendererActive;

        if (mGpuRendererActive) {
            // --- Switching TO GPU renderer ---

            // Try to share the active terminal session's PTY with the Rust pipeline
            TerminalSession currentSession = getCurrentSession();
            boolean sharedSession = false;

            if (currentSession != null && currentSession.isRunning()) {
                int ptyFd = currentSession.getPtyFd();
                if (ptyFd >= 0) {
                    Logger.logInfo(LOG_TAG, "toggleGpuRenderer: sharing PTY fd=" + ptyFd
                        + " from session pid=" + currentSession.getPid()
                        + ", pausing Java reader");

                    // Pause the Java reader so only Rust reads from the PTY.
                    // The reader will be resumed by:
                    // - onPtyAttachFailed() if the Rust pipeline fails to attach
                    // - toggleGpuRenderer() when switching back to classic
                    // - onGpuRendererFailed() if the GPU renderer crashes
                    currentSession.pauseReader();

                    // Tell the SurfaceView to attach to this fd
                    mCodefactorySurfaceView.setExternalPtyFd(ptyFd);
                    sharedSession = true;
                } else {
                    Logger.logWarn(LOG_TAG, "toggleGpuRenderer: session has no valid PTY fd");
                }
            } else {
                Logger.logInfo(LOG_TAG, "toggleGpuRenderer: no active session, GPU renderer will spawn its own");
            }

            mTerminalView.setVisibility(View.GONE);
            mCodefactorySurfaceView.setVisibility(View.VISIBLE);
            mCodefactorySurfaceView.requestFocus();

            if (sharedSession) {
                showToast("GPU renderer enabled (shared session)", false);
            } else {
                showToast("GPU renderer enabled", false);
            }
        } else {
            // --- Switching FROM GPU renderer back to classic ---

            // Detach the Rust pipeline from the shared PTY (if attached)
            if (mCodefactorySurfaceView.isAttachedToExternalPty()) {
                mCodefactorySurfaceView.detachFromExternalPty();
            }

            // Always resume the Java reader if it is paused. This covers both the
            // normal detach path AND the case where PTY attach failed (the attach
            // failure clears isAttachedToExternalPty but the reader stays paused).
            resumeJavaReaderIfPaused("toggleGpuRenderer(deactivate)");

            mCodefactorySurfaceView.setVisibility(View.GONE);
            mTerminalView.setVisibility(View.VISIBLE);
            mTerminalView.requestFocus();
            showToast("Classic terminal renderer enabled", false);
        }

        Logger.logInfo(LOG_TAG, "GPU renderer " + (mGpuRendererActive ? "activated" : "deactivated"));
    }

    /**
     * Returns true if the GPU renderer is currently the active view.
     */
    public boolean isGpuRendererActive() {
        return mGpuRendererActive;
    }

    /**
     * Resume the Java reader thread for the current session if it is paused.
     * This is a safety net to ensure the reader is never left permanently paused
     * when GPU activation or PTY attach fails.
     *
     * @param caller Description of the caller for logging (e.g. "onPtyAttachFailed")
     */
    private void resumeJavaReaderIfPaused(String caller) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession != null && currentSession.isReaderPaused()) {
            currentSession.resumeReader();
            Logger.logInfo(LOG_TAG, caller + ": resumed Java reader for session pid="
                + currentSession.getPid());
        } else if (currentSession != null) {
            Logger.logDebug(LOG_TAG, caller + ": Java reader already running for session pid="
                + currentSession.getPid() + ", no resume needed");
        } else {
            Logger.logWarn(LOG_TAG, caller + ": no current session to resume reader for");
        }
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            getDrawer().closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }

    private void setDashboardButtonView() {
        View dashboardButton = findViewById(R.id.drawer_dashboard_button);
        if (dashboardButton != null) {
            dashboardButton.setOnClickListener(v -> {
                getDrawer().closeDrawers();
                Intent intent = CodefactoryWebViewActivity.newInstance(TermuxActivity.this);
                startActivity(intent);
            });
        }
    }

    private void setBackendLogsButtonView() {
        View backendLogsButton = findViewById(R.id.settings_backend_logs);
        if (backendLogsButton != null) {
            backendLogsButton.setOnClickListener(v -> {
                getDrawer().closeDrawers();
                showBackendDebugPanel();
            });
        }
    }





    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
        } else {
            finishActivityIfNotFinishing();
        }
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        String gpuMenuLabel;
        if (mGpuRendererActive) {
            gpuMenuLabel = "Switch to Classic Renderer";
        } else if (CodefactoryBridge.isAvailable()) {
            gpuMenuLabel = "Switch to GPU Renderer";
        } else {
            gpuMenuLabel = "GPU Renderer (unavailable)";
        }
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_GPU_RENDERER, Menu.NONE, gpuMenuLabel);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_TOGGLE_GPU_RENDERER:
                toggleGpuRenderer();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    // -----------------------------------------------------------------------
    // Samsung DeX / Desktop mode support
    // -----------------------------------------------------------------------

    /**
     * Initialize DeX detection and input handling.
     * Called from onCreate() after the surface views are set up.
     */
    private void setupDeXSupport() {
        // Detect initial DeX state
        mIsDeXMode = DeXUtils.isDeXMode(this);
        DeXUtils.logDisplayState(this);

        // Create the input handler (used for GPU renderer keyboard/mouse)
        mDeXInputHandler = new DeXInputHandler();
        mDeXInputHandler.setShortcutHandler(new DeXInputHandler.ShortcutHandler() {
            @Override
            public void onNewSession() {
                if (mTermuxTerminalSessionActivityClient != null) {
                    mTermuxTerminalSessionActivityClient.addNewSession(false, null);
                }
            }

            @Override
            public void onCloseSession() {
                TerminalSession session = getCurrentSession();
                if (session != null) {
                    showKillSessionDialog(session);
                }
            }

            @Override
            public void onPaste() {
                if (mTermuxTerminalViewClient != null) {
                    mTermuxTerminalViewClient.doPaste();
                }
            }

            @Override
            public void onCopy() {
                if (mTermuxTerminalViewClient != null) {
                    mTermuxTerminalViewClient.shareSelectedText();
                }
            }

            @Override
            public void onNextSession() {
                if (mTermuxTerminalSessionActivityClient != null) {
                    mTermuxTerminalSessionActivityClient.switchToSession(true);
                }
            }

            @Override
            public void onPreviousSession() {
                if (mTermuxTerminalSessionActivityClient != null) {
                    mTermuxTerminalSessionActivityClient.switchToSession(false);
                }
            }

            @Override
            public void onSwitchToSession(int index) {
                if (mTermuxTerminalSessionActivityClient != null) {
                    mTermuxTerminalSessionActivityClient.switchToSession(index);
                }
            }

            @Override
            public void onToggleKeyboard() {
                if (mTermuxTerminalViewClient != null) {
                    mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
                }
            }

            @Override
            public void onOpenDrawer() {
                getDrawer().openDrawer(Gravity.LEFT);
            }

            @Override
            public void onToggleRenderer() {
                toggleGpuRenderer();
            }
        });

        // Wire the input handler to the GPU surface view
        if (mCodefactorySurfaceView != null) {
            mCodefactorySurfaceView.setDeXInputHandler(mDeXInputHandler);
        }

        // If in DeX mode, adjust UI: hide extra keys toolbar (hardware keyboard
        // is available), log the mode for debugging
        if (mIsDeXMode) {
            Logger.logInfo(LOG_TAG, "DeX mode detected at startup");
            applyDeXModeAdjustments();
        }
    }

    /**
     * Apply UI adjustments for DeX / desktop mode.
     * Called when DeX mode is detected or when switching to DeX.
     */
    private void applyDeXModeAdjustments() {
        Configuration config = getResources().getConfiguration();
        boolean hwKeyboard = DeXUtils.isHardwareKeyboardConnected(config);

        Logger.logInfo(LOG_TAG, "applyDeXModeAdjustments: hwKeyboard=" + hwKeyboard
            + " screenWidthDp=" + config.screenWidthDp);

        // Hide the extra keys toolbar when a hardware keyboard is connected.
        // The toolbar is designed for touchscreen input and takes up valuable
        // screen space in desktop mode.
        if (hwKeyboard) {
            ViewPager terminalToolbar = getTerminalToolbarViewPager();
            if (terminalToolbar != null && terminalToolbar.getVisibility() == View.VISIBLE) {
                terminalToolbar.setVisibility(View.GONE);
                Logger.logInfo(LOG_TAG, "DeX: hiding extra keys toolbar (hardware keyboard connected)");
            }
        }
    }

    /**
     * Revert UI adjustments when leaving DeX mode.
     */
    private void revertDeXModeAdjustments() {
        Logger.logInfo(LOG_TAG, "revertDeXModeAdjustments: restoring phone UI");

        // Restore extra keys toolbar visibility from preferences
        if (mPreferences != null && mPreferences.shouldShowTerminalToolbar()) {
            ViewPager terminalToolbar = getTerminalToolbarViewPager();
            if (terminalToolbar != null) {
                terminalToolbar.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Handle configuration changes. The manifest declares this activity handles
     * orientation|screenSize|smallestScreenSize|density|screenLayout|keyboard|
     * keyboardHidden|navigation, so this method is called instead of recreating
     * the activity.
     *
     * In DeX mode, this is called frequently during window drag-resize. The
     * terminal grid resize is debounced by CodefactorySurfaceView.
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mIsInvalidState) return;

        Logger.logDebug(LOG_TAG, "onConfigurationChanged: screenSize="
            + newConfig.screenWidthDp + "x" + newConfig.screenHeightDp
            + " keyboard=" + newConfig.keyboard
            + " uiMode=" + Integer.toHexString(newConfig.uiMode));

        // Check if DeX mode changed
        boolean wasDeX = mIsDeXMode;
        mIsDeXMode = DeXUtils.updateFromConfiguration(newConfig);

        if (mIsDeXMode && !wasDeX) {
            // Entered DeX mode
            Logger.logInfo(LOG_TAG, "Entered DeX mode");
            showToast("Desktop mode enabled", false);
            applyDeXModeAdjustments();
        } else if (!mIsDeXMode && wasDeX) {
            // Left DeX mode
            Logger.logInfo(LOG_TAG, "Left DeX mode");
            showToast("Desktop mode disabled", false);
            revertDeXModeAdjustments();
        }

        // Handle keyboard attachment changes (can happen independently of DeX)
        boolean hwKeyboard = DeXUtils.isHardwareKeyboardConnected(newConfig);
        if (mIsDeXMode && hwKeyboard) {
            ViewPager terminalToolbar = getTerminalToolbarViewPager();
            if (terminalToolbar != null && terminalToolbar.getVisibility() == View.VISIBLE) {
                terminalToolbar.setVisibility(View.GONE);
            }
        }

        DeXUtils.logDisplayState(this);
    }

    /**
     * Intercept key events at the activity level for the GPU renderer.
     *
     * When the GPU renderer is active, key events need to be routed through
     * {@link DeXInputHandler} which handles:
     * - App-level shortcuts (Ctrl+Shift+T, etc.) intercepted before terminal
     * - All other keys forwarded to the Rust renderer via JNI
     *
     * When the classic TerminalView is active, keys go through the normal
     * Termux input pipeline (TermuxTerminalViewClient).
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mIsInvalidState) return super.dispatchKeyEvent(event);

        // Only intercept when the GPU renderer is active
        if (mGpuRendererActive && mDeXInputHandler != null) {
            // Let system keys through (volume, power, etc.)
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_POWER
                || keyCode == KeyEvent.KEYCODE_HOME) {
                return super.dispatchKeyEvent(event);
            }

            // Let the DeX input handler process the key
            if (mDeXInputHandler.handleKeyEvent(event)) {
                return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }

    /**
     * Handle generic motion events (mouse hover, scroll wheel) at the activity level.
     *
     * This catches mouse events that are not targeted at a specific view, which
     * can happen during DeX operation. Events targeted at the CodefactorySurfaceView
     * are handled by its own onGenericMotionEvent override.
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (mIsInvalidState) return super.onGenericMotionEvent(event);

        if (mGpuRendererActive && mDeXInputHandler != null) {
            if (DeXInputHandler.isMouseInput(event)) {
                if (mDeXInputHandler.handleGenericMotionEvent(event)) {
                    return true;
                }
            }
        }

        return super.onGenericMotionEvent(event);
    }

    /**
     * Returns true if the device is currently in Samsung DeX / desktop mode.
     */
    public boolean isDeXMode() {
        return mIsDeXMode;
    }

    /**
     * Returns the DeX input handler.
     */
    public DeXInputHandler getDeXInputHandler() {
        return mDeXInputHandler;
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    TermuxActivity.this, requestCode, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        mTermuxSessionListViewController.notifyDataSetChanged();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    // -----------------------------------------------------------------------
    // Backend error dialog and debug panel
    // -----------------------------------------------------------------------

    /**
     * Check if the backend has failed and show an error dialog if so.
     * Called from onServiceConnected and can be called from onResume.
     */
    private void checkAndShowBackendErrorDialog() {
        if (mTermuxService == null) return;

        if (mTermuxService.isBackendFailed() || mTermuxService.getBackendError() != null) {
            // Delay briefly to let the activity finish rendering
            mTerminalView.postDelayed(this::showBackendErrorDialog, 500);
        }
    }

    /**
     * Show a dialog informing the user that the PocketForge backend failed to start.
     * Displays the last 20 log lines, a Retry button, and an Open Terminal button.
     */
    private void showBackendErrorDialog() {
        if (isFinishing() || isDestroyed()) return;
        if (mTermuxService == null) return;

        String errorMsg = mTermuxService.getBackendError();
        BackendObserver observer = mTermuxService.getBackendObserver();

        StringBuilder body = new StringBuilder();
        body.append("The PocketForge backend failed to start.");

        if (errorMsg != null) {
            body.append("\n\nError: ").append(errorMsg);
        }

        if (observer != null && observer.isCrashLoopDetected()) {
            body.append("\n\nA crash loop was detected (")
                .append(observer.getCrashCount())
                .append(" crashes in 60 seconds). Automatic retries have been stopped.");
        }

        // Append last log lines
        if (observer != null) {
            List<String> logLines = observer.readLastLogLines(BackendObserver.ERROR_DIALOG_LINE_COUNT);
            if (!logLines.isEmpty()) {
                body.append("\n\n--- Recent log ---\n");
                for (String line : logLines) {
                    body.append(line).append("\n");
                }
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(getString(R.string.backend_error_dialog_title))
            .setMessage(body.toString())
            .setCancelable(true)
            .setPositiveButton(getString(R.string.backend_error_retry), (dialog, which) -> {
                if (mTermuxService != null) {
                    mTermuxService.retryBackendStart();
                    showToast("Retrying backend start...", false);
                }
            })
            .setNegativeButton(getString(R.string.backend_error_open_terminal), (dialog, which) -> {
                // Just dismiss -- user can use the terminal for debugging
                dialog.dismiss();
            });

        builder.show();
    }

    /**
     * Show the developer debug panel as a dialog with recent backend log lines.
     * Accessible from the drawer Settings tab.
     */
    public void showBackendDebugPanel() {
        if (mTermuxService == null) {
            showToast("Service not connected", false);
            return;
        }

        BackendObserver observer = mTermuxService.getBackendObserver();
        if (observer == null) {
            showToast("Backend observer not available", false);
            return;
        }

        List<String> logLines = observer.getRecentLogLines(BackendObserver.LOG_RING_BUFFER_SIZE);

        // Build a scrollable text view with log lines
        ScrollView scrollView = new ScrollView(this);
        scrollView.setPadding(24, 16, 24, 16);

        TextView logView = new TextView(this);
        logView.setTextSize(11f);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextColor(getResources().getColor(R.color.pf_text_primary));

        if (logLines.isEmpty()) {
            logView.setText("No log entries yet.");
        } else {
            StringBuilder sb = new StringBuilder();
            for (String line : logLines) {
                sb.append(line).append("\n");
            }
            logView.setText(sb.toString());
        }

        scrollView.addView(logView);
        scrollView.setBackgroundColor(getResources().getColor(R.color.pf_bg_secondary));

        // Scroll to bottom after layout
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));

        // Build status header
        StringBuilder statusHeader = new StringBuilder();
        statusHeader.append("Backend: ");
        if (mTermuxService.isBackendFailed()) {
            statusHeader.append("FAILED (crash loop)");
        } else if (mTermuxService.isBackendRunning()) {
            statusHeader.append("Running");
        } else if (mTermuxService.getBackendError() != null) {
            statusHeader.append("Error");
        } else {
            statusHeader.append("Starting...");
        }
        statusHeader.append(" | Crashes: ").append(observer.getCrashCount());
        statusHeader.append(" | Lines: ").append(logLines.size());

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(getString(R.string.backend_debug_panel_title))
            .setMessage(statusHeader.toString())
            .setView(scrollView)
            .setCancelable(true)
            .setPositiveButton("Close", null);

        if (mTermuxService.isBackendFailed() || mTermuxService.getBackendError() != null) {
            builder.setNeutralButton(getString(R.string.backend_error_retry), (dialog, which) -> {
                if (mTermuxService != null) {
                    mTermuxService.retryBackendStart();
                    showToast("Retrying backend start...", false);
                }
            });
        }

        builder.show();
    }

    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
