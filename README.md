# S1 Ambient

A lightweight native Android dashboard that turns an older Android tablet into an
ambient display with a clock, weather, local tasks, timers, stopwatch, and alarms.
A bundled phone-friendly web remote controls the same app over local Wi-Fi.

**Status:** Milestone 3 · version 0.3.0 · development paused. The owner has tested
the current implementation successfully over the local network. Earlier milestones
were also physically tested on the development tablet. This does not imply that every edge case,
Android version, or long-term reliability scenario has been verified.

## Screenshots

_Screenshots will be added here: the S1 Ambient dashboard, phone remote, and night mode._

## Features

- Large local clock, day/date, landscape fullscreen, and keep-awake while visible.
- Neutral charcoal daytime appearance and dim, warm night mode.
- MS Palya weather with cached offline readings.
- Persistent local tasks with due times, completion, overdue status, and deletion.
- Timer, stopwatch, and daily alarms controlled from the tablet or a phone browser.
- Local pairing, configurable HTTP port, and connection information on the tablet.
- Bundled alert chime and native file-picker import for custom alarm audio.

## Hardware and technology

S1 Ambient is designed for older Android tablets and low-powered Android hardware,
with a landscape display around **1280 × 800**, intended for a permanently powered
ambient display. Minimum supported OS is **Android 9 / API 28**; confirm the installed
OS on any additional device.

| Component | Technology |
|---|---|
| Android app | Kotlin, framework Activity, TextView, LinearLayout, native dialogs |
| Local persistence | Private SharedPreferences with JSON |
| LAN server | Java ServerSocket and a bounded request-worker pool |
| Phone remote | Bundled HTML, CSS, and JavaScript; no CDN |
| Weather | Open-Meteo over HTTPS using HttpsURLConnection |
| Timing / alerts | Elapsed realtime, AlarmManager, MediaPlayer |
| Build | JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.9.2, Kotlin 2.1.20 |

The project compiles and targets SDK 35. Its app runtime dependencies are limited
to Kotlin's standard library and the Android platform; it uses no Compose,
AndroidX, WebView-based dashboard, database framework, or server framework.

## Architecture and how it works

`AmbientApp` owns a single `AmbientState`. The native controls and remote API use
that shared state, and mutations are serialized on Android's main thread. Socket
handling, weather requests, and audio import run off-thread.

| Source | Responsibility |
|---|---|
| `MainActivity`, `TaskPanel`, `ControlPanel` | Dashboard and native controls |
| `AmbientState`, `TaskStore`, `TimeState` | Shared state, persistence, elapsed-time calculations |
| `AmbientService` | Foreground LAN availability and alert playback |
| `LocalHttpServer`, `RemoteApi`, `PairingGate` | HTTP, JSON commands, pairing and authentication |
| `AlarmScheduler` and its receivers | Scheduled delivery and restoration |
| `WeatherController`, `AmbientAppearance` | Weather cache and day/night appearance |
| `app/src/main/assets/remote/` | Phone web dashboard |

The phone opens `http://<tablet-IP>:8080`, pairs with the display, and sends JSON commands
to its local API. Both interfaces operate on the same stored tasks and alarms.
The phone refreshes state every five seconds while visible and immediately after
commands; timer/stopwatch digits update locally between refreshes. The tablet receives
state-change callbacks without reloading the Activity.

## Development setup and build

1. Install Android Studio, or Android SDK command-line tools, with **SDK Platform 35**
   and **Build Tools 35.0.0**. Use **JDK 17** for Gradle.
2. Clone/download this repository and open its root in Android Studio.
3. Let Android Studio configure your SDK path, or create an ignored `local.properties`
   containing `sdk.dir=/path/to/your/Android/sdk`. Use your own path.
4. Build with the included Gradle wrapper:

```sh
# macOS / Linux; JAVA_HOME should point to JDK 17
./gradlew assembleDebug lintDebug
```

On Windows, use `gradlew.bat assembleDebug lintDebug`. Initial dependency and SDK
setup requires internet access. Do not commit SDK downloads, local configuration,
or generated build files. The Gradle wrapper JAR is deliberately included so a
separate Gradle installation is unnecessary.

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

APKs are excluded from Git. Debug builds are for development; release signing
requires your own private keystore and signing setup. Keep those files outside
version control.

### Checks

Run `assembleDebug lintDebug` for compilation and Android lint. The dependency-free
Java checks in `checks/` cover weather/task logic, timing transitions, pairing
cooldowns, and the HTTP server on loopback. The existing `checks/run.sh` assumes a
local ignored `.tools/android-sdk` layout and defaults to a local JDK; override
`JAVA_HOME` if needed. It is a development-machine helper, not a portable SDK installer.

Previously recorded checks passed, with zero lint errors and two manifest warnings
for fixed landscape orientation and newer Android backup rules. The web UI was
also previewed at 320px and 390px widths using sample API data. No build or device
tests were rerun for this documentation-only repository preparation.

## Installation and running the app

