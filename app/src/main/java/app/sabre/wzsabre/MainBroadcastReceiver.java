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
     * Starts SabreService from a BroadcastReceiver context.
     *
     * Key insight from Android 15 logcat analysis:
     *   startForegroundService() → DENIED (uidBFSL: n/a, tempAllowListReason: null)
     *   WorkManager startForegroundService() → also DENIED (uidState: TRNB)
     *
     * The BFSL (Background Foreground Service Launch) restriction only applies to
     * startForegroundService(). Plain startService() is NOT subject to BFSL checks and
     * CAN be called from a BroadcastReceiver (RCVR state gives a temporary service-start
     * allowlist). SabreService then promotes itself to foreground by calling startForeground()
     * from within its own onCreate(), which is not subject to BFSL checks either.
     *
     * WorkManager is kept as final fallback in case even startService() fails.
     */
    private void startSabreService(Context context, String action, String data) {
        Intent svc = new Intent(context, SabreService.class);
        if (action != null) svc.putExtra("action", action);
        if (data   != null) svc.putExtra("data",   data);

        try {
            context.startService(svc);
            Log.d(TAG, "startService succeeded for action: " + action);
        } catch (Exception e) {
            Log.w(TAG, "startService failed, trying WorkManager: " + e.getMessage());
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
            Log.e(TAG, "WorkManager fallback also failed: " + e.getMessage());
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

        // Pre-warm SabreService so it is already initialized when the first
        // FETCH_REQUEST arrives. Without this, a cold-start service has to
        // initialize OkHttp, WebViewInterceptor, etc. while HR is already
        // waiting for a response, causing the "plugin not responding" error.
        startSabreService(context, null, null);
    }
}
