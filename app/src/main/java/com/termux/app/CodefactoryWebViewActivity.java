package com.termux.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.logger.Logger;

/**
 * Activity that hosts a WebView for the Codefactory dashboard.
 * <p>
 * Loads the Codefactory Axum backend at {@code http://localhost:3001}.
 * This is used for dashboard pages only (git, kanban, files, search, elevator UI),
 * NOT for terminal rendering which is handled by the native wgpu/Vulkan SurfaceView.
 * <p>
 * The WebView survives configuration changes (rotation) via {@code configChanges}
 * declared in AndroidManifest.xml.
 */
public class CodefactoryWebViewActivity extends AppCompatActivity {

    private static final String LOG_TAG = "CodefactoryWebView";
    private static final String CODEFACTORY_URL = "http://localhost:3001";
    private static final String ARG_WEBVIEW_URL = "webview_url";

    private WebView mWebView;
    private ProgressBar mProgressBar;
    private TextView mTitleView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.logDebug(LOG_TAG, "onCreate");

        setContentView(R.layout.activity_codefactory_webview);

        mWebView = findViewById(R.id.codefactory_webview);
        mProgressBar = findViewById(R.id.webview_progress);
        mTitleView = findViewById(R.id.webview_title);

        configureWebView();
        setupToolbar();

        // Restore URL from saved state or load the default
        String url = CODEFACTORY_URL;
        if (savedInstanceState != null) {
            String savedUrl = savedInstanceState.getString(ARG_WEBVIEW_URL);
            if (savedUrl != null) {
                url = savedUrl;
            }
        } else {
            // Check if launched with a specific URL intent extra
            Intent intent = getIntent();
            if (intent != null && intent.hasExtra("url")) {
                url = intent.getStringExtra("url");
            }
        }

        if (savedInstanceState != null) {
            mWebView.restoreState(savedInstanceState);
        } else {
            mWebView.loadUrl(url);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = mWebView.getSettings();

        // JavaScript is required for the Codefactory dashboard
        settings.setJavaScriptEnabled(true);

        // Enable DOM storage and localStorage for dashboard state persistence
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        // Allow file access for local resources
        settings.setAllowFileAccess(true);

        // Enable mixed content for localhost development
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Viewport and zoom settings
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        // Cache settings -- use cache when available, fetch from network otherwise
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Enable WebSocket support (inherent in WebView with JS enabled)
        // Allow content URL access
        settings.setAllowContentAccess(true);

        // Set user agent to identify PocketForge
        String defaultUA = settings.getUserAgentString();
        settings.setUserAgentString(defaultUA + " PocketForge/1.0");

        // Set dark background to avoid white flash while loading
        mWebView.setBackgroundColor(0xFF0D1117);

        // Set WebViewClient to handle navigation within the WebView
        mWebView.setWebViewClient(new CodefactoryWebViewClient());

        // Set WebChromeClient for progress and title updates
        mWebView.setWebChromeClient(new CodefactoryWebChromeClient());
    }

    private void setupToolbar() {
        // Back button -- navigate WebView history or finish activity
        ImageButton backButton = findViewById(R.id.webview_back_button);
        backButton.setOnClickListener(v -> {
            if (mWebView.canGoBack()) {
                mWebView.goBack();
            } else {
                finish();
            }
        });

        // Reload button
        ImageButton reloadButton = findViewById(R.id.webview_reload_button);
        reloadButton.setOnClickListener(v -> mWebView.reload());

        // Switch to terminal button
        ImageButton terminalButton = findViewById(R.id.webview_terminal_button);
        terminalButton.setOnClickListener(v -> {
            // Navigate back to TermuxActivity
            Intent intent = new Intent(this, TermuxActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Logger.logVerbose(LOG_TAG, "onResume");
        if (mWebView != null) {
            mWebView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Logger.logVerbose(LOG_TAG, "onPause");
        if (mWebView != null) {
            mWebView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Logger.logDebug(LOG_TAG, "onDestroy");
        if (mWebView != null) {
            mWebView.destroy();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mWebView != null) {
            mWebView.saveState(outState);
            outState.putString(ARG_WEBVIEW_URL, mWebView.getUrl());
        }
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * WebViewClient that keeps navigation within the WebView for localhost URLs
     * and updates the toolbar title.
     */
    private class CodefactoryWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            // Keep localhost navigation within this WebView
            if (url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1")) {
                return false; // Let WebView handle it
            }
            // For external URLs, let the system handle them
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            if (mProgressBar != null) {
                mProgressBar.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (mProgressBar != null) {
                mProgressBar.setVisibility(View.GONE);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request.isForMainFrame()) {
                Logger.logError(LOG_TAG, "WebView error loading " + request.getUrl()
                    + ": " + error.getDescription());
                // Load a simple error page
                String errorHtml = "<html><body style='background:#0D1117;color:#E6EDF3;font-family:sans-serif;"
                    + "display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;'>"
                    + "<h2>Dashboard Unavailable</h2>"
                    + "<p style='color:#8B949E;'>Could not connect to Codefactory at<br><code>"
                    + CODEFACTORY_URL + "</code></p>"
                    + "<p style='color:#8B949E;font-size:14px;'>Make sure the backend is running:<br>"
                    + "<code style='color:#58A6FF;'>codefactory serve</code></p>"
                    + "<button onclick='location.reload()' style='margin-top:20px;padding:10px 24px;"
                    + "background:#1F6FEB;color:white;border:none;border-radius:6px;font-size:14px;"
                    + "cursor:pointer;'>Retry</button>"
                    + "</body></html>";
                view.loadData(errorHtml, "text/html", "UTF-8");
            }
        }
    }

    /**
     * WebChromeClient that updates progress bar and page title.
     */
    private class CodefactoryWebChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (mProgressBar != null) {
                mProgressBar.setProgress(newProgress);
                if (newProgress >= 100) {
                    mProgressBar.setVisibility(View.GONE);
                } else {
                    mProgressBar.setVisibility(View.VISIBLE);
                }
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            super.onReceivedTitle(view, title);
            if (mTitleView != null && title != null && !title.isEmpty()
                    && !title.startsWith("http://") && !title.startsWith("https://")) {
                mTitleView.setText(title);
            }
        }
    }

    /**
     * Create an intent to launch this activity.
     */
    public static Intent newInstance(@NonNull Context context) {
        return new Intent(context, CodefactoryWebViewActivity.class);
    }

    /**
     * Create an intent to launch this activity with a specific URL.
     */
    public static Intent newInstance(@NonNull Context context, @NonNull String url) {
        Intent intent = new Intent(context, CodefactoryWebViewActivity.class);
        intent.putExtra("url", url);
        return intent;
    }
}
