package app.sabre.wzsabre;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SabreService extends Service {
    private static final String TAG = "SABREService";
    private static final String CHANNEL_ID = "SabreServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    private ExecutorService executor;
    private CHPSource chpSource;
    private WazeSource wazeSource;

    @Override
    public void onCreate() {
        super.onCreate();
        executor   = Executors.newFixedThreadPool(4);
        chpSource  = new CHPSource();
        wazeSource = new WazeSource(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildForegroundNotification());
        Log.d(TAG, "Service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getStringExtra("action");
        String data   = intent.getStringExtra("data");
        if (action == null) return START_STICKY;
        if (action.contains("FETCH_REQUEST")) handleFetchRequest(data);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    // Hard deadline: respond to HR within this many ms regardless of Waze status.
    // HR shows "Crowd-Sourced Alert Problems" if we exceed its internal timeout.
    private static final long RESPONSE_BUDGET_MS = 8_000;

    private void handleFetchRequest(String data) {
        executor.submit(() -> {
            try {
                JSONObject req = new JSONObject(data);
                String requestId      = req.getString("request_id");
                String responseAction = req.getString("response_action");
                double lat    = req.has("lat")      ? req.getDouble("lat")      : req.getDouble("latitude");
                double lon    = req.has("lon")      ? req.getDouble("lon")      : req.getDouble("longitude");
                double radius = req.has("radius_m") ? req.getDouble("radius_m") : req.getDouble("radius");

                Log.d(TAG, String.format("Fetch: lat=%.4f lon=%.4f radius=%.0fm", lat, lon, radius));

                long deadline = System.currentTimeMillis() + RESPONSE_BUDGET_MS;

                // CHP and Waze run in parallel (4-thread pool leaves 2 threads free here)
                Future<List<SabreAlert>> chpFuture  = executor.submit(() -> chpSource.fetchAlerts(lat, lon, radius));
                Future<List<SabreAlert>> wazeFuture = executor.submit(() -> wazeSource.fetchAlerts(lat, lon, radius));

                List<SabreAlert> allAlerts = new ArrayList<>();

                // CHP is always fast (~0.5 s); give it up to 5 s just in case
                try {
                    allAlerts.addAll(chpFuture.get(5, TimeUnit.SECONDS));
                    Log.d(TAG, "CHP: " + allAlerts.size() + " alerts");
                } catch (TimeoutException e) {
                    Log.w(TAG, "CHP timed out");
                    chpFuture.cancel(true);
                }

                // Give Waze whatever budget remains; cancel if exceeded
                long wazeMs = deadline - System.currentTimeMillis();
                if (wazeMs > 500) {
                    try {
                        List<SabreAlert> wazeAlerts = wazeFuture.get(wazeMs, TimeUnit.MILLISECONDS);
                        allAlerts.addAll(wazeAlerts);
                        Log.d(TAG, "Waze: " + wazeAlerts.size() + " alerts");
                    } catch (TimeoutException e) {
                        Log.w(TAG, "Waze exceeded budget — sending without Waze data");
                        wazeFuture.cancel(true);
                    }
                } else {
                    Log.w(TAG, "No budget left for Waze");
                    wazeFuture.cancel(true);
                }

                Log.d(TAG, "Sending " + allAlerts.size() + " total alerts");
                sendFetchResponse(responseAction, requestId, allAlerts);
            } catch (Exception e) {
                Log.e(TAG, "Error handling fetch request", e);
            }
        });
    }

    private void sendFetchResponse(String responseAction, String requestId,
                                    List<SabreAlert> alerts) throws Exception {
        JSONObject response = new JSONObject();
        response.put("request_id", requestId);
        response.put("error_message", JSONObject.NULL);

        JSONObject responseData = new JSONObject();
        responseData.put("n_batches", 1);
        responseData.put("batch_id", 0);

        JSONArray alertsArray = new JSONArray();
        for (SabreAlert a : alerts) {
            JSONObject obj = new JSONObject();
            obj.put("alert_source",  a.alertSource);
            obj.put("alert_id",      a.alertId);
            obj.put("user_id",       extractUserId(a.alertId));  // required by HR's serializer
            obj.put("type",          a.type);
            obj.put("lat",           a.lat);
            obj.put("lon",           a.lon);
            obj.put("heading_deg",   a.headingDeg);
            obj.put("street_name",   a.streetName);
            obj.put("report_ts",     (int)(a.reportTs));
            obj.put("confirm_ts",    JSONObject.NULL);
            obj.put("confirm_count", 0);
            alertsArray.put(obj);
        }

        responseData.put("alerts", alertsArray);
        response.put("response", responseData);

        String responseJson = response.toString();
        Intent intent = new Intent(responseAction);
        intent.putExtra("data", responseJson);
        sendBroadcast(intent);
        Log.d(TAG, "Response sent to: " + responseAction);
        Log.d(TAG, "Response JSON: " + responseJson);
    }

    /**
     * Extracts user_id from an alert ID, matching wzsabre 1.8's USER_ID_REGEX = "alert-(\\d*)/.*"
     * Waze alert IDs from the georss API look like "alert-1234567890/abcdef".
     * Our alertId prefix is "waze_" so we strip that before matching.
     * Returns "0" if no numeric user ID is found (CHP alerts, anonymous Waze alerts).
     */
    private static final Pattern USER_ID_PATTERN = Pattern.compile("alert-(\\d*)/.*");

    private static String extractUserId(String alertId) {
        if (alertId == null) return "0";
        // Strip our internal prefix before matching
        String id = alertId.startsWith("waze_") ? alertId.substring(5) : alertId;
        Matcher m = USER_ID_PATTERN.matcher(id);
        if (m.find()) {
            String uid = m.group(1);
            return (uid != null && !uid.isEmpty()) ? uid : "0";
        }
        return "0";
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "CHP + Waze SABRE", NotificationManager.IMPORTANCE_LOW);
            ch.enableVibration(false);
            ch.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildForegroundNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setOngoing(true)
         .setSmallIcon(android.R.drawable.ic_menu_compass)
         .setContentTitle("CHP + Waze SABRE")
         .setContentText("Providing CHP and Waze alerts to Highway Radar")
         .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
}
