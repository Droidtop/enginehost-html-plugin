package dev.enginehost.plugin.web;

import android.app.Activity;
import android.os.Bundle;
import android.net.Uri;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.Toast;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

abstract class GameWebActivity extends Activity {
    protected WebView webView;
    protected File gameRoot;
    private boolean allowNetwork;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String path = getIntent().getStringExtra("path");
        try {
            validateEngineRequest();
            if (path == null) throw new IOException("enginehost did not provide a game folder");
            gameRoot = new File(path).getCanonicalFile();
            if (!gameRoot.isDirectory()) throw new IOException("enginehost game folder is not readable");
        } catch (IOException exception) {
            fail(exception.getMessage());
            return;
        }

        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        JSONObject options;
        try { options = new JSONObject(getIntent().getStringExtra("options") == null ? "{}" : getIntent().getStringExtra("options")); }
        catch (JSONException error) { fail("options must be valid JSON"); return; }
        allowNetwork = options.optBoolean("allowNetwork", false);
        settings.setJavaScriptEnabled(options.optBoolean("javaScript", true));
        settings.setDomStorageEnabled(options.optBoolean("domStorage", true));
        settings.setDatabaseEnabled(options.optBoolean("database", true));
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(options.optBoolean("mediaPlaybackRequiresGesture", false));
        settings.setBlockNetworkLoads(!allowNetwork);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isAllowed(request.getUrl());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (isAllowed(request.getUrl())) return super.shouldInterceptRequest(view, request);
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
        });
        setContentView(webView);

        try {
            loadGame();
        } catch (Exception exception) {
            fail(exception.getMessage());
        }
    }

    protected abstract void loadGame() throws Exception;

    protected abstract void validateEngineRequest() throws IOException;

    private boolean isAllowed(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null || "about".equals(scheme) || "data".equals(scheme)) return true;
        if ("http".equals(scheme) || "https".equals(scheme)) return allowNetwork;
        if (!"file".equals(scheme)) return false;
        String path = uri.getPath();
        try {
            File file = new File(path == null ? "" : path).getCanonicalFile();
            return file.getPath().startsWith(gameRoot.getPath() + File.separator);
        } catch (IOException ignored) { return false; }
    }

    protected final boolean versionInRange(String version, String min, String max) {
        if (version == null || !version.matches("\\d+(\\.\\d+)+")) return false;
        return compareVersions(version, min) >= 0 && compareVersions(version, max) <= 0;
    }

    private int compareVersions(String left, String right) {
        String[] a = left.split("\\."), b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? Integer.parseInt(a[i]) : 0;
            int bv = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    protected File confinedFile(String requested) throws IOException {
        File file = new File(gameRoot, requested).getCanonicalFile();
        String prefix = gameRoot.getPath() + File.separator;
        if (!file.getPath().startsWith(prefix) || !file.isFile()) {
            throw new IOException("Requested entry file is not inside the game folder");
        }
        return file;
    }

    protected void fail(String message) {
        Toast.makeText(this, message == null ? "Runtime failed" : message, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
