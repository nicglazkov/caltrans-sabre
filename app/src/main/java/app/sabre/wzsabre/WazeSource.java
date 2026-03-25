package app.sabre.wzsabre;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fetches Waze crowdsourced alerts via the Waze live-map georss API.
 *
 * Strategy (mirrors wzsabre 1.8 WWWClient / WazeLiveMapQuery):
 *   1. GET https://www.waze.com/login/get to establish a session and receive cookies.
 *   2. GET the georss API URL with those cookies and Referer: https://www.waze.com/live-map.
 *
 * Uses java.net.CookieManager so cookies from step 1 are automatically sent in step 2.
 */
public class WazeSource {
    private static final String TAG = "WazeSource";

    // Matches wzsabre 1.8 default user-agent (desktop Mac Chrome)
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_0_9; en-US) " +
            "AppleWebKit/537.13 (KHTML, like Gecko) Chrome/48.0.2867.225 Safari/534";

    private static final String INIT_URL   = "https://www.waze.com/login/get";
    private static final String REFERER    = "https://www.waze.com/live-map";

    private final CookieManager cookieManager;

    public WazeSource(Context context) {
        cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        java.net.CookieHandler.setDefault(cookieManager);
    }

    public List<SabreAlert> fetchAlerts(double centerLat, double centerLon, double radiusMeters) {
        List<SabreAlert> results = new ArrayList<>();
        try {
            // Step 1: init session / get cookies
            initSession();

            // Step 2: build bounding-box georss URL
            double delta    = radiusMeters / 111_320.0;
            double lonDelta = delta / Math.cos(Math.toRadians(centerLat));
            String georssUrl = String.format(Locale.US,
                    "https://www.waze.com/live-map/api/georss" +
                    "?top_right_lat=%.6f&top_right_lon=%.6f" +
                    "&bottom_left_lat=%.6f&bottom_left_lon=%.6f" +
                    "&env=row&types=alerts,traffic",
                    centerLat + delta, centerLon + lonDelta,
                    centerLat - delta, centerLon - lonDelta);

            Log.d(TAG, "Fetching georss: " + georssUrl);

            // Step 3: fetch georss with session cookies
            String json = httpGet(georssUrl, REFERER);
            if (json != null && json.startsWith("{")) {
                results = parseAlerts(json);
                Log.d(TAG, "Waze: " + results.size() + " alerts parsed");
            } else {
                int len = json == null ? 0 : Math.min(200, json.length());
                Log.w(TAG, "Waze: unexpected response: " +
                        (json == null ? "null" : json.substring(0, len)));
            }
        } catch (Exception e) {
            Log.w(TAG, "Waze fetch failed: " + e.getMessage());
        }
        return results;
    }

    private void initSession() {
        try {
            httpGet(INIT_URL, null);
            Log.d(TAG, "Waze session init done, cookies: " +
                    cookieManager.getCookieStore().getCookies().size());
        } catch (Exception e) {
            Log.w(TAG, "Waze session init failed: " + e.getMessage());
        }
    }

    private String httpGet(String urlStr, String referer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json, */*");
            if (referer != null) {
                conn.setRequestProperty("Referer", referer);
            }
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            Log.d(TAG, "GET " + urlStr + " → HTTP " + code);
            if (code != 200) return null;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private List<SabreAlert> parseAlerts(String json) throws Exception {
        List<SabreAlert> alerts = new ArrayList<>();
        JSONObject root = new JSONObject(json);

        if (!root.has("alerts")) return alerts;
        JSONArray wazeAlerts = root.getJSONArray("alerts");

        for (int i = 0; i < wazeAlerts.length(); i++) {
            JSONObject wa = wazeAlerts.getJSONObject(i);

            String type    = wa.optString("type", "");
            String subtype = wa.optString("subtype", "");
            String sabreType = AlertMapper.fromWazeType(type, subtype);
            if (sabreType == null) continue;

            JSONObject loc = wa.optJSONObject("location");
            if (loc == null) continue;
            double lon = loc.optDouble("x", 0);
            double lat = loc.optDouble("y", 0);
            if (lat == 0 && lon == 0) continue;

            String id     = wa.optString("uuid", wa.optString("id", "waze_" + i));
            String street = wa.optString("street", wa.optString("city", "Unknown"));
            long ts       = wa.optLong("pubMillis", System.currentTimeMillis()) / 1000;
            double heading = wa.optDouble("magvar", 0);

            alerts.add(new SabreAlert(
                    "waze_" + id, "Waze",
                    sabreType,
                    lat, lon, heading,
                    street, ts
            ));
        }
        return alerts;
    }
}
