# NC Camera

A Kotlin/Jetpack Compose Android app that connects directly to a V380 camera on the local network.

The home screen lists **Mini camera** at **192.168.0.111**. Tap the card or **Watch live** to open the live-view screen. On first use, enter the device username and camera password inside the app. The device ID is discovered automatically, with a manual field in Camera settings if discovery is blocked. Allow local network access if Android asks.

The live screen includes video, camera audio, mute/unmute, reconnect, camera settings, and back navigation. Leaving the screen or putting the app in the background closes the live connection. A remembered password is encrypted using Android Keystore. No microphone permission is requested for listening to the camera.

## Run

Open this project in Android Studio and run the `app` configuration on an Android phone, or install the generated debug APK. Keep the phone and camera on the same Wi-Fi. Camera settings lets you edit the name, address, device ID, and older-camera compatibility mode.

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. A convenient copy is delivered in `artifacts/NC-Camera-debug.apk` after verification. The project currently uses Android SDK 37 and a Java 25 Gradle daemon, matching the existing project configuration.

## Verification

Twelve JVM tests cover fragmented responses, simulated plain/encrypted live sessions, the camera's JPEG/IMA packet layout, frame bounds/order, codec vectors, and credential redaction. Four Android emulator tests cover navigation/mute/back, H.264 and JPEG rendering, audio output, and encrypted password persistence. The app builds and Android lint runs without errors; lint retains warnings including the camera protocol's required ECB cipher and existing template/dependency recommendations.

The owner's camera has authenticated successfully from the phone. Its encrypted JPEG video (`0x09`, 640×480) decoded successfully, and its encrypted IMA WAV audio blocks (`0x16`) were received. The complete physical-phone live screen and intelligible microphone sound still need a check with the phone unlocked.

## Save your login privately

Open **Camera login**, enter your device credentials locally, and enable **Remember on this phone**. Future connections use the saved login. Each phone/emulator keeps its own password encrypted with Android Keystore; it is not embedded in the APK or source code. App updates preserve it. Uninstalling the app, clearing its data, or changing the camera address/device ID/compatibility clears it. Emulator tests use separate settings and keys so they preserve your camera login.

For repeated emulator tests, update both APKs with `adb install -r` and invoke the instrumentation runner. A test workflow that uninstalls the app (including Gradle's connected-test cleanup) removes saved credentials regardless of test isolation. Do not uninstall or wipe the emulator between runs if you want the login retained.

The decoder fixtures are test-only; the app never substitutes them for a camera feed. Unsupported firmware/audio formats produce an explicit status or error. See the verification limits above before treating physical display/sound or SD support as complete.

See [implementation notes](docs/IMPLEMENTATION.md), [HLD](docs/HLD.md), [device profile](docs/DEVICE_PROFILE.md), and the [working vendor-app comparison](docs/V99_APP_ANALYSIS.md). The comparison identifies remaining login/audio/frame-metadata gaps and recovers direct-LAN SD search/replay paths. SD browsing/copying and live recording remain later HLD milestones; this implementation delivers the requested camera-list/live-view flow.
