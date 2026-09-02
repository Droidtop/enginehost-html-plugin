package dev.enginehost.plugin.web;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import dev.enginehost.api.EngineControllerEvent;
import dev.enginehost.api.EnginePlugin;
import dev.enginehost.api.EnginePluginSession;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/** In-process compiled-Twine runtime with confined navigation and external saves. */
public final class TwinePlugin implements EnginePlugin {
    private EnginePluginSession session;
    private WebView webView;
    private File gameRoot;
    private File localStorageSave;
    private boolean allowNetwork;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public void onCreate(EnginePluginSession session) throws Exception {
        this.session = session;
        if (!"twine".equals(session.engine()) || !"compiled-html".equals(session.engineContext())) {
            throw new IOException("Unsupported Twine engine context");
        }
        gameRoot = new File(session.gamePath()).getCanonicalFile();
        if (!gameRoot.isDirectory()) throw new IOException("Twine game folder is not readable");
        localStorageSave = new File(session.host().saveDirectory(), "twine-local-storage.json");
        JSONObject options = new JSONObject(session.optionsJson() == null ? "{}" : session.optionsJson());
        allowNetwork = options.optBoolean("allowNetwork", false);

        webView = new WebView(session.host().context());
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(options.optBoolean("javaScript", true));
        settings.setDomStorageEnabled(options.optBoolean("domStorage", true));
        settings.setDatabaseEnabled(options.optBoolean("database", true));
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(options.optBoolean("mediaPlaybackRequiresGesture", false));
        settings.setBlockNetworkLoads(!allowNetwork);
        webView.addJavascriptInterface(new SaveBridge(), "EnginehostSave");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new ConfinedClient());
        session.display().addView(
            webView,
            new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        );
        webView.loadUrl(entryFile().toURI().toString());
    }

    private File entryFile() throws IOException {
        if (session.execFile() != null && !session.execFile().isBlank()) return confinedFile(session.execFile());
        File index = new File(gameRoot, "index.html");
        if (index.isFile()) return index.getCanonicalFile();
        File[] html = gameRoot.listFiles((directory, name) -> name.toLowerCase().endsWith(".html"));
        if (html != null && html.length == 1) return html[0].getCanonicalFile();
        throw new IOException("Set execFile to the compiled Twine HTML story");
    }

    private File confinedFile(String relative) throws IOException {
        if (new File(relative).isAbsolute()) throw new IOException("Twine execFile must be relative");
        File file = new File(gameRoot, relative).getCanonicalFile();
        if (!file.getPath().startsWith(gameRoot.getPath() + File.separator) || !file.isFile()) {
            throw new IOException("Twine entry file leaves the game folder");
        }
        return file;
    }

    private boolean allowed(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null || "about".equals(scheme) || "data".equals(scheme)) return true;
        if ("http".equals(scheme) || "https".equals(scheme)) return allowNetwork;
        if (!"file".equals(scheme)) return false;
        try {
            File file = new File(uri.getPath() == null ? "" : uri.getPath()).getCanonicalFile();
            return file.getPath().startsWith(gameRoot.getPath() + File.separator);
        } catch (IOException ignored) {
            return false;
        }
    }

    private final class ConfinedClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !allowed(request.getUrl());
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (allowed(request.getUrl())) return super.shouldInterceptRequest(view, request);
            return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
        }

        @Override public void onPageFinished(WebView view, String url) {
            String saved = "{}";
            try {
                if (localStorageSave.isFile()) {
                    try (FileInputStream input = new FileInputStream(localStorageSave)) {
                        if (localStorageSave.length() > 4L * 1024 * 1024) throw new IOException("Twine save is too large");
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        for (int count; (count = input.read(buffer)) >= 0;) bytes.write(buffer, 0, count);
                        saved = bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name());
                    }
                }
                new JSONObject(saved);
            } catch (Exception ignored) {
                saved = "{}";
            }
            String script = "(function(raw){var saved=JSON.parse(raw);Object.keys(saved).forEach(function(k){" +
                "localStorage.setItem(k,saved[k]);});function sync(){var o={};for(var i=0;i<localStorage.length;i++){" +
                "var k=localStorage.key(i);o[k]=localStorage.getItem(k);}EnginehostSave.persist(JSON.stringify(o));}" +
                "['setItem','removeItem','clear'].forEach(function(n){var f=Storage.prototype[n];Storage.prototype[n]=" +
                "function(){var r=f.apply(this,arguments);sync();return r;};});sync();})(" +
                JSONObject.quote(saved) + ");";
            view.evaluateJavascript(script, null);
        }
    }

    private final class SaveBridge {
        @JavascriptInterface public void persist(String json) {
            try {
                JSONObject validated = new JSONObject(json);
                try (FileOutputStream output = new FileOutputStream(localStorageSave, false)) {
                    output.write(validated.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception error) {
                session.host().log(android.util.Log.ERROR, "twine", "Could not persist Twine save data", error);
            }
        }
    }

    @Override public boolean onControllerEvent(EngineControllerEvent event) {
        int key = switch (event.action()) {
            case "up" -> KeyEvent.KEYCODE_DPAD_UP;
            case "down" -> KeyEvent.KEYCODE_DPAD_DOWN;
            case "left", "page_previous" -> KeyEvent.KEYCODE_DPAD_LEFT;
            case "right", "page_next" -> KeyEvent.KEYCODE_DPAD_RIGHT;
            case "confirm" -> KeyEvent.KEYCODE_ENTER;
            case "cancel", "menu" -> KeyEvent.KEYCODE_ESCAPE;
            default -> KeyEvent.KEYCODE_UNKNOWN;
        };
        if (key == KeyEvent.KEYCODE_UNKNOWN) return false;
        int action = event.pressed() ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
        return webView.dispatchKeyEvent(new KeyEvent(event.eventTime(), event.eventTime(), action, key, 0));
    }

    @Override public void onResume() { webView.onResume(); }
    @Override public void onPause() { webView.onPause(); }
    @Override public void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
    }
}
