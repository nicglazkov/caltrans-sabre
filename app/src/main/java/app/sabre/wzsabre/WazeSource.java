package app.sabre.wzsabre;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches Waze crowdsourced alerts, mirroring the wzsabre 1.8 approach exactly:
 *
 *  1. WebViewInterceptor loads https://www.waze.com/ in a hidden WebView and
 *     waits for Waze's own JS to fire a /live-map/api/georss request.  The
 *     session cookies captured at that point are the ones Waze requires.
 *
 *  2. Those cookies are loaded into a java.net.CookieManager and passed to
 *     OkHttp via JavaNetCookieJar for all subsequent data requests.
 *
 *  3. The georss endpoint is queried with params:
 *       top, bottom, left, right  (bounding-box corners)
 *       types = alerts
 *       env   = region code ("na" for North America, "il" for Israel, "row" otherwise)
 *
 *  4. Adaptive tiling: if a query returns ≥ 150 alerts the bounding box is
 *     split in half and both halves are queried (sequentially), deduplicating
 *     by alert id — matching wzsabre 1.8's recursive queryServer logic.
 */
public class WazeSource {
    private static final String TAG = "WazeSource";

    // wzsabre 1.8 default user-agent (Windows Chrome 146)
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/146.0.0.0 Safari/537.36";

    private static final String INIT_URL      = "https://www.waze.com/";
    private static final String INTERCEPT_PATH = "/live-map/api/georss";
    private static final String GEORSS_URL    = "https://www.waze.com/live-map/api/georss";
    private static final String REFERER       = "https://www.waze.com/live-map";

    private static final int TILE_ALERT_THRESHOLD = 150; // matches wzsabre 1.8

    private final WebViewInterceptor webViewInterceptor;
    private final OkHttpClient       httpClient;
    /** Cookies captured from the last successful WebView intercept. */
    private volatile String          sessionCookies = "";

    public WazeSource(Context context) {
        webViewInterceptor = new WebViewInterceptor(context);
        httpClient = new OkHttpClient.Builder()
                .callTimeout(15L, TimeUnit.SECONDS)
                .build();
    }

