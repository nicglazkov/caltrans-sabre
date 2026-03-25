package app.sabre.wzsabre;

import android.net.Network;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

/**
 * Fetches and parses the CHP statewide live incident XML feed.
 * Filters to alerts within radiusMeters of the given center point.
 */
public class CHPSource {
    private static final String TAG = "CHPSource";
    private static final String CHP_URL = "https://media.chp.ca.gov/sa_xml/sa.xml";
    private static final int TIMEOUT_MS = 12000;

    public List<SabreAlert> fetchAlerts(double centerLat, double centerLon, double radiusMeters) {
        return fetchAlerts(centerLat, centerLon, radiusMeters, null);
    }

    public List<SabreAlert> fetchAlerts(double centerLat, double centerLon, double radiusMeters,
                                         Network network) {
        List<SabreAlert> results = new ArrayList<>();
        try {
            String xml = fetchXml(network);
            results = parseXml(xml, centerLat, centerLon, radiusMeters);
            Log.d(TAG, "CHP: " + results.size() + " alerts within radius");
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch CHP data: " + e.getMessage());
        }
        return results;
    }

    private String fetchXml(Network network) throws Exception {
        URL url = new URL(CHP_URL);
        HttpsURLConnection conn = network != null
                ? (HttpsURLConnection) network.openConnection(url)
                : (HttpsURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private List<SabreAlert> parseXml(String xml, double centerLat, double centerLon,
                                       double radiusMeters) throws Exception {
        List<SabreAlert> alerts = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(xml));

        // State for the current <Log> being parsed
        String logId = null, logTime = null, logType = null;
        String location = null, area = null, latlon = null;
        String currentTag = null;

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case XmlPullParser.START_TAG:
                    currentTag = parser.getName();
                    if ("Log".equals(currentTag)) {
                        // Reset state for new Log entry
                        logId = parser.getAttributeValue(null, "ID");
                        logTime = null; logType = null;
                        location = null; area = null; latlon = null;
                    }
                    break;

                case XmlPullParser.TEXT:
                    String text = cleanValue(parser.getText());
                    if (text.isEmpty()) break;
                    if ("LogTime".equals(currentTag))     logTime = text;
                    else if ("LogType".equals(currentTag))     logType = text;
                    else if ("Location".equals(currentTag))    location = text;
                    else if ("Area".equals(currentTag))        area = text;
                    else if ("LATLON".equals(currentTag))      latlon = text;
                    break;

                case XmlPullParser.END_TAG:
                    if ("Log".equals(parser.getName()) && logId != null && latlon != null) {
                        SabreAlert alert = buildAlert(logId, logType, location, area,
                                latlon, centerLat, centerLon, radiusMeters);
                        if (alert != null) alerts.add(alert);
                    }
                    if ("Log".equals(parser.getName())) currentTag = null;
                    break;
            }
            eventType = parser.next();
        }
        return alerts;
    }

    private SabreAlert buildAlert(String logId, String logType, String location,
                                   String area, String latlon,
                                   double centerLat, double centerLon, double radiusMeters) {
        // Skip if no GPS fix
        if (latlon == null || latlon.equals("0:0") || latlon.startsWith("0:")) return null;

        double[] coords;
        try {
            coords = parseLatLon(latlon);
        } catch (Exception e) {
            return null;
        }
        double lat = coords[0];
        double lon = coords[1];

        // Skip obviously invalid coordinates
        if (lat == 0.0 && lon == 0.0) return null;

        // Distance filter
        if (haversineMeters(centerLat, centerLon, lat, lon) > radiusMeters) return null;

        // Map to SABRE type — null means we skip this incident type
        String sabreType = AlertMapper.fromChpLogType(logType);
        if (sabreType == null) return null;

        String streetName = buildStreetName(location, area);
        long reportTs = System.currentTimeMillis() / 1000;

        return new SabreAlert(
                "chp_" + logId,
                "CHP",
                sabreType,
                lat, lon,
                0.0,
                streetName,
                reportTs
        );
    }

    /** "37721302:122169832"  →  [37.721302, -122.169832] */
    private static double[] parseLatLon(String latlon) {
        String[] parts = latlon.split(":");
        double lat = Long.parseLong(parts[0].trim()) / 1_000_000.0;
        double lon = -(Long.parseLong(parts[1].trim()) / 1_000_000.0);
        return new double[]{lat, lon};
    }

    /** Strip surrounding quotes that CHP wraps values in */
    private static String cleanValue(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("^\"|\"$", "").trim();
    }

    private static String buildStreetName(String location, String area) {
        if (location != null && !location.isEmpty() && area != null && !area.isEmpty())
            return location + " (" + area + ")";
        if (location != null && !location.isEmpty()) return location;
        if (area != null && !area.isEmpty()) return area;
        return "Unknown";
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
