package com.nusuk.automator.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.nusuk.automator.core.L;
import com.nusuk.automator.overlay.OverlayService;

/**
 * The website's tutorial (/tutorial: videos) or Help (/help: step-by-step fixes) page in a WebView,
 * so they are changed on the web (app/public/) without an app update.
 * ?app=1 hides the page's "Home" link; &v= tells the Help page this app's version.
 * The page picks Bangla/English like the website.
 * Links to other sites (Facebook group...) open in the browser.
 */
public class TutorialActivity extends Activity {

    private static final String HOST = "nusuk.minbars.net";
    /** Intent extra: which page to open, "/tutorial" (default) or "/help". */
    static final String EXTRA_PATH = "path";

    private WebView web;
    private ProgressBar loading;
    private TextView error;
    private FrameLayout root;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float dp = getResources().getDisplayMetrics().density;

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF37608D); // the page header's color, also behind the status bar

        web = new WebView(this);
        web.setBackgroundColor(0xFFF5F5F4);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);   // language switch + video picker on the page
        s.setDomStorageEnabled(true);   // remembers the chosen language
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setIndeterminate(true);
        root.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (4 * dp), Gravity.TOP));

        error = new TextView(this);
        error.setText(L.t(this, "Couldn't load the tutorials.\nCheck your internet, then tap here to try again."));
        error.setTextColor(0xFFE7E5E4);
        error.setTextSize(17);
        error.setGravity(Gravity.CENTER);
        error.setPadding((int) (32 * dp), 0, (int) (32 * dp), 0);
        error.setVisibility(View.GONE);
        error.setOnClickListener(v -> {
            error.setVisibility(View.GONE);
            if (web == null) return;
            web.setVisibility(View.VISIBLE);
            web.reload();
        });
        root.addView(error, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // Close, where the page's (hidden in-app) "Back to codes" link sits
        TextView close = new TextView(this);
        close.setText(L.t(this, "✕  Close"));
        close.setTextSize(15);
        close.setTextColor(0xFFFDE68A);
        int ph = (int) (12 * dp), pv = (int) (6 * dp);
        close.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(999f);
        bg.setColor(0x22FFFFFF);
        close.setBackground(bg);
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        int margin = (int) (14 * dp);
        closeLp.setMargins(margin, (int) (10 * dp), margin, 0);
        root.addView(close, closeLp);

        // Edge-to-edge (Android 15+): keep the page and the Close pill clear of the system bars
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop(), bottom = insets.getSystemWindowInsetBottom();
            if (web != null) {
                ((FrameLayout.LayoutParams) web.getLayoutParams()).setMargins(0, top, 0, bottom);
                web.requestLayout();
            }
            closeLp.topMargin = top + (int) (10 * dp);
            close.setLayoutParams(closeLp);
            ((FrameLayout.LayoutParams) loading.getLayoutParams()).topMargin = top;
            loading.requestLayout();
            return insets;
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false; // our site stays in here
                if (!"https".equals(u.getScheme()) && !"http".equals(u.getScheme())) return true;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loading.setVisibility(View.GONE);
            }

            // Without this, a crash of the page's renderer (low memory, big video) kills the
            // WHOLE app, overlay and running automation included. Just close this screen.
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                if (web != null) {
                    root.removeView(web);
                    web.destroy();
                    web = null;
                }
                if (!isFinishing()) finish();
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (!req.isForMainFrame() || web == null) return;
                loading.setVisibility(View.GONE);
                web.setVisibility(View.INVISIBLE);
                error.setVisibility(View.VISIBLE);
            }
        });

        // Full-screen button on the videos
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return; }
                fullscreenView = view;
                fullscreenCallback = callback;
                view.setBackgroundColor(Color.BLACK);
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        setContentView(root);
        String path = "/help".equals(getIntent().getStringExtra(EXTRA_PATH)) ? "/help" : "/tutorial";
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        web.loadUrl("https://" + HOST + path + "?app=1&v=" + Uri.encode(version));
    }

    private void exitFullscreen() {
        if (fullscreenView == null) return;
        root.removeView(fullscreenView);
        fullscreenView = null;
        if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        fullscreenCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (fullscreenView != null) { exitFullscreen(); return; }
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        OverlayService.setAppOpen(false);
        if (web != null) web.onPause(); // stops video sound when the app goes to the background
    }

    @Override
    protected void onResume() {
        super.onResume();
        OverlayService.setAppOpen(true);
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
