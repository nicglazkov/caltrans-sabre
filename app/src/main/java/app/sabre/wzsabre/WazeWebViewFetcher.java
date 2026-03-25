package app.sabre.wzsabre;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches Waze georss JSON via an Android WebView.
 *
 * Strategy (mirrors wzsabre 1.8):
 *   1. Load https://www.waze.com/live-map in a headless WebView.
 *   2. In onPageFinished, inject a JavaScript interceptor that overrides
 *      XMLHttpRequest and fetch() to monitor every outgoing network call.
 *   3. After 3 s (giving the page's JS time to set up auth + make its first
 *      georss call), also trigger OUR specific georss fetch so we have the
 *      right bounding box.
 *   4. Whichever call returns a valid georss JSON first is used.
 *
 * The 3-second delay ensures the page's session cookies / CSRF tokens are in
 * place before our fetch runs; the interceptor catches whichever call lands
 * first.
 */
public class WazeWebViewFetcher {
    private static final String TAG = "WazeWebView";
    private static final int TIMEOUT_SECONDS = 10;
    private static final int FETCH_DELAY_MS  = 2000;   // wait for page JS to set up auth

    private final Context appContext;

    public WazeWebViewFetcher(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Blocks the calling thread up to TIMEOUT_SECONDS while a headless WebView
     * retrieves authenticated Waze georss JSON.
     *
     * @return georss JSON string, or null on timeout / error
     */
    public String fetchGeoRssJson(double lat, double lon, double radiusMeters) {
        double delta    = radiusMeters / 111_320.0;
        double lonDelta = delta / Math.cos(Math.toRadians(lat));
        final String georssUrl = String.format(Locale.US,
                "https://www.waze.com/live-map/api/georss" +
                "?top_right_lat=%.6f&top_right_lon=%.6f" +
                "&bottom_left_lat=%.6f&bottom_left_lon=%.6f" +
                "&env=row&types=alerts,traffic",
                lat + delta, lon + lonDelta,
                lat - delta, lon - lonDelta);

        Log.d(TAG, "Targeting: " + georssUrl);

        CountDownLatch latch       = new CountDownLatch(1);
        AtomicReference<String>  captured   = new AtomicReference<>(null);
        AtomicBoolean fired        = new AtomicBoolean(false);
        // Strong reference — prevents WebView + sandboxed renderer from being GC'd
        AtomicReference<WebView> webViewRef = new AtomicReference<>(null);

        new Handler(Looper.getMainLooper()).post(() -> {
            WebView webView;
            try {
                webView = new WebView(appContext);
                webViewRef.set(webView);
            } catch (Exception e) {
                Log.e(TAG, "Failed to create WebView", e);
                latch.countDown();
                return;
            }

            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUserAgentString(
                    "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");

            // Native bridge — receives intercepted or direct fetch results
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void onCapture(String body) {
                    if (!fired.compareAndSet(false, true)) return;
                    if (body != null && body.startsWith("{")) {
                        Log.d(TAG, "Waze georss captured: " + body.length() + " bytes");
                        captured.set(body);
                    } else {
                        int len = body == null ? 0 : Math.min(120, body.length());
                        Log.w(TAG, "Waze georss invalid body: " +
                                (body == null ? "null" : body.substring(0, len)));
                    }
                    latch.countDown();
                    destroyOnMain(webViewRef);
                }

                /** Called by the XHR/fetch interceptor for ALL network calls */
                @JavascriptInterface
                public void onTraffic(String json) {
                    if (fired.get()) return;
                    try {
                        JSONObject obj  = new JSONObject(json);
                        String url      = obj.optString("url", "");
                        int    status   = obj.optInt("status", 0);
                        String body     = obj.optString("body", "");
                        if (url.contains("/live-map/api/georss") && status == 200
                                && body.startsWith("{")) {
                            Log.d(TAG, "Waze georss intercepted from page JS: "
                                    + body.length() + " bytes: "
                                    + body.substring(0, Math.min(200, body.length())));
                            if (fired.compareAndSet(false, true)) {
                                captured.set(body);
                                latch.countDown();
                                destroyOnMain(webViewRef);
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "onTraffic parse error: " + e.getMessage());
                    }
                }
            }, "NativeBridge");

            Handler mainHandler = new Handler(Looper.getMainLooper());

            webView.setWebViewClient(new WebViewClient() {
                private boolean interceptorInjected = false;
                private boolean fetchTriggered = false;

                @Override
                public void onPageStarted(WebView view, String url,
                                           android.graphics.Bitmap favicon) {
                    // Inject interceptor as early as possible — before page JS runs.
                    // evaluateJavascript may silently no-op if the JS engine isn't
                    // ready yet, so we also re-inject in onPageFinished as a fallback.
                    if (!interceptorInjected && url.contains("waze.com")) {
                        view.evaluateJavascript(buildInterceptorScript(), null);
                    }
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (fired.get()) return;
                    if (!url.contains("waze.com")) return;

                    // Re-inject interceptor (catches race with onPageStarted)
                    if (!interceptorInjected) {
                        interceptorInjected = true;
                        view.evaluateJavascript(buildInterceptorScript(), null);
                        Log.d(TAG, "Interceptor injected, page: " + url);
                    }

                    // After a delay, trigger our own georss fetch as fallback.
                    // The 3 s gives the page's JS time to set up auth tokens.
                    if (!fetchTriggered) {
                        fetchTriggered = true;
                        mainHandler.postDelayed(() -> {
                            if (fired.get()) return;
                            Log.d(TAG, "Triggering direct georss fetch");
                            String escaped = georssUrl
                                    .replace("\\", "\\\\")
                                    .replace("'", "\\'");
                            String js =
                                "fetch('" + escaped + "'," +
                                "{credentials:'include'," +
                                " headers:{" +
                                "   'Accept':'application/json,*/*'," +
                                "   'Referer':'https://www.waze.com/live-map'" +
                                " }," +
                                " mode:'cors'})" +
                                ".then(function(r){return r.text();})" +
                                ".then(function(t){NativeBridge.onCapture(t);})" +
                                ".catch(function(e){NativeBridge.onCapture('');});";
                            view.evaluateJavascript(js, null);
                        }, FETCH_DELAY_MS);
                    }
                }

                @Override
                public void onReceivedError(WebView view, int errorCode,
                                             String description, String failingUrl) {
                    Log.w(TAG, "WebView error " + errorCode +
                            " (" + description + ") url=" + failingUrl);
                    if (failingUrl != null && failingUrl.contains("waze.com")
                            && fired.compareAndSet(false, true)) {
                        latch.countDown();
                        destroyOnMain(webViewRef);
                    }
                }
            });

            // Load with coordinates so the page targets our area
            String loadUrl = String.format(Locale.US,
                    "https://www.waze.com/live-map?zoom=13&lon=%.4f&lat=%.4f",
                    lon, lat);
            Log.d(TAG, "Loading: " + loadUrl);
            webView.loadUrl(loadUrl);
        });

        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Waze WebView fetch timed out after " + TIMEOUT_SECONDS + "s");
                destroyOnMain(webViewRef);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        webViewRef.set(null);
        return captured.get();
    }