    public List<SabreAlert> fetchAlerts(double centerLat, double centerLon,
                                         double radiusMeters) {
        try {
            // ── Step 1: obtain session cookies via WebView ────────────────
            String cookies = webViewInterceptor.extractCookies(
                    INIT_URL, INTERCEPT_PATH, USER_AGENT, 30_000L);

            if (cookies == null || cookies.isEmpty()) {
                Log.w(TAG, "No cookies obtained from WebView");
                return new ArrayList<>();
            }
            Log.d(TAG, "Cookies captured: " + cookies.length() + " chars");
            sessionCookies = cookies;

            // ── Step 3: build bounding box and query ─────────────────────
            double latDelta = radiusMeters / 111_320.0;
            double lonDelta = latDelta / Math.cos(Math.toRadians(centerLat));

            double top    = centerLat + latDelta;
            double bottom = centerLat - latDelta;
            double left   = centerLon - lonDelta;
            double right  = centerLon + lonDelta;

            String env = getRegion(centerLat, centerLon);

            Map<String, WazeAlertData> dedupMap = new LinkedHashMap<>();
            queryServerDedup(top, bottom, left, right, env, dedupMap);

            // ── Step 4: map to SabreAlert ─────────────────────────────────
            List<SabreAlert> results = new ArrayList<>();
            for (WazeAlertData wa : dedupMap.values()) {
                String sabreType = AlertMapper.fromWazeType(wa.type, wa.subtype);
                if (sabreType == null) continue;
                results.add(new SabreAlert(
                        "waze_" + wa.id, SabreResponseBuilder.SOURCE_WAZE,  // must match declared source id
                        sabreType,
                        wa.lat, wa.lon, wa.magvar,
                        wa.street, wa.pubMillis / 1000L));
            }

            Log.d(TAG, "Waze: " + results.size() + " alerts mapped from " +
                    dedupMap.size() + " raw");
            return results;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Waze fetch interrupted");
            return new ArrayList<>();
        } catch (Exception e) {
            Log.w(TAG, "Waze fetch failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ── Adaptive tiling (mirrors wzsabre 1.8 queryServer recursion) ──────────

    private void queryServerDedup(double top, double bottom, double left, double right,
                                   String env, Map<String, WazeAlertData> out)
            throws Exception {
        List<WazeAlertData> alerts = queryServer(top, bottom, left, right, env);
        if (alerts == null) return;

        double area = Math.abs((top - bottom) * (right - left));
        if (alerts.size() < TILE_ALERT_THRESHOLD || area <= 1e-4) {
            // Base case: below threshold or tiny tile — just accept results
            for (WazeAlertData a : alerts) out.put(a.id, a);
            return;
        }

        // Split the tile and recurse into each half (sequential, same thread)
        Log.d(TAG, "Tiling: " + alerts.size() + " alerts, splitting bounds");
        double height = top - bottom;
        double width  = right - left;
        if (height > width) {
            // split horizontally
            double mid = (top + bottom) / 2.0;
            queryServerDedup(top,    mid,    left, right, env, out);
            queryServerDedup(mid,    bottom, left, right, env, out);
        } else {
            // split vertically
            double mid = (left + right) / 2.0;
            queryServerDedup(top, bottom, left,  mid,   env, out);
            queryServerDedup(top, bottom, mid,   right, env, out);
        }
    }

    private List<WazeAlertData> queryServer(double top, double bottom,
                                             double left, double right,
                                             String env)
            throws Exception {
        HttpUrl url = HttpUrl.parse(GEORSS_URL).newBuilder()
                .addQueryParameter("top",    String.valueOf(top))
                .addQueryParameter("bottom", String.valueOf(bottom))
                .addQueryParameter("left",   String.valueOf(left))
                .addQueryParameter("right",  String.valueOf(right))
                .addQueryParameter("types",  "alerts")
                .addQueryParameter("env",    env)
                .build();

        Log.d(TAG, "GET " + url);

        Request.Builder rb = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer",    REFERER);
        if (!sessionCookies.isEmpty()) rb.header("Cookie", sessionCookies);
        Request request = rb.build();

        try (Response response = httpClient.newCall(request).execute()) {
            int code = response.code();
            Log.d(TAG, "HTTP " + code);
            if (code != 200) {
                Log.w(TAG, "Unexpected HTTP " + code);
                return new ArrayList<>();
            }
            ResponseBody body = response.body();
            if (body == null) return new ArrayList<>();
            String json = body.string();
            return parseAlerts(json);
        }
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    private List<WazeAlertData> parseAlerts(String json) throws Exception {
        List<WazeAlertData> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return list;

        JSONObject root       = new JSONObject(json);
        JSONArray  wazeAlerts = root.optJSONArray("alerts");
        if (wazeAlerts == null) return list;

        for (int i = 0; i < wazeAlerts.length(); i++) {
            JSONObject wa = wazeAlerts.getJSONObject(i);

            JSONObject loc = wa.optJSONObject("location");
            if (loc == null) continue;
            double lon = loc.optDouble("x", Double.NaN);
            double lat = loc.optDouble("y", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;

            WazeAlertData d = new WazeAlertData();
            d.type     = wa.optString("type",    "");
            d.subtype  = wa.optString("subtype", "");
            d.lat      = lat;
            d.lon      = lon;
            d.magvar   = wa.optInt   ("magvar",   0);
            d.pubMillis = wa.optLong ("pubMillis", System.currentTimeMillis());
            d.street   = wa.optString("street",  null);

            // Prefer uuid, fall back to id
            String uuid = wa.optString("uuid", "");
            String id   = wa.optString("id",   "");
            d.id        = !uuid.isEmpty() ? uuid : (!id.isEmpty() ? id : "waze_" + i);

            list.add(d);
        }
        return list;
    }

    // ── Region detection (matches wzsabre 1.8 RegionEstimator defaults) ───────

    /**
     * Returns "na" for North America, "il" for Israel, "row" elsewhere.
     * Uses simple bounding-box checks that cover the same territory as the
     * polygon-based RegionEstimator in wzsabre 1.8.
     */
    private static String getRegion(double lat, double lon) {
        // North America: roughly covers continental US, Canada, Alaska, Hawaii, Caribbean
        if (lon >= -170.0 && lon <= -52.0 && lat >= -15.0 && lat <= 73.0) return "na";
        // Also includes Guam / western Pacific outliers wzsabre maps to "na"
        if (lon >= 144.0 && lon <= 146.5 && lat >= 12.0 && lat <= 21.0)  return "na";
        // Israel
        if (lon >= 34.0 && lon <= 36.0 && lat >= 29.5 && lat <= 33.5)   return "il";
        return "row";
    }

    // ── Internal data holder ──────────────────────────────────────────────────

    private static final class WazeAlertData {
        String type, subtype, id, street;
        double lat, lon, magvar;
        long   pubMillis;
    }
}
