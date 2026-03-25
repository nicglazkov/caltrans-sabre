package app.sabre.wzsabre;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "SABREProxy";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Received action: " + action);
        try {
            if ("app.sabre.HANDSHAKE".equals(action) || "app.sabre.wzsabre.HANDSHAKE".equals(action)) {
                handleHandshake(context, intent);
            } else if ("app.sabre.wzsabre.FETCH_REQUEST".equals(action)) {
                startSabreService(context, "FETCH_REQUEST", intent.getStringExtra("data"));
            } else if (action != null && action.contains("SHUTDOWN")) {
                // HR sends SHUTDOWN when ending a session but immediately starts a new one.
                // Keep the service running so the next FETCH_REQUEST can be handled.
                Log.d(TAG, "Shutdown received — keeping service alive for next session");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling broadcast", e);
        }
    }

    /**
     * Starts SabreService as a foreground service.
     * Must use startForegroundService() on API 26+ — plain startService() causes the process
     * to not have an active FGS, which allows Android 15's app freezer to freeze it.
     * A frozen process silently drops incoming broadcasts (FETCH_REQUEST), causing the
     * "Crowd-Sourced Alert Problems" state in HR.
     *
     * This is only called from FETCH_REQUEST (sent by HR while it's in the foreground), which
     * gives us a temp allowlist that permits the foreground service start.
     */
    /**
     * Starts SabreService, mirroring wzsabre 1.8's ForegroundServiceStarter:
     *  1. Try startForegroundService() — works when we have a temp allowlist
     *     (e.g., FETCH_REQUEST sent by HR while it's in the foreground).
     *  2. Fall back to WorkManager expedited task — bypasses Android 12-15 background
     *     FGS restrictions.  This is the key fix for cold-start "Crowd-Sourced Alert
     *     Problems": our process may be frozen, but WorkManager can wake it.
     */
    private void startSabreService(Context context, String action, String data) {
        Intent svc = new Intent(context, SabreService.class);
        if (action != null) svc.putExtra("action", action);
        if (data   != null) svc.putExtra("data",   data);

        boolean started = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.startForegroundService(svc);
                started = true;
            } catch (Exception e) {
                Log.w(TAG, "startForegroundService denied, falling back to WorkManager: " + e.getMessage());
            }
        } else {
            try {
                context.startService(svc);
                started = true;
            } catch (Exception e) {
                Log.w(TAG, "startService failed: " + e.getMessage());
            }
        }

        if (!started) {
            startViaWorkManager(context, action, data);
        }
    }

    private void startViaWorkManager(Context context, String action, String data) {
        try {
            Data.Builder inputData = new Data.Builder();
            if (action != null) inputData.putString(ServiceStartWorker.KEY_ACTION, action);
            if (data   != null) inputData.putString(ServiceStartWorker.KEY_DATA,   data);

            OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ServiceStartWorker.class)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(inputData.build())
                    .build();

            WorkManager.getInstance(context).enqueue(work);
            Log.d(TAG, "Enqueued WorkManager task for action: " + action);
        } catch (Exception e) {
            Log.e(TAG, "WorkManager fallback failed: " + e.getMessage());
        }
    }

    private void handleHandshake(Context context, Intent intent) throws Exception {
        Log.d(TAG, "Handling handshake");
        String rawData = intent.getStringExtra("data");
        String responseAction = null;
        if (rawData != null) {
            try {
                JSONObject req = new JSONObject(rawData);
                responseAction = req.optString("response_action", null);
                Log.d(TAG, "Discovery request: " + rawData);
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse discovery request JSON: " + e.getMessage());
            }
        }
        if (responseAction == null)
            responseAction = intent.getStringExtra("response_action");
        if (responseAction == null) {
            Log.e(TAG, "No response_action in handshake — cannot respond");
            return;
        }
        String pkg = context.getPackageName();
        JSONObject response = new JSONObject();
        response.put("id",           pkg);
        response.put("name",         "CHP + Waze SABRE");
        response.put("package_name", pkg);
        response.put("version",      "1.0");
        JSONArray sources = new JSONArray();
        JSONObject s1 = new JSONObject(); s1.put("id", "chp");  s1.put("name", "CHP Live Feed"); sources.put(s1);
        JSONObject s2 = new JSONObject(); s2.put("id", "waze"); s2.put("name", "Waze");          sources.put(s2);
        response.put("supported_sources", sources);
        response.put("request_action",  "app.sabre.wzsabre.FETCH_REQUEST");
        response.put("report_action",   "app.sabre.wzsabre.SUBMIT_REPORT");
        response.put("confirm_action",  "app.sabre.wzsabre.CONFIRM_REPORT");
        response.put("discard_action",  "app.sabre.wzsabre.DISCARD_REPORT");
        response.put("shutdown_action", "app.sabre.wzsabre.SHUTDOWN");
        Intent resp = new Intent(responseAction);
        resp.putExtra("data", response.toString());
        context.sendBroadcast(resp);
        Log.d(TAG, "Handshake response sent to: " + responseAction + " data: " + response);
    }
}
