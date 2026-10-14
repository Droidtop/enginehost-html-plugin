package dev.enginehost.plugin.html;

import android.annotation.SuppressLint;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import dev.enginehost.api.EngineControllerEvent;
import dev.enginehost.api.EnginePlugin;
import dev.enginehost.api.EnginePluginSession;
import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/**
 * HTML games (Twine stories among them) in a confined browser.
 *
 * The game is served from its own folder over a private https origin (see
 * {@link GameServer}) rather than opened as file:// URLs, so the page has a
 * real origin and stays inside its folder. Saves never live in the WebView:
 * {@link LocalStorageBridge} replaces localStorage with a store whose file
 * sits in the save folder Enginehost chose for this game.
 */
public final class HtmlGamePlugin implements EnginePlugin {
    private static final String TAG = "enginehost-html";

    private WebView webView;
    private GameServer server;
    private LocalStorageBridge storage;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public void onCreate(EnginePluginSession session) throws Exception {
        if (!"html".equals(session.engine())) throw new IOException("This runtime runs html games, not " + session.engine());
        File gameRoot = new File(session.gamePath()).getCanonicalFile();
        if (!gameRoot.isDirectory()) throw new IOException("The game folder is not readable");
        JSONObject options = new JSONObject(session.optionsJson() == null ? "{}" : session.optionsJson());

        storage = new LocalStorageBridge(new File(session.host().saveDirectory(), "localStorage.json"),
            (priority, message, error) -> session.host().log(priority, TAG, message, error));
        server = new GameServer(gameRoot, session.execFile(), options);

        webView = new WebView(session.host().context());
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(options.optBoolean("javaScript", true));
        settings.setDomStorageEnabled(true); // sessionStorage; saves go through the bridge
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(options.optBoolean("mediaPlaybackRequiresGesture", false));
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        String userAgent = options.optString("userAgent", "");
        if (!userAgent.isBlank()) settings.setUserAgentString(userAgent);
        WebView.setWebContentsDebuggingEnabled(options.optBoolean("webContentsDebugging", false));
        webView.setBackgroundColor(0xFF000000);
        webView.addJavascriptInterface(storage, LocalStorageBridge.JS_NAME);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                int priority = message.messageLevel() == ConsoleMessage.MessageLevel.ERROR ? android.util.Log.ERROR : android.util.Log.DEBUG;
                session.host().log(priority, TAG, message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")", null);
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !server.serves(request.getUrl());
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    return server.respond(request);
                } catch (IOException error) {
                    session.host().log(android.util.Log.WARN, TAG, "Could not serve " + request.getUrl(), error);
                    return GameServer.status(500, "Internal error");
                }
            }
        });
        session.display().addView(webView, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.loadUrl(server.entryUrl());
    }

    @Override public boolean onControllerEvent(EngineControllerEvent event) {
        int key;
        switch (event.action()) {
            case "up": key = KeyEvent.KEYCODE_DPAD_UP; break;
            case "down": key = KeyEvent.KEYCODE_DPAD_DOWN; break;
            case "left": case "page_previous": key = KeyEvent.KEYCODE_DPAD_LEFT; break;
            case "right": case "page_next": key = KeyEvent.KEYCODE_DPAD_RIGHT; break;
            case "confirm": key = KeyEvent.KEYCODE_ENTER; break;
            case "cancel": case "menu": key = KeyEvent.KEYCODE_ESCAPE; break;
            default: return false;
        }
        if (webView == null) return false;
        int action = event.pressed() ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
        return webView.dispatchKeyEvent(new KeyEvent(event.eventTime(), event.eventTime(), action, key, 0));
    }

    @Override public void onResume() { if (webView != null) webView.onResume(); }

    @Override public void onPause() {
        if (webView != null) webView.onPause();
        if (storage != null) storage.flush();
    }

    @Override public void onDestroy() {
        if (storage != null) storage.flush();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
    }
}
