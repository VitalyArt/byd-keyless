# BYD Keyless

BYD Keyless turns an Android phone into an additional way to use a supported global BYD vehicle. After you connect it to your BYD account, you can use the phone to unlock, lock and control the car when you are nearby, without taking the physical key out of your pocket.

You can also set up automatic access: the app learns how close you normally need to be before the car should unlock or lock. The app shows the current car connection, gives you quick controls from a notification or home-screen widget, and guides you through the setup step by step. It is best thought of as a convenient experimental spare key, not as a replacement for the original key.

This project is unofficial and is not affiliated with or endorsed by BYD. It uses an unpublished protocol that may change without notice. Test only with a vehicle you own or are explicitly authorized to control, keep a working physical key nearby, and never treat the app as your only way to access or secure the vehicle.

The app is intended for global BYD vehicle versions that are supported by the relevant BYD AUTO / Watch API flow. It is not intended to imply support for Chinese-market variants that use a different service or protocol. Compatibility still depends on the vehicle, account region, BYD backend version and capabilities advertised by the vehicle. Redistribution rights for the bundled native libraries have not been independently verified; see [Third-party components](THIRD_PARTY.md) before redistributing them.

## In everyday use

1. Link the app with your BYD account using the QR code flow in BYD AUTO.
2. Select your car and check that manual unlock and lock work while you are next to it.
3. Use the buttons in the app, notification, widget or launcher shortcut whenever you need them.
4. Optionally teach the app where you want automatic unlock and lock to happen.

The app supports manual unlock, lock and trunk controls where your car makes those functions available. It also includes light and dark themes and English, Russian and Uzbek translations.

Commands are available only when the vehicle advertises the required capability and the key is valid. Experimental commands may be shown but remain disabled when their capability is not confirmed.

## Compatibility

The app supports Android 8.0 (API 26) and later on ARM64 devices (`arm64-v8a`). Only ARM64 native libraries are bundled, so x86/x86_64 emulators and other Android ABIs are not supported.

For a global BYD vehicle to work, all of the following may matter:

- the vehicle must be available through the BYD AUTO / Watch API flow used by the app;
- the account region and backend must support the required watch and vehicle operations;
- the vehicle must advertise the capabilities needed for a command;
- the current BYD server protocol and the app's unpublished protocol implementation must still match.

The app is designed for global BYD vehicles, but compatibility with every model, market, firmware version or future BYD backend release is not guaranteed. The vehicle profile selected during authorization determines which commands are available.

## Screenshots

The app has three main destinations: Vehicle, Auto access and Settings. Auto access learns your preferred opening and closing points from real measurements, so you do not need to estimate distances in metres.

| Vehicle, light | Auto access | Point calibration |
| --- | --- | --- |
| ![Vehicle, light](docs/screenshots/home-light-en.png) | ![Auto access](docs/screenshots/access-light-en.png) | ![Point calibration](docs/screenshots/measure-light-en.png) |

More interface captures and instructions for reproducing them without vehicle access are available in the [screenshot guide](docs/screenshots/README.md). The screenshots use an isolated preview package and fictional vehicle data. They demonstrate the interface, not a working vehicle connection.

## Install

