package app.sabre.wzsabre;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TextView tv = findViewById(R.id.statusText);
        tv.setText(
            "CHP + Waze SABRE Plugin\n\n" +
            "Package: app.sabre.wzsabre\n\n" +
            "Data sources:\n" +
            "  \u2022 CHP live incident feed\n" +
            "  \u2022 Waze crowdsourced alerts\n\n" +
            "Service running in background.\n" +
            "Open Highway Radar and select this plugin\n" +
            "under Settings \u2192 SABRE."
        );
        // Start the foreground service so it has network access for FETCH_REQUEST handling
        Intent serviceIntent = new Intent(this, SabreService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
}
