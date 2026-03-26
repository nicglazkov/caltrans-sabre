# Building from Source

## Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Ladybug (2024.2) or newer |
| Android SDK | API 35 |
| JDK | 11 (bundled with Android Studio) |

No NDK or additional toolchains needed.

## Making a release

### One-time setup: create your signing keystore

The keystore is what lets users upgrade the app without reinstalling. **Keep it backed up and private — if you lose it you can't release updates.**

```bash
# Generate keystore (run once; keep the output passwords somewhere safe)
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
"$JAVA_HOME/bin/keytool" \
  -genkey -v \
  -keystore app/caltrans-sabre.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias caltrans-sabre \
  -dname "CN=caltrans-sabre, O=, L=CA, ST=California, C=US" \
  -storepass YOUR_PASSWORD \
  -keypass YOUR_PASSWORD
```

Then create `keystore.properties` at the project root (this file is gitignored):

```properties
storeFile=caltrans-sabre.keystore
storePassword=YOUR_PASSWORD
keyAlias=caltrans-sabre
keyPassword=YOUR_PASSWORD
```

### Build the signed release APK

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

Verify it's properly signed:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" PATH="$JAVA_HOME/bin:$PATH" \
  "$ANDROID_SDK/build-tools/35.0.0/apksigner" verify --verbose app/build/outputs/apk/release/app-release.apk
# Should show: Verified using v1 scheme: true, Verified using v2 scheme: true
```

### Publish the GitHub Release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`
2. Commit, push, and tag:
   ```bash
   git tag v1.x
   git push origin v1.x
   ```
3. Go to **github.com/nicglazkov/caltrans-sabre/releases → Draft a new release**
4. Choose the tag, add release notes, and attach `app-release.apk` (rename to `caltrans-sabre-v1.x.apk` for clarity)

Or with the [GitHub CLI](https://cli.github.com/) (`gh`):
```bash
gh release create v1.x \
  "app/build/outputs/apk/release/app-release.apk#caltrans-sabre-v1.x.apk" \
  --title "v1.x" \
  --generate-notes
```

## Clone and build

```bash
git clone https://github.com/nicglazkov/caltrans-sabre.git
cd caltrans-sabre
```

**Debug APK** (for sideloading during development):
```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr" ./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

On Windows (Git Bash):
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

**Install directly to a connected device:**
```bash
./gradlew installDebug
```

## Running tests

```bash
./gradlew test
```

All tests are JVM unit tests (no emulator required). Results land in `app/build/reports/tests/`.

Current test suites:

| Suite | Tests | What it covers |
|-------|-------|----------------|
| `SabreProtocolTest` | 84 | HR JSON schema — all 11 required alert fields, types, nullability |
| `AlertMapperTest` | 99 | Every CHP log type and Waze type maps to a valid SABRE type constant |
| `CHPSourceTest` | 42 | XML parsing, radius filter, coordinate parsing, haversine distance |
| `ChpConfigTest` | 96 | Category toggles, type overrides, age filter, LogTime parsing |

## Project structure

```
app/src/main/java/app/sabre/wzsabre/
├── MainActivity.java           # Settings UI (category toggles, age picker)
│
├── MainBroadcastReceiver.java  # Receives HR intents, starts SabreService
├── SabreService.java           # Foreground service; orchestrates CHP + Waze fetches
├── ServiceStartWorker.java     # WorkManager fallback for service start
│
├── CHPSource.java              # CHP XML fetch + parse + filter
├── WazeSource.java             # Waze georss fetch (WebView cookies + OkHttp)
├── WebViewInterceptor.java     # Harvests Waze session cookies from a hidden WebView
│
├── AlertMapper.java            # CHP log type → SABRE type; Waze type → SABRE type
├── ChpCategory.java            # Enum of 6 CHP alert categories
├── ChpConfig.java              # User config (SharedPreferences), resolves final type
├── SabreResponseBuilder.java   # Builds the HR response JSON; enforces schema
├── SabreAlert.java             # Internal alert model
└── SabreService.java           # (see above)
```

## Key architecture decisions

### Package ID = `app.sabre.wzsabre`
Highway Radar's SABRE discovery whitelists this package ID. Keeping the same ID means HR finds this plugin without requiring any HR-side changes.

### SABRE protocol — `SabreFetchResponseAlert` schema
HR uses `kotlinx.serialization` with a bitmask that requires **all 11 fields** to be present on every alert object. Missing any field throws `MissingFieldException` in HR and crashes the crowdsourced-alert layer. `SabreResponseBuilder.buildAlert()` enforces this and rejects NaN coordinates and overflowing `report_ts` at build time.

The 11 fields: `alert_source`, `alert_id`, `user_id`, `type`, `lat`, `lon`, `heading_deg`, `street_name` (nullable), `report_ts` (Int, not Long), `confirm_ts` (nullable Int), `confirm_count`.

### Waze session cookies
Waze's georss API requires valid browser session cookies. A plain HTTP request returns 403. The fix (matching wzsabre 1.8 exactly) is to load `https://www.waze.com/` in a hidden `WebView`, inject a JavaScript XHR/fetch interceptor, wait for Waze's own page JS to fire a request to `/live-map/api/georss`, capture the cookies from `android.webkit.CookieManager`, then use those cookies for our own OkHttp data requests.

### Android 15 foreground service start restriction
The `startForegroundService()` call is blocked by Android 15's BFSL (Background Foreground Service Launch) restriction when `uidBFSL: n/a`. Using plain `startService()` bypasses this check — the service then promotes itself to foreground via `startForeground()` from within `onCreate()`, which is not subject to BFSL. `WorkManager` with an expedited task is kept as a final fallback.

### Config hot-reload
`SabreService` calls `ChpConfig.load(context)` on every `FETCH_REQUEST`, so changes made in the settings UI take effect on the next HR map refresh without restarting the service.

## ADB shortcuts

```bash
# Install and launch
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.sabre.wzsabre/.MainActivity

# Watch plugin logs
adb logcat -s SABREProxy SabreService SABREService WazeSource CHPSource WVInterceptor

# Simulate a FETCH_REQUEST (frozen-process caveat: use --receiver-foreground)
adb shell am broadcast --receiver-foreground \
  -a app.sabre.wzsabre.FETCH_REQUEST \
  -p app.sabre.wzsabre \
  --es data '{"request_id":"t1","response_action":"com.test.RESPONSE","lat":34.0522,"lon":-118.2437,"radius_m":5000}'
```