    private static void destroyOnMain(AtomicReference<WebView> ref) {
        new Handler(Looper.getMainLooper()).post(() -> {
            WebView wv = ref.getAndSet(null);
            if (wv != null) { try { wv.destroy(); } catch (Exception ignore) {} }
        });
    }

    /**
     * JavaScript that overrides XMLHttpRequest and window.fetch to report every
     * network call back to NativeBridge.onTraffic().
     */
    private static String buildInterceptorScript() {
        return "(function(){" +
            // XHR interceptor
            "var origOpen=XMLHttpRequest.prototype.open;" +
            "var origSend=XMLHttpRequest.prototype.send;" +
            "XMLHttpRequest.prototype.open=function(m,u){" +
            "  this._wz_url=u;this._wz_method=m;" +
            "  return origOpen.apply(this,arguments);" +
            "};" +
            "XMLHttpRequest.prototype.send=function(b){" +
            "  var self=this;" +
            "  this.addEventListener('load',function(){" +
            "    try{NativeBridge.onTraffic(JSON.stringify({" +
            "      url:self._wz_url,method:self._wz_method," +
            "      status:self.status,body:self.responseText}));}" +
            "    catch(e){}" +
            "  });" +
            "  return origSend.apply(this,arguments);" +
            "};" +
            // fetch interceptor
            "var origFetch=window.fetch;" +
            "window.fetch=function(input,init){" +
            "  var u=(typeof input==='string')?input:(input&&input.url?input.url:'');" +
            "  return origFetch.apply(this,arguments).then(function(resp){" +
            "    resp.clone().text().then(function(body){" +
            "      try{NativeBridge.onTraffic(JSON.stringify({" +
            "        url:u,status:resp.status,body:body}));}" +
            "      catch(e){}" +
            "    });" +
            "    return resp;" +
            "  });" +
            "};" +
            "})();";
    }
}
