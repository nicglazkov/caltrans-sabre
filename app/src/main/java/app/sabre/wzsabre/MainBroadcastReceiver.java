package app.sabre.wzsabre;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
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
                // Relay to SabreService which runs as a foreground service and has network access.
                // startService() delivers a new intent to the ALREADY-RUNNING service without
                // triggering the ForegroundServiceStartNotAllowedException.
                Intent svc = new Intent(context, SabreService.class);
                svc.putExtra("action", "FETCH_REQUEST");
                svc.putExtra("data", intent.getStringExtra("data"));
                context.startService(svc);
            } else if (action != null && action.contains("SHUTDOWN")) {
                // HR sends SHUTDOWN when ending a session but immediately starts a new one.
                // Keep the service running so the next FETCH_REQUEST can be handled.
                Log.d(TAG, "Shutdown received — keeping service alive for next session");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling broadcast", e);
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
