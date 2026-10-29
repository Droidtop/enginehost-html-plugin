package dev.enginehost.plugin.html;

import android.view.KeyEvent;
import android.webkit.WebView;
import dev.enginehost.api.EngineControllerEvent;
import dev.enginehost.api.EnginePlugin;
import dev.enginehost.api.EnginePluginSession;
import dev.enginehost.web.GameServer;
import dev.enginehost.web.GameWebView;
import java.io.IOException;

/**
 * HTML games (Twine stories among them) in a confined browser.
 *
 * The browser, the private https origin the game is served from and the
 * save-folder localStorage are Enginehost's plugin-web ({@link GameWebView}),
 * shared with the RPG Maker MV/MZ core. What is this core's own: the engine
 * it accepts, the pages a game folder may start at, and the keys a pad's
 * actions become.
 */
public final class HtmlGamePlugin implements EnginePlugin {
    private static final String TAG = "enginehost-html";

    private static final GameServer.Site SITE = () -> new String[] {"index.html", "index.htm"};

    private GameWebView game;

    @Override
    public void onCreate(EnginePluginSession session) throws Exception {
        if (!"html".equals(session.engine())) throw new IOException("This runtime runs html games, not " + session.engine());
        game = GameWebView.open(session, TAG, SITE);
        // The page has the keyboard from the start: Android hands a keyboard's keys to the
        // focused view, and with none focused a story ignored Enter until it was tapped
        // (Enginehost's emulator conformance run, Droidtop/tracker#27).
        WebView view = game.view();
        view.setFocusableInTouchMode(true);
        view.requestFocus();
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
        WebView view = game == null ? null : game.view();
        if (view == null) return false;
        int action = event.pressed() ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
        return view.dispatchKeyEvent(new KeyEvent(event.eventTime(), event.eventTime(), action, key, 0));
    }

    @Override public void onResume() { if (game != null) game.onResume(); }

    @Override public void onPause() { if (game != null) game.onPause(); }

    @Override public void onDestroy() { if (game != null) game.onDestroy(); }
}
