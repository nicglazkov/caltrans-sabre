package app.sabre.wzsabre;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads a URL in a hidden WebView and waits for the page's own JavaScript to
 * make a request matching targetPath with HTTP 200 + JSON body.  When that
 * fires the session cookies held by android.webkit.CookieManager are captured
 * and returned to the caller.
 *
 * Mirrors the WebViewInterceptor / JsBridge logic in wzsabre 1.8.
 */
public class WebViewInterceptor {
    private static final String TAG          = "WVInterceptor";
    private static final String BRIDGE_NAME  = "NativeBridge";

    private final Context context;
    private final Handler mainHandler;

    public WebViewInterceptor(Context context) {
        this.context     = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Blocks the calling thread until cookies are captured or the timeout
     * elapses.  Must NOT be called on the main thread.
     *
     * @return cookie string ("k=v; k2=v2; …") or null on failure/timeout.
     */
    public String extractCookies(String initUrl, String targetPath,
                                  String userAgent, long timeoutMs)
            throws InterruptedException {
        CountDownLatch              latch       = new CountDownLatch(1);
        AtomicReference<String>    result      = new AtomicReference<>(null);
        AtomicReference<WebView>   webViewRef  = new AtomicReference<>(null);

        mainHandler.post(() -> {
            try {
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);

                WebView wv = new WebView(context);
                wv.getSettings().setJavaScriptEnabled(true);
                wv.getSettings().setDomStorageEnabled(true);
                if (userAgent != null) wv.getSettings().setUserAgentString(userAgent);
                cm.setAcceptThirdPartyCookies(wv, true);
                webViewRef.set(wv);

                JsBridge bridge = new JsBridge(targetPath, cm, cookies -> {
                    result.set(cookies);
                    latch.countDown();
                    mainHandler.post(() -> destroyWebView(webViewRef.get()));
                });

                wv.addJavascriptInterface(bridge, BRIDGE_NAME);
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageStarted(WebView view, String url, Bitmap favicon) {
                        view.evaluateJavascript(buildInterceptorScript(), null);
                    }

                    @Override
                    public void onReceivedError(WebView view,
                                                WebResourceRequest request,
                                                WebResourceError error) {
                        if (request != null && request.isForMainFrame()) {
                            Log.e(TAG, "WebView main-frame error: " +
                                    (error != null ? error.getDescription() : "?"));
                            latch.countDown();
                            mainHandler.post(() -> destroyWebView(webViewRef.get()));
                        }
                    }
                });

                wv.loadUrl(initUrl);
                Log.i(TAG, "Loading " + initUrl + ", intercepting " + targetPath);
            } catch (Exception e) {
                Log.e(TAG, "WebView setup failed", e);
                latch.countDown();
            }
        });

        boolean done = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        if (!done) Log.w(TAG, "Timed out after " + timeoutMs + " ms");

        // Guarantee cleanup even on timeout
        mainHandler.post(() -> destroyWebView(webViewRef.get()));
        return result.get();
    }

    private void destroyWebView(WebView wv) {
        if (wv == null) return;
        try { wv.stopLoading(); } catch (Exception ignored) {}
        wv.removeJavascriptInterface(BRIDGE_NAME);
        wv.destroy();
        Log.d(TAG, "WebView destroyed");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Callback interface
    // ────────────────────────────────────────────────────────────────────────

    interface CookieCallback { void onCookies(String cookies); }

    // ────────────────────────────────────────────────────────────────────────
    // JS bridge exposed to the WebView page as "NativeBridge"
    // ────────────────────────────────────────────────────────────────────────

    static final class JsBridge {
        private final String         targetPath;
        private final CookieManager  cookieManager;
        private final CookieCallback callback;
        private volatile boolean     captured = false;

        JsBridge(String targetPath, CookieManager cookieManager, CookieCallback callback) {
            this.targetPath    = targetPath;
            this.cookieManager = cookieManager;
            this.callback      = callback;
        }

        @JavascriptInterface
        public void onTraffic(String json) {
            if (captured) return;
            try {
                JSONObject o           = new JSONObject(json);
                String     url         = o.optString("url",         "");
                int        status      = o.optInt   ("status",       0);
                String     contentType = o.optString("contentType",  "");
                String     body        = o.optString("body",         "").trim();

                if (!shouldCapture(url, status, contentType, body)) return;

                captured = true;
                String cookies = cookieManager.getCookie(url);
                Log.d(TAG, "Intercepted " + url +
                        " — " + (cookies != null ? cookies.length() : 0) + " chars of cookies");
                callback.onCookies(cookies != null ? cookies : "");
            } catch (Exception e) {
                Log.w(TAG, "Traffic parse error", e);
            }
        }

        private boolean shouldCapture(String url, int status,
                                       String contentType, String body) {
            try {
                if (!targetPath.equals(URI.create(url).getPath())) return false;
                if (status != 200) return false;
                if (!contentType.toLowerCase(Locale.ROOT).contains("application/json"))
                    return false;
                return body.startsWith("{") || body.startsWith("[");
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // JS interceptor script — hooks XHR and fetch, calls NativeBridge.onTraffic() on each response
    // ────────────────────────────────────────────────────────────────────────

    private String buildInterceptorScript() {
        return "(function() {\n" +
               "    window.requestAnimationFrame = function(cb) { return 0; };\n" +
               "    window.cancelAnimationFrame = function(id) {};\n" +
               "    \n" +
               "    if (window.__networkInterceptorInstalled) return;\n" +
               "    window.__networkInterceptorInstalled = true;\n" +
               "\n" +
               "    function logTraffic(payload) {\n" +
               "        try {\n" +
               "            NativeBridge.onTraffic(JSON.stringify(payload));\n" +
               "        } catch (e) {}\n" +
               "    }\n" +
               "\n" +
               "    function headersToString(headers) {\n" +
               "        try {\n" +
               "            if (headers && typeof headers.entries === 'function') {\n" +
               "                var parts = [];\n" +
               "                headers.forEach(function(val, key) {\n" +
               "                    parts.push(key + ': ' + val);\n" +
               "                });\n" +
               "                return parts.join('; ');\n" +
               "            }\n" +
               "        } catch(e) {}\n" +
               "        return '';\n" +
               "    }\n" +
               "\n" +
               "    /* ---- XHR ---- */\n" +
               "    var origOpen = XMLHttpRequest.prototype.open;\n" +
               "    var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;\n" +
               "    var origSend = XMLHttpRequest.prototype.send;\n" +
               "\n" +
               "    XMLHttpRequest.prototype.open = function(method, url) {\n" +
               "        this.__method = method || 'GET';\n" +
               "        this.__url = url;\n" +
               "        this.__reqHeaders = {};\n" +
               "        return origOpen.apply(this, arguments);\n" +
               "    };\n" +
               "\n" +
               "    XMLHttpRequest.prototype.setRequestHeader = function(name, value) {\n" +
               "        if (this.__reqHeaders) {\n" +
               "            this.__reqHeaders[name] = value;\n" +
               "        }\n" +
               "        return origSetHeader.apply(this, arguments);\n" +
               "    };\n" +
               "\n" +
               "    XMLHttpRequest.prototype.send = function() {\n" +
               "        var self = this;\n" +
               "\n" +
               "        this.addEventListener('load', function() {\n" +
               "            var finalURL = self.responseURL || self.__url || '';\n" +
               "            var status = self.status || 0;\n" +
               "            var ct = self.getResponseHeader('Content-Type') || '';\n" +
               "            var text = '';\n" +
               "            try { text = self.responseText || ''; } catch(e) {}\n" +
               "            if (!text) {\n" +
               "                try {\n" +
               "                    if (typeof self.response === 'string') text = self.response;\n" +
               "                    else if (self.response && typeof self.response === 'object')\n" +
               "                        text = JSON.stringify(self.response);\n" +
               "                } catch(e) {}\n" +
               "            }\n" +
               "\n" +
               "            var reqH = '';\n" +
               "            try {\n" +
               "                var parts = [];\n" +
               "                for (var k in self.__reqHeaders) {\n" +
               "                    parts.push(k + ': ' + self.__reqHeaders[k]);\n" +
               "                }\n" +
               "                reqH = parts.join('; ');\n" +
               "            } catch(e) {}\n" +
               "\n" +
               "            var respH = '';\n" +
               "            try {\n" +
               "                respH = (self.getAllResponseHeaders() || '')\n" +
               "                    .replace(/\\r?\\n/g, '; ')\n" +
               "                    .replace(/;\\s*$/, '');\n" +
               "            } catch(e) {}\n" +
               "\n" +
               "            logTraffic({\n" +
               "                transport: 'xhr',\n" +
               "                method: self.__method,\n" +
               "                url: finalURL,\n" +
               "                status: status,\n" +
               "                contentType: ct,\n" +
               "                requestHeaders: reqH,\n" +
               "                responseHeaders: respH,\n" +
               "                body: text\n" +
               "            });\n" +
               "        });\n" +
               "\n" +
               "        return origSend.apply(this, arguments);\n" +
               "    };\n" +
               "\n" +
               "    /* ---- Fetch ---- */\n" +
               "    var origFetch = window.fetch;\n" +
               "\n" +
               "    window.fetch = function(input, init) {\n" +
               "        var method = (init && init.method) || 'GET';\n" +
               "\n" +
               "        var reqH = '';\n" +
               "        try {\n" +
               "            if (init && init.headers) {\n" +
               "                if (init.headers instanceof Headers) {\n" +
               "                    reqH = headersToString(init.headers);\n" +
               "                } else if (typeof init.headers === 'object') {\n" +
               "                    var parts = [];\n" +
               "                    for (var k in init.headers) {\n" +
               "                        parts.push(k + ': ' + init.headers[k]);\n" +
               "                    }\n" +
               "                    reqH = parts.join('; ');\n" +
               "                }\n" +
               "            }\n" +
               "        } catch(e) {}\n" +
               "\n" +
               "        return origFetch.apply(this, arguments).then(function(response) {\n" +
               "            var reqURL = typeof input === 'string' ? input\n" +
               "                : (input && input.url) ? input.url : response.url;\n" +
               "            var finalURL = response.url || reqURL;\n" +
               "            var status = response.status || 0;\n" +
               "            var ct = response.headers.get('content-type') || '';\n" +
               "\n" +
               "            var respH = headersToString(response.headers);\n" +
               "\n" +
               "            response.clone().text().then(function(text) {\n" +
               "                logTraffic({\n" +
               "                    transport: 'fetch',\n" +
               "                    method: method,\n" +
               "                    url: finalURL,\n" +
               "                    status: status,\n" +
               "                    contentType: ct,\n" +
               "                    requestHeaders: reqH,\n" +
               "                    responseHeaders: respH,\n" +
               "                    body: text\n" +
               "                });\n" +
               "            }).catch(function(err) {\n" +
               "                console.log('fetch clone failed', err);\n" +
               "            });\n" +
               "\n" +
               "            return response;\n" +
               "        });\n" +
               "    };\n" +
               "})();";
    }
}
