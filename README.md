# BYD Keyless

Personal ARM64 Android prototype for using a BYD Sealion 7 watch credential as a Bluetooth key. The app signs in through the official BYD AUTO QR flow, obtains a separate watch token and BLE `dkey`, and keeps an authenticated BLE connection in a `connectedDevice` foreground service.

> This is an independently built, unofficial client for an unpublished protocol. Do not publish or redistribute the bundled BYD native libraries without a separate licensing review. Test only on a vehicle you own or are explicitly authorized to control.

## Build

Requirements: Android Studio/JDK 17 and Android SDK 36.

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Only `arm64-v8a` is supported because the reference Wear OS APK contains no other ABI.

## First use

1. Install the debug APK on an ARM64 Android phone.
2. Create a QR code in the app, scan it in BYD AUTO, choose the Sealion 7 and approve the watch sign-in.
3. Grant Nearby devices and notification permissions, then start the key while the app is visible.
4. Verify manual unlock and lock while standing next to the parked car.
5. Capture RSSI at approximately 1 m and 5 m before enabling automatic access.

Auto unlock and auto lock remain disabled until calibration and successful manual lock/unlock. Auto lock additionally requires the latest BLE command response to confirm all closures are closed.

## Safety boundaries

- Tokens, control password, VIN and BLE key are encrypted with AES-GCM under an Android Keystore key. Logs never contain request bodies or secrets.
- A command is rejected in the controller unless the BYD vehicle configuration advertises its `functionCode`.
- Experimental commands with no confirmed capability remain visible but disabled. Commands capable of moving or powering the vehicle also require the system device credential.
- A persistent notification provides an emergency stop action. Force-stop, OEM battery restrictions and revoked Bluetooth permission can still prevent background key operation.
- BLE RSSI provides a calibrated proximity zone, not a precise physical distance.

## Protocol references

The Kotlin Watch implementation is parity-tested against the uncommitted Watch API work in `/Users/vitaly/Project/VitalyArt/byd-php-client` and the offline reference in `/Users/vitaly/Project/Sealion7/byd-app/tools/byd_watch_auth_reference.py`.

Before testing on a real vehicle, run the staged sequence: connect/read only → manual unlock/lock → trunk/find car → passive handle → calibrated auto unlock → observed auto lock. Treat every additional command as unverified until the car advertises the capability and returns a successful acknowledgement.
