package app.sabre.wzsabre;

import android.app.Application;

/**
 * Records the last uncaught exception (see {@link CrashLog}) before delegating to
 * the platform's default handler, so the settings diagnostics panel can show it.
 */
public class App extends Application {

    /** Wall-clock time this process started, for the diagnostics "app uptime" line. */
    public static volatile long PROCESS_START_MS = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        PROCESS_START_MS = System.currentTimeMillis();
        DebugLog.event("app process started");
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            CrashLog.record(this, throwable);
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }
}
