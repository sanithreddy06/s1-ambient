# S1 Ambient

A lightweight native Android dashboard that turns an older Android tablet into an
ambient display with a clock, weather, tasks, timers, stopwatch, and alarms.
A bundled phone-friendly web remote controls the same app over local Wi-Fi.

**Status:** Milestone 3 is published and has been tested by the owner over a local
network. This source includes a device-based weather update that still needs
physical-device validation; long-term reliability has not been established.

## ✨ Features

- Large ambient clock with current date and day
- Device-based weather with a human-readable location name
- Persistent tasks with due times, completion, and overdue status
- Timer with start, pause, resume, reset, and stop
- Stopwatch with start/resume, pause, and reset
- Daily alarms and custom alarm sounds
- LAN web remote with local pairing
- Offline-friendly core functions and cached weather
- Automatic day/night appearance
- Low-resource native UI for older Android hardware

## 📸 Screenshots

| Main dashboard | Phone remote | Night mode |
| :---: | :---: | :---: |
| _Coming soon_ | _Coming soon_ | _Coming soon_ |

## 📥 Download

Prebuilt Android APKs will be provided through
[GitHub Releases](https://github.com/sanithreddy06/s1-ambient/releases).
No release APK is currently published. When available, download the latest APK
from that page; APKs are release artifacts rather than files committed to source.
Until then, build the project using the instructions below.

## 🚀 Quick Start

1. Download an APK from Releases when available, or build it locally.
2. Install it on an Android tablet running Android 9 or later.
3. Grant approximate location permission for automatic local weather.
4. Connect the tablet and phone/computer to the same Wi-Fi.
5. Enable **Local Wi-Fi remote** in **S1 / Settings**.
6. Open the displayed local URL on the other device and enter the pairing code.

Install updates over the existing app with a matching signing key to retain data.
Uninstalling or clearing app data removes saved tasks, settings, and imported audio.

## 🌐 Remote Control

The tablet serves a local web dashboard at `http://<tablet-IP>:8080` by default.
Both devices must share a Wi-Fi network that allows communication between clients.
The port can be changed in the app's settings.

Pair using the code shown on the tablet to manage tasks, the timer, stopwatch,
and daily alarms, or dismiss an alert. The browser remembers its token;
**Reset pairing** on the tablet revokes existing phone access.
Native controls and the remote share the same application state and local storage.

## 🛠️ Development

- **Language/UI:** Kotlin and native Android framework views
- **Minimum Android:** Android 9 / API 28
- **Compile/target SDK:** 35; Build Tools 35.0.0
- **Java:** JDK 17
- **Build:** Included Gradle wrapper

Open the project in Android Studio and configure the Android SDK, or set `JAVA_HOME`
to JDK 17 and create an ignored `local.properties` with your own `sdk.dir` path.

```sh
./gradlew assembleDebug
```

On Windows, use `gradlew.bat assembleDebug`. To run lint, add `lintDebug`.
The development APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
Install it with Android Studio's Run action, or:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.s1ambient/.MainActivity
```

No cloud SDK, UI framework, or server framework is required.

## 🌤️ Weather & Location

Weather uses [Open-Meteo](https://open-meteo.com/en/docs) with coordinates obtained
from the Android device. Approximate foreground location permission is used only
to determine local weather; precise GPS and background location are not requested.

The app uses a recent platform fix or a bounded one-shot network-location request.
Saved location is reused, with refresh attempts roughly every six hours while
visible. It does not continuously track location. Weather refreshes every
20 minutes, with no rapid retries on failure.

Android reverse geocoding supplies a short place name when available. If it fails,
the previous name is retained, or **Current location** is shown.
Last successful weather and location data are stored locally for offline use.
If permission is denied or positioning is unavailable, saved data remains usable;
without a saved location, weather stays unavailable while the dashboard works.
Permission can be enabled later in Android's app settings.

## 🔒 Privacy & Security

- No accounts, cloud backend, analytics, or location-tracking service.
- Task/alarm data and imported sounds stay on the tablet.
- Location is used for weather and sent only to Open-Meteo and the device's
  positioning/geocoding services as required; it is not shared with the LAN remote.
- Use the remote only on trusted networks. Local HTTP is **unencrypted**.
- **Never expose the remote server to the public internet.**

Pairing protects control access but does not encrypt network traffic.
Keep credentials, signing keys, local configuration, and device-data exports out of Git.

## ⚠️ Limitations

- Primarily designed/tested around a **1280 × 800 landscape** display.
- Compatibility and positioning support vary on older Android hardware; a working
  network-location provider is needed to acquire a new approximate fix.
- LAN control requires the same network and a private IPv4 Wi-Fi address.
- HTTP is unencrypted. Long-term reliability testing remains ongoing.
- Android force-stop prevents scheduled alerts until the app is reopened.
  Android 12+ requires **Alarms & reminders** access; check alarm volume before use.
- After reboot, a timer recovers from its saved deadline; the stopwatch pauses at
  its last saved snapshot. Device idle policies may delay closely spaced timers.
- Custom audio is imported on the tablet; the remote can select an imported sound
  but cannot upload one. Daily alarms missed while powered off are not replayed.

## 🗺️ Roadmap

- [ ] More device compatibility testing
- [ ] Native phone remote application
- [ ] More scheduling options
- [ ] Optional sensor integrations
- [ ] Further UI refinement

## 📄 License

A project license has not yet been selected. Add a `LICENSE` file before distributing
this project under an open-source license. Third-party components retain their licenses.
