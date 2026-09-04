package dev.enginehost.plugin.web;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Serves the game folder to the WebView as an https origin of its own.
 *
 * Every request the page makes arrives here through
 * shouldInterceptRequest and is answered from the game folder; nothing is
 * opened as a file:// URL and nothing outside the folder is reachable.
 * The private origin is what makes a page behave like a page: fetch works,
 * WebAssembly compiles, media seeks (Range requests are honoured), and the
 * game's own scripts see a normal document. The runtime's own files, the
 * Ruffle player among them, live under a reserved path on the same origin.
 *
 * Two accommodations for how games are deployed: an HTML document gets the
 * localStorage script from {@link LocalStorageBridge} injected ahead of its
 * own scripts, and an audio file asked for in one format is answered with
 * its sibling in the other when only that one exists (RPG Maker MV asks a
 * mobile browser for .m4a while a Windows deploy ships only .ogg).
 */
final class GameServer {
    static final String HOST = "game.enginehost.local";
    static final String ORIGIN = "https://" + HOST;
    static final String RUNTIME_PREFIX = "/__enginehost__/";
    private static final Pattern AIR_CONTENT = Pattern.compile("<content>\\s*([^<]+?)\\s*</content>");
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    private static final Map<String, String> MIME = new HashMap<>();

    static {
        String[][] table = {
            {"html", "text/html"}, {"htm", "text/html"}, {"js", "text/javascript"}, {"mjs", "text/javascript"},
            {"css", "text/css"}, {"json", "application/json"}, {"xml", "text/xml"}, {"txt", "text/plain"},
            {"wasm", "application/wasm"}, {"swf", "application/x-shockwave-flash"},
            {"png", "image/png"}, {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"}, {"gif", "image/gif"},
            {"webp", "image/webp"}, {"svg", "image/svg+xml"}, {"ico", "image/x-icon"}, {"bmp", "image/bmp"},
            {"ogg", "audio/ogg"}, {"oga", "audio/ogg"}, {"m4a", "audio/mp4"}, {"mp3", "audio/mpeg"},
            {"wav", "audio/wav"}, {"flac", "audio/flac"}, {"opus", "audio/ogg"},
            {"mp4", "video/mp4"}, {"webm", "video/webm"}, {"ogv", "video/ogg"},
            {"woff", "font/woff"}, {"woff2", "font/woff2"}, {"ttf", "font/ttf"}, {"otf", "font/otf"},
            {"pdf", "application/pdf"},
        };
        for (String[] entry : table) MIME.put(entry[0], entry[1]);
    }

    private final File gameRoot;
    private final File bundleRoot;
    private final WebGamePlugin.Runtime runtime;
    private final boolean allowNetwork;
    private final File entry;

    GameServer(File gameRoot, File bundleRoot, WebGamePlugin.Runtime runtime, String execFile, JSONObject options)
        throws IOException {
        this.gameRoot = gameRoot;
        this.bundleRoot = bundleRoot;
        this.runtime = runtime;
        this.allowNetwork = options.optBoolean("allowNetwork", false);
        this.entry = runtime.isFlash() ? resolveSwf(execFile) : resolvePage(execFile, options.optString("entryPoint", ""));
    }

    /** The URL the WebView opens first. */
    String entryUrl() {
        if (runtime.isFlash()) return ORIGIN + RUNTIME_PREFIX + "player.html";
        return ORIGIN + urlPath(entry);
    }

    /** Whether the page may navigate to [uri] at all. */
    boolean serves(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null || "about".equals(scheme) || "data".equals(scheme) || "blob".equals(scheme)) return true;
        if ("https".equals(scheme) && HOST.equals(uri.getHost())) return true;
        return allowNetwork && ("http".equals(scheme) || "https".equals(scheme));
    }

