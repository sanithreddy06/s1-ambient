# S1 Ambient

S1 Ambient turns an older Android tablet into a lightweight ambient display with a
clock, weather, tasks, timers, stopwatch, and alarms. A bundled web remote lets you
control the display from a phone or computer on the same local Wi-Fi network.

**Current version: v0.4.0.** LAN control and device-based weather have been physically
tested by the owner. Broader compatibility and long-term reliability remain under evaluation.

## ✨ Features

- Large ambient clock with the current date and day
- Device-based weather with a human-readable location name
- Persistent tasks with due times, completion, and overdue status
- Timer with start, pause, resume, reset, and stop
- Stopwatch with start/resume, pause, and reset
- Daily alarms and custom alarm sounds
- LAN web remote with local pairing
- Offline-friendly core functions and cached weather
- Automatic day/night appearance
- Low-resource native UI for older Android hardware

## 📥 Download

Download the latest prebuilt APK from
[GitHub Releases](https://github.com/sanithreddy06/s1-ambient/releases).

APK files are published as release artifacts and are not committed to the source
repository. You can also build the app locally using the instructions below.

## 🚀 Quick Start

1. Download an APK from Releases when available, or build it locally.
2. Install it on an Android tablet running **Android 9 / API 28 or later**.
3. Grant **approximate foreground location** permission for local weather.
4. Connect the tablet and phone/computer to the same trusted Wi-Fi.
5. Enable **Local Wi-Fi remote** in **S1 / Settings**.
6. Open the displayed local URL on the other device and enter the pairing code.

Update over the existing installation with a matching signing key to retain data.
Uninstalling or clearing app data removes saved tasks, settings, and imported audio.

## 🌐 Remote Control

The tablet serves a local web dashboard on port **8080** by default:
`http://<tablet-IP>:8080`. The IP address, configurable port, and pairing code appear
in the app's settings. Both devices must be on a network that allows communication
between Wi-Fi clients.

Manage tasks, the timer, stopwatch, and daily alarms, or dismiss a ringing alert.
Both interfaces share the same local state. The browser remembers its access token;
**Reset pairing** on the tablet revokes existing remote access.

## 🛠️ Development

- **Language/UI:** Kotlin and native Android framework views
- **Minimum Android:** Android 9 / API 28
- **Compile/target SDK:** 35; Build Tools 35.0.0
- **Java:** JDK 17
- **Build:** Included Gradle wrapper

Open the project in Android Studio and configure your Android SDK and JDK 17.
For command-line builds, set `JAVA_HOME` and configure your SDK path locally.

```sh
./gradlew assembleDebug
./gradlew lintDebug
```

On Windows, use `gradlew.bat`. Install and run the debug build through Android Studio
or Android SDK platform-tools.

| Build | APK output path |
| --- | --- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` |
| Release | `app/build/outputs/apk/release/app-release.apk` |

To build a release APK, run `./gradlew assembleRelease`. Release distribution
requires a signing key; keep signing keys and credentials out of Git.

## 🌤️ Weather & Location

[Open-Meteo](https://open-meteo.com/en/docs) provides weather using device-derived
coordinates. Approximate foreground location permission is used only to determine
local weather. The app requests **no background location** and does **not continuously track** location.

A recent platform fix or a bounded one-shot request supplies coordinates. Saved
location is reused, with refresh attempts roughly every **six hours while visible**.
Weather refreshes every **20 minutes**; failures do not trigger rapid retries.

Reverse geocoding provides a human-readable place name. If it fails, the previous
name is retained, or **Current location** is shown. Last successful weather and
location data are cached locally for offline use. If permission is denied or location
is unavailable, saved data is reused; without a saved location, weather remains
unavailable while the rest of the dashboard works. Permission can be enabled later
in Android's app settings.

## 🔒 Privacy & Security

- No accounts, cloud backend, analytics, or advertising.
- Tasks, alarms, settings, and imported sounds are stored locally.
- Location is used for weather and sent only to the weather and device
  positioning/geocoding services required for that purpose.
- The local HTTP remote is **unencrypted**. Use trusted Wi-Fi only.
- **Never expose the remote server to the public internet.**
- Never commit credentials, signing keys, local configuration, or device-data exports.

Pairing restricts control access but does not encrypt network traffic.

## ⚠️ Limitations

- Primarily designed/tested around a **1280 × 800 landscape** display; compatibility
  varies on older Android hardware. Long-term reliability remains under evaluation.
- New approximate fixes require a working network-location provider.
- LAN control requires the same network and a private IPv4 Wi-Fi address; HTTP is unencrypted.
- Android force-stop prevents scheduled alerts until reopening the app. Android 12+
  requires **Alarms & reminders** access; speaker volume and system settings affect alerts.
- After reboot, a timer recovers from its saved deadline; the stopwatch pauses at its
  last saved snapshot. Device idle policies may delay closely spaced timers.
- Custom audio is imported on the tablet, not uploaded through the remote.
  Daily alarms missed while powered off are not replayed.

## 🗺️ Roadmap

- [ ] More device compatibility testing
- [ ] Native phone remote application
- [ ] More scheduling options
- [ ] Optional sensor integrations
- [ ] Further UI refinement

## 📄 License

S1 Ambient is licensed under the [MIT License](LICENSE).
