package dev.enginehost.plugin.web;

import android.annotation.SuppressLint;
import android.net.Uri;
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
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * One browser runtime for the engines that are a web page: HTML games
 * (Twine stories among them), RPG Maker MV and MZ deployed for the web,
 * and Flash/AIR through Ruffle.
 *
 * The game is served to the WebView from its own folder over a private
 * https origin (see {@link GameServer}) rather than opened as file://
 * URLs. That gives the page a real origin, which is what a browser needs
 * before it will fetch, decode WebAssembly, or keep session state, and it
 * keeps the page confined to the game folder. Saves never live in the
 * WebView: {@link LocalStorageBridge} replaces localStorage with a store
 * whose file sits in the save folder Enginehost chose for this game.
 */
public final class WebGamePlugin implements EnginePlugin {
    private static final String TAG = "enginehost-web";

    private EnginePluginSession session;
    private WebView webView;
    private GameServer server;
    private LocalStorageBridge storage;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public void onCreate(EnginePluginSession session) throws Exception {
        this.session = session;
        Runtime runtime = Runtime.forSession(session);
        File gameRoot = new File(session.gamePath()).getCanonicalFile();
        if (!gameRoot.isDirectory()) throw new IOException("The game folder is not readable");
        JSONObject options = new JSONObject(session.optionsJson() == null ? "{}" : session.optionsJson());

        storage = new LocalStorageBridge(new File(session.host().saveDirectory(), "localStorage.json"),
            (priority, message, error) -> session.host().log(priority, TAG, message, error));
        server = new GameServer(gameRoot, session.bundleDirectory(), runtime, session.execFile(), options);

        webView = new WebView(session.host().context());
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(options.optBoolean("javaScript", true));
        // sessionStorage and the page's own scratch state. Saves do not go
        // here; localStorage is replaced before the page's first script runs.
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(options.optBoolean("mediaPlaybackRequiresGesture", false));
        settings.setSupportZoom(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        String userAgent = options.optString("userAgent", "");
        if (!userAgent.isBlank()) settings.setUserAgentString(userAgent);
        WebView.setWebContentsDebuggingEnabled(options.optBoolean("webContentsDebugging", false));
        webView.setBackgroundColor(0xFF000000);
        webView.addJavascriptInterface(storage, LocalStorageBridge.JS_NAME);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                int priority = message.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                    ? android.util.Log.ERROR : android.util.Log.DEBUG;
                session.host().log(priority, TAG, message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")", null);
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Navigation stays inside the game. A page that wants to open
                // the outside world gets nothing, not a browser.
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
        int key = keyFor(event.action());
        if (key == KeyEvent.KEYCODE_UNKNOWN || webView == null) return false;
        int action = event.pressed() ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
        return webView.dispatchKeyEvent(new KeyEvent(event.eventTime(), event.eventTime(), action, key, 0));
    }

    /**
     * What an Enginehost action means to a page. RPG Maker reads the
     * keyboard the way its desktop build does: Enter confirms, Escape
     * cancels and opens the menu, Shift dashes, Page Up and Page Down turn
     * pages, Control skips a message. A story or a Flash game is driven by
     * the arrows and Enter, and Escape where it has a menu.
     */
    private int keyFor(String action) {
        switch (action) {
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "confirm": return KeyEvent.KEYCODE_ENTER;
            case "cancel": case "menu": return KeyEvent.KEYCODE_ESCAPE;
            case "auto": return KeyEvent.KEYCODE_SHIFT_LEFT;
            case "skip": return KeyEvent.KEYCODE_CTRL_LEFT;
            case "page_previous": return KeyEvent.KEYCODE_PAGE_UP;
            case "page_next": return KeyEvent.KEYCODE_PAGE_DOWN;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
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

    /** Which page runtime a session asks for, from the engine and context Enginehost resolved. */
    enum Runtime {
        HTML, RPGMAKER_MV, RPGMAKER_MZ, FLASH_SWF, FLASH_AIR;

        static Runtime forSession(EnginePluginSession session) throws IOException {
            String engine = session.engine();
            String context = session.engineContext();
            if ("html".equals(engine)) return HTML;
            if ("rpgmaker".equals(engine) && "mv".equals(context)) return RPGMAKER_MV;
            if ("rpgmaker".equals(engine) && "mz".equals(context)) return RPGMAKER_MZ;
            if ("flash_air".equals(engine) && "swf".equals(context)) return FLASH_SWF;
            if ("flash_air".equals(engine) && "air".equals(context)) return FLASH_AIR;
            throw new IOException("This runtime does not run " + engine + " (" + context + ")");
        }

        boolean isFlash() { return this == FLASH_SWF || this == FLASH_AIR; }
    }

    static WebResourceResponse textResponse(String mime, String body) {
        return new WebResourceResponse(mime, "UTF-8", new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    static Uri uri(String value) { return Uri.parse(value); }
}