Use Android Studio's **Run** action, or enable USB debugging and install with Android
SDK platform-tools on your PATH:

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.s1ambient/.MainActivity
```

Alternatively, copy the generated APK to the tablet and open it to install.
Install updates over the existing app with a matching signing key to retain data.
Uninstalling or clearing app data removes local tasks, settings, weather cache,
pairing credentials, and imported audio.

The app starts in landscape immersive fullscreen. Swipe from an edge to reveal
system navigation. It keeps the screen awake while visible; it is not a kiosk.
Day mode is 07:00–20:59, night mode 21:00–06:59, using the device's local time.

## Using the LAN remote

1. Connect the tablet and phone to the same trusted Wi-Fi.
2. On the tablet, open **S1 / Settings** and enable **Local Wi-Fi remote**.
3. Open the displayed remote URL on the phone. Default port: **8080**.
4. Enter the pairing code shown on the tablet. The browser remembers its access token.
5. Manage tasks, timer, stopwatch, alarms, and alert dismissal from the phone.

Settings allows ports **1024–65535**. If the IP or port changes, use the new URL;
a different browser origin may require pairing again. **Reset pairing** revokes
all existing phone tokens. **Forget this phone** removes that browser's saved token.

The server binds to a private IPv4 Wi-Fi address. Guest-network/client isolation,
a changed DHCP address, an occupied port, or an IPv6-only network can prevent
connection. Internet access is not required for local control.

The API provides `/api/status`, task and alarm CRUD, timer/stopwatch commands,
`/api/alerts/dismiss`, and sound selection. `/api/pair` exchanges a code for a token;
other API requests require `Authorization: Bearer <token>`.

## Weather

[Open-Meteo](https://open-meteo.com/en/docs) provides current temperature and mapped
weather conditions without an API key. Location is fixed to **MS Palya, Bengaluru**
(13.08166, 77.54823); no device location permission is requested.

Refresh attempts are spaced 20 minutes apart while the dashboard is resumed.
Successful readings persist across restarts. Failed requests retain the last known
reading, or show a subtle unavailable state when no cache exists.

## Tasks

Add a title and a due time for the current local date. Tasks persist in the original
local SharedPreferences store and are shared by both interfaces. Unfinished tasks
remain overdue after midnight; completed tasks remain visible until deleted.
Limits are 120 characters per title and 200 retained tasks.

## Timer and stopwatch

The timer supports setting 1–86400 seconds, start, pause, resume, reset, and stop.
Reset/stop restore the configured duration. Completion plays an alert and shows a
steady completion state. The stopwatch supports start/resume, pause, and reset;
reset clears and stops it.

Both calculate time from elapsed-realtime timestamps rather than incrementing
counters. State survives process restarts on the same boot. After reboot, a running
timer recovers from its saved wall-clock deadline; the stopwatch pauses at its last
saved snapshot. Same-boot wall-clock adjustments do not change elapsed timing.

## Alarms and custom sounds

Up to 32 daily local-time alarms can be created, enabled/disabled, or deleted.
Android AlarmManager schedules delivery independently of UI refresh. Receivers
restore scheduling after reboot, time/timezone changes, and app updates.

On Android 12+, grant **Alarms & reminders** from the app’s **S1 / Settings** menu. Android 13+ also asks
for notification permission. Check **Alarm volume** before relying on audio.
Dismiss ringing from the dashboard, phone remote, or notification action. Unattended
ringing stops after ten minutes; concurrent alerts share one ringing state.

A bundled chime works offline. **Import alarm sound…** opens Android's native audio
picker, copies a file up to 20 MB into private storage, and selects it for future
alerts. Unsupported playback falls back to the default chime. The phone can select
between the default and already imported sound; remote audio upload is not implemented.
Timer and alarm alerts use the selected sound.

## Offline behavior and performance

Clock, tasks, elapsed timing, and scheduled alarms work without internet. Losing
Wi-Fi interrupts phone access; local features continue, and the server rebinds when
Wi-Fi returns. Weather uses its last successful cached reading.

The clock updates its own text once per second while resumed. Date, theme, and task
rows change when needed; weather does not poll every second. A single foreground
service maintains LAN access and alert audio, using an ongoing low-priority Android
notification. With remote disabled it stops when no alert is ringing. There are no
continuous animations or background counter loops.

## Known limitations

- The design currently targets a **1280 × 800** landscape display; broader device
  compatibility and long-term reliability are not established by the reported LAN test.
- HTTP traffic is unencrypted; only private IPv4 Wi-Fi is supported.
- Android force-stop prevents scheduled work until the app is reopened. Device idle
  policies can delay closely spaced wakeup timers. Speaker, volume, and system audio
  settings affect alerts.
- Daily alarms missed while powered off are scheduled for their next occurrence.
  The app does not bypass a lock screen or implement permanent kiosk mode.
- Weather location is fixed, alarms repeat daily, and only one imported sound is kept.
- Reboot, prolonged offline operation, and all audio/device edge cases should be
  validated before relying on the display for critical reminders.

## Privacy and security

Tasks, alarms, timing state, settings, pairing credentials, and imported audio are
stored locally. The app has no accounts, cloud backend, analytics, or advertising.
Weather requests go to Open-Meteo using the fixed coordinates above.

Pairing codes and bearer tokens are generated on the device, not embedded in source.
The browser stores its token locally. Pairing attempts are rate limited; HTTP requests
validate Host/Origin, reject cross-site access, and apply request limits and a
restrictive Content Security Policy. These measures do not encrypt the LAN connection
or protect a token from a network observer. Use trusted Wi-Fi and do not forward the
port to the public internet.

The repository excludes local SDK paths, SDK/JDK downloads, build output, APKs,
logs, IDE metadata, signing keys, environment files, and device-data exports.
Runtime data and real pairing credentials must never be committed. Test token strings
in the Java checks are dummy fixtures, not usable credentials.

## Future roadmap

Development is paused at Milestone 3. Possible future work, with no committed scope
or schedule, includes broader device/reliability testing, more flexible scheduling,
an optional native phone remote using the existing API, and optional sensor integrations.
These are not current features.

## License

A project license has not yet been selected. Add a `LICENSE` file before distributing
this project under an open-source license. Third-party tools and components retain
their respective licenses.
