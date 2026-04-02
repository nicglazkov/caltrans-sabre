package app.sabre.wzsabre;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Starts SabreService automatically after the device boots.
 *
 * Without this, users must open the app manually after every reboot before
 * Highway Radar can reach the plugin. BOOT_COMPLETED is an explicit exemption
 * from Android's background FGS start restrictions, so startForegroundService()
 * is allowed here even on Android 12-15.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "SABREBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Log.d(TAG, "Boot completed — starting SabreService");
        Intent svc = new Intent(context, SabreService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service on boot: " + e.getMessage());
        }
    }
}