    /** The response for one request, or null to let the WebView fetch it itself (permitted network only). */
    WebResourceResponse respond(WebResourceRequest request) throws IOException {
        Uri uri = request.getUrl();
        if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost())) {
            if (serves(uri) && !"about".equals(uri.getScheme())) return null;
            return status(403, "Blocked");
        }
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        if (path.startsWith(RUNTIME_PREFIX)) return runtimeResource(path.substring(RUNTIME_PREFIX.length()), request);
        File file = confined(path);
        if (file == null) return status(403, "Outside the game folder");
        if (file.isDirectory()) file = new File(file, "index.html");
        if (!file.isFile()) {
            file = audioSibling(file);
            if (file == null) return status(404, "Not found");
        }
        String mime = mimeFor(file.getName());
        if ("text/html".equals(mime)) return document(file);
        return fileResponse(file, mime, request.getRequestHeaders().get("Range"));
    }

    private WebResourceResponse runtimeResource(String name, WebResourceRequest request) throws IOException {
        if ("player.html".equals(name) && runtime.isFlash()) {
            return WebGamePlugin.textResponse("text/html", playerPage());
        }
        if (name.startsWith("ruffle/") && !name.contains("..")) {
            File file = new File(bundleRoot, name);
            if (!file.isFile()) return status(404, "Not found");
            return fileResponse(file, mimeFor(file.getName()), request.getRequestHeaders().get("Range"));
        }
        return status(404, "Not found");
    }

    /** An HTML document with the storage script placed before anything of the page's own runs. */
    private WebResourceResponse document(File file) throws IOException {
        String html = new String(readAll(file), StandardCharsets.UTF_8);
        return WebGamePlugin.textResponse("text/html", inject(html));
    }

    static String inject(String html) {
        String tag = "<script>" + LocalStorageBridge.script() + "</script>";
        Matcher head = Pattern.compile("<head(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE).matcher(html);
        if (head.find()) return html.substring(0, head.end()) + tag + html.substring(head.end());
        Matcher root = Pattern.compile("<html(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE).matcher(html);
        if (root.find()) return html.substring(0, root.end()) + tag + html.substring(root.end());
        return tag + html;
    }

    /**
     * The page that hosts Ruffle. Ruffle is served from the bundle under the
     * reserved path, so its script and WebAssembly are same-origin with the
     * SWF it plays; Flash local shared objects go through localStorage and
     * so into the same save file as everything else.
     */
    private String playerPage() {
        String swfUrl = JSONObject.quote(ORIGIN + urlPath(entry));
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}"
            + "ruffle-player{display:block;width:100%;height:100%}</style>"
            + "<script>" + LocalStorageBridge.script() + "</script>"
            + "<script src=\"" + RUNTIME_PREFIX + "ruffle/ruffle.js\"></script></head><body>"
            + "<script>window.RufflePlayer=window.RufflePlayer||{};"
            + "window.RufflePlayer.config={publicPath:\"" + RUNTIME_PREFIX + "ruffle/\",autoplay:\"on\","
            + "unmuteOverlay:\"hidden\",letterbox:\"on\",splashScreen:false,contextMenu:\"off\","
            + "warnOnUnsupportedContent:false,scale:\"showAll\",allowScriptAccess:true};"
            + "window.addEventListener(\"load\",function(){var player=window.RufflePlayer.newest().createPlayer();"
            + "document.body.appendChild(player);player.load({url:" + swfUrl + "});});</script></body></html>";
    }

    // ------------------------------------------------------------ resolution

    private File resolvePage(String execFile, String entryPoint) throws IOException {
        if (execFile != null && !execFile.isBlank()) return confinedFile(execFile);
        if (!entryPoint.isBlank()) return confinedFile(entryPoint);
        String[] candidates = runtime == WebGamePlugin.Runtime.HTML
            ? new String[] {"index.html", "index.htm"}
            : new String[] {"www/index.html", "index.html"};
        for (String candidate : candidates) {
            File file = new File(gameRoot, candidate);
            if (file.isFile()) return file.getCanonicalFile();
        }
        File[] pages = gameRoot.listFiles((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".html"));
        if (pages != null && pages.length > 0) {
            Arrays.sort(pages);
            return pages[0].getCanonicalFile();
        }
        throw new IOException("No HTML page found at the game folder's root; set execFile to the page to open");
    }

    private File resolveSwf(String execFile) throws IOException {
        if (execFile != null && !execFile.isBlank()) return confinedFile(execFile);
        File descriptor = new File(gameRoot, "META-INF/AIR/application.xml");
        if (descriptor.isFile()) {
            Matcher matcher = AIR_CONTENT.matcher(new String(readAll(descriptor), StandardCharsets.UTF_8));
            if (matcher.find()) {
                String content = matcher.group(1).replace('\\', '/');
                File swf = new File(gameRoot, content);
                if (swf.isFile()) return swf.getCanonicalFile();
                // Some AIR packages name a padding file as content and keep
                // the real SWF beside the descriptor; fall through to the scan.
            }
        }
        File[] files = gameRoot.listFiles((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".swf"));
        if (files != null && files.length == 1) return files[0].getCanonicalFile();
        throw new IOException("Set execFile to the application's main SWF");
    }

    private File confinedFile(String relative) throws IOException {
        if (new File(relative).isAbsolute()) throw new IOException("The entry file must be relative to the game folder");
        File file = new File(gameRoot, relative).getCanonicalFile();
        if (!file.getPath().startsWith(gameRoot.getPath() + File.separator) || !file.isFile()) {
            throw new IOException("The entry file " + relative + " is not inside the game folder");
        }
        return file;
    }

    /** The file a URL path names, or null when it would leave the game folder. */
    private File confined(String path) {
        StringBuilder relative = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) continue;
            String decoded = Uri.decode(segment);
            if (decoded.equals(".") || decoded.equals("..") || decoded.contains("/") || decoded.contains("\\")) return null;
            if (relative.length() > 0) relative.append(File.separatorChar);
            relative.append(decoded);
        }
        File file = new File(gameRoot, relative.toString());
        try {
            File canonical = file.getCanonicalFile();
            String prefix = gameRoot.getPath() + File.separator;
            if (!canonical.equals(gameRoot) && !canonical.getPath().startsWith(prefix)) return null;
            return canonical;
        } catch (IOException error) {
            return null;
        }
    }

    private String urlPath(File file) {
        String relative = file.getPath().substring(gameRoot.getPath().length()).replace(File.separatorChar, '/');
        StringBuilder encoded = new StringBuilder();
        for (String segment : relative.split("/")) {
            if (segment.isEmpty()) continue;
            encoded.append('/').append(Uri.encode(segment));
        }
        return encoded.toString();
    }

    private static File audioSibling(File wanted) {
        String name = wanted.getName();
        String sibling;
        if (name.endsWith(".m4a")) sibling = name.substring(0, name.length() - 4) + ".ogg";
        else if (name.endsWith(".ogg")) sibling = name.substring(0, name.length() - 4) + ".m4a";
        else return null;
        File file = new File(wanted.getParentFile(), sibling);
        return file.isFile() ? file : null;
    }

    // -------------------------------------------------------------- responses

    static String mimeFor(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MIME.get(extension);
        return mime == null ? "application/octet-stream" : mime;
    }

    static WebResourceResponse status(int code, String reason) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/plain", "UTF-8", code, reason, headers,
            new ByteArrayInputStream(reason.getBytes(StandardCharsets.UTF_8)));
    }

    /** A whole file, or the byte range a media element asked for. */
    private static WebResourceResponse fileResponse(File file, String mime, String range) throws IOException {
        long length = file.length();
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Access-Control-Allow-Origin", ORIGIN);
        Matcher matcher = range == null ? null : RANGE.matcher(range.trim());
        if (matcher != null && matcher.matches() && length > 0) {
            long start = matcher.group(1).isEmpty() ? -1 : Long.parseLong(matcher.group(1));
            long end = matcher.group(2).isEmpty() ? length - 1 : Long.parseLong(matcher.group(2));
            if (start < 0) { // suffix range: the last N bytes
                start = Math.max(0, length - (end + 1));
                end = length - 1;
            }
            end = Math.min(end, length - 1);
            if (start > end) {
                headers.put("Content-Range", "bytes */" + length);
                return new WebResourceResponse(mime, null, 416, "Range Not Satisfiable", headers,
                    new ByteArrayInputStream(new byte[0]));
            }
            FileInputStream input = new FileInputStream(file);
            skipFully(input, start);
            headers.put("Content-Range", "bytes " + start + "-" + end + "/" + length);
            headers.put("Content-Length", Long.toString(end - start + 1));
            return new WebResourceResponse(mime, null, 206, "Partial Content", headers,
                new BoundedInputStream(input, end - start + 1));
        }
        headers.put("Content-Length", Long.toString(length));
        return new WebResourceResponse(mime, null, 200, "OK", headers, new FileInputStream(file));
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() < 0) break;
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static byte[] readAll(File file) throws IOException {
        long length = file.length();
        if (length > 64L * 1024 * 1024) throw new IOException(file.getName() + " is too large to read as a document");
        byte[] bytes = new byte[(int) length];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
        }
        return bytes;
    }

    /** Reads at most [remaining] bytes of the wrapped stream. */
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream input, long limit) {
            super(input);
            remaining = limit;
        }

        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = super.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int count = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }
}