When a release is available, install the signed ARM64 APK from [GitHub Releases](https://github.com/VitalyArt/byd-keyless/releases). Version tags publish new builds automatically after CI succeeds; see [Release setup and publishing](docs/RELEASING.md).

Until the first release, build the app from source as described below.

## Build from source

You need:

- JDK 17, selected through `JAVA_HOME` or Android Studio's Gradle JDK setting;
- Android SDK Platform 36, Build-Tools 34.0.0 and Platform-Tools;
- network access for the initial Gradle and dependency downloads.

Use your own installation directories. Do not commit machine-specific paths:

```bash
export JAVA_HOME="/path/to/jdk-17"
export ANDROID_HOME="/path/to/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Install the required SDK packages with Android Studio's SDK Manager or the Android command-line tools:

```bash
sdkmanager "platforms;android-36" "build-tools;34.0.0" "platform-tools"
```

Accept the Android SDK licenses when prompted. Android Studio can create an ignored `local.properties` containing `sdk.dir`; do not add a local `org.gradle.java.home` path to `gradle.properties`.

Run the checks and build with the committed Gradle Wrapper; a global Gradle installation is not required:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows, use the equivalent environment variables and `gradlew.bat`.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it on a connected ARM64 phone with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK uses Android's local debug signing key. No release signing credentials are included or required. The existing Gradle 8.6, AGP 8.3.2 and Kotlin 1.9.22 versions are intentionally unchanged; known SDK and tooling warnings are recorded in [Publication checks](docs/PUBLISHING.md).

## First-time setup

1. Install a release APK or a locally built debug APK on an ARM64 Android phone.
2. Create a QR code in the app, scan it in BYD AUTO, select your BYD vehicle and approve the watch sign-in.
3. Grant the requested Bluetooth / nearby-device, location and notification permissions, then start the key while the app is visible.
4. Stand next to the parked vehicle and verify manual unlock and lock.
5. Open **Auto access → Set up points**, measure where you want the vehicle to unlock and then where it should lock, and save the calibration.
6. Enable automatic unlock and automatic lock separately after manual commands have worked.

Automatic unlock and automatic lock remain disabled until calibration and successful manual lock/unlock. Automatic lock also requires a recent BLE command response confirming that the vehicle is closed. Calibration collects eight signal samples. If an automatic command is not confirmed, automation pauses until a manual lock or unlock succeeds. See [background reliability changes and device checks](docs/BACKGROUND-RELIABILITY.md).

## Automatic access

The app uses calibrated Bluetooth signal zones rather than physical distance. Starting calibration or a zone check persistently disables both automatic command switches. Saving, cancelling or restarting does not re-enable them. A zone check sends no automatic commands.

Each measurement collects eight fresh readings within 12 seconds and rejects a spread greater than 12 dBm. Losing the connection or backgrounding the screen interrupts the current measurement.

Older saved settings are migrated as follows:

- Passive Entry is removed and migrated to Off;
- both automatic command switches are disabled after migration;
- existing distance-based calibration is converted to its effective signal thresholds without rounding;
- accounts and calibration data remain available.

The detailed reliability behavior is documented in [Background reliability](docs/BACKGROUND-RELIABILITY.md).

## Permissions and quick controls

- **Nearby devices / Bluetooth:** discovers and communicates with the vehicle. Older Android versions also require location permission for BLE scanning.
- **Notifications:** displays foreground-service status and quick controls.
- **Internet:** performs QR authorization, retrieves credentials and sends supported cloud commands.
- **Foreground service, wake lock and boot handling:** keep background key operation and resume notifications working, subject to Android and device-vendor restrictions.

The foreground-service notification uses the Android system template and shows supported Unlock, Lock and Trunk actions. Actions are temporarily removed while a command is running; expanded text describes automation status. There is no emergency-stop action in the notification.

Add the **BYD Keyless** widget from the Android widget picker for separate Unlock, Lock and Trunk buttons. It supports compact 4×1 and expanded 4×2 layouts and follows the selected theme. The same actions are available as launcher shortcuts by long-pressing the app icon. Launcher shortcuts show a confirmation dialog before sending a command.

Quick controls connect the BLE key on demand and wait up to 20 seconds for authentication before failing. Notification and widget controls do not require device authentication and may be available from the lock screen. Only enable notification access on a phone you trust.

## Safety

Before testing with a real vehicle, use this sequence:

1. connect and read only;
2. test manual unlock and lock;
3. test trunk or find-car actions;
4. test calibrated automatic unlock while observing the vehicle;
5. test automatic lock while the vehicle remains parked and visible.

Keep every command untrusted until the vehicle advertises the required capability and returns a successful acknowledgement. Commands capable of moving or powering the vehicle also require the system device credential.

Emergency stop is available in the app's Settings. It disables proximity mode and automatic unlock/lock, disconnects Bluetooth and stops the foreground service. Android's Force stop can also terminate the app. Force-stop, OEM battery restrictions and revoked Bluetooth permission can prevent background operation. BLE RSSI provides a calibrated proximity zone, not a physical distance.

## Privacy and credentials

- The saved session contains watch tokens, the control password, VIN, vehicle profile and BLE key. It is encrypted with AES-GCM using an Android Keystore key.
- Preferences also contain an unencrypted copy of the active VIN, a generated watch identifier, language and region settings, and calibration values. These are app-private preferences rather than an encrypted database. Android backup is disabled.
- App-level network logging records endpoint, status and error information rather than request bodies or credential values. This does not guarantee what third-party native libraries may log.
- Signing out clears the locally saved session and disables keyless settings.

Never publish tokens, control passwords, BLE keys, session dumps or unredacted device logs. Share VINs or QR codes only with the owner's explicit permission; use expired QR codes for documentation whenever possible.

## Tests and CI

Unit tests cover protocol formatting and cryptography, mocked Watch API flows, command policies, proximity behavior, quick controls, manifest permissions, launcher shortcuts and translations. They do not require a BYD account or a vehicle.

The [Android CI workflow](.github/workflows/android.yml) runs unit tests, Android lint and a debug build on pushes and pull requests, and retains reports. Version-tag pushes also build, sign and publish a release APK after checks succeed; signing requires the one-time [release setup](docs/RELEASING.md). CI never executes commands against a vehicle.

Reports are available locally under `app/build/reports/` and `app/build/test-results/`. The native instrumentation smoke test requires a connected ARM64 Android device and is separate from CI:

```bash
./gradlew :app:connectedDebugAndroidTest
```

It loads JNI libraries and exercises local frame construction; it does not establish a vehicle connection. Automated checks cannot validate real-world vehicle behavior.

## Further documentation and licensing

- [Interface screenshot guide](docs/screenshots/README.md)
- [Background reliability and device checks](docs/BACKGROUND-RELIABILITY.md)
- [Release setup and publishing](docs/RELEASING.md)
- [Publication checks](docs/PUBLISHING.md)
- [Third-party components](THIRD_PARTY.md)

The Kotlin Watch implementation was developed against separate PHP Watch API work and an offline Python reference. Those external working copies are not distributed here or required to build this project; the included tests are the available regression reference.

No project license has been selected and no `LICENSE` file is provided. Do not assume an open-source license applies to this code or the bundled binaries. Review [Third-party components](THIRD_PARTY.md) and [Publication checks](docs/PUBLISHING.md) before uploading or redistributing the repository.
