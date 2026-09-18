# Background reliability changes

Implemented after the 2026-09-05 audit. Automated verification covers Kotlin behavior and Android compilation; vehicle behavior and battery improvement still need the physical checks below.

## Connection and commands

- A system MAC ScanFilter replaces the unfiltered scan. Missing/invalid MAC is reported as key-not-ready instead of connecting to arbitrary nearby devices. A usable stable vehicle MAC is required.
- Scanning starts in LOW_LATENCY and switches to LOW_POWER after 30 seconds without a match. Bluetooth off/on resumes monitoring. Nordic connection events trigger recovery immediately; setup has a 35-second overall deadline.
- Each connection owns its Nordic manager and generation; callbacks from retired managers/scanners are ignored. The unused raw-GATT transport has been removed.
- All BLE commands share a non-queueing mutex. A concurrent command receives COMMAND_BUSY rather than executing later with stale intent. Cancellation or timeout invalidates the connection before another command may run, and pending ACK state is cleared in finally.
- Auto commands execute independently from telemetry collection. RSSI updates cannot cancel the pending command.
- A local valid key starts BLE immediately. The network refresh has an 8-second budget while a valid local key is available, or 30 seconds if it is not. Foreground-start failures are shown in the UI.
- Cloud command submission is not automatically retried after a lost response; read requests retain retry. Coroutine cancellation cancels the OkHttp call.

## Automation

- Timing uses elapsedRealtime; key expiration still uses calendar time.
- Each RSSI sample has a sequence and timestamp. A connection change or a sample gap over 4 seconds restarts signal stabilization. Changes to other telemetry fields do not count as RSSI samples.
- Unlock-only rearms after a stable departure. Manual lock/unlock acknowledgements update the approach state; manual locking nearby suppresses immediate automatic reopening.
- Conditions are reevaluated while the user remains in a zone, including closures becoming known in FAR. Definitely rejected automatic requests may retry once after a 5-second cooldown.
- Unknown command outcomes pause automation until a manual lock/unlock succeeds. The app and service notification explain this state. No blind retry of an ambiguous physical action.
- Closures older than 30 seconds cannot authorize auto-lock. The available protocol still does not provide continuous fresh door/boot telemetry: if no fresh confirmation arrives, auto-lock is intentionally withheld. The existing native closure-bit interpretation has not been validated for every vehicle opening.
- Displayed lock status expires after 30 seconds on the next RSSI sample and resets immediately on disconnect. The approach history is distinct from displayed lock status.

## Energy, calibration and controls

- Removed the indefinite service wake lock. Connection setup and commands use bounded locks; handle acknowledgement gets a short bounded lock. Actual Doze latency must be tested on the target phone.
- Passive Entry was removed; connected signal sampling uses a 1-second interval. At most one RSSI read is queued. The watchdog allows a fresh read after CPU sleep before declaring the link stale.
- Widget rendering skips unchanged state.
- Calibration captures 8 distinct fresh samples from one connection, accepts at most 12 dB spread, and uses the median. The wizard records opening and closing thresholds directly. Legacy metre settings migrate to their effective thresholds without rounding. The UI shows zones rather than a physical distance.
- Settings include service state, BLE state/error, age of signal and vehicle responses, recovery count and last transport recovery reason. This diagnostic snapshot is in memory; no credentials or protocol payloads are stored.
- Public launcher shortcuts require a confirmation dialog. Notification/widget PendingIntents retain their existing one-tap behavior.

## Physical acceptance checks

Use a parked vehicle and observe actual actions. Record phone/OS, key mode, charge level and diagnostics before each run.

1. Connect, turn the screen off, leave BLE range and return. Repeat after 1 hour and 8 hours idle.
2. Repeat with system battery saver/Doze and the phone in a pocket. Measure time from first handle press to unlock and from discovery to READY.
3. Disable Bluetooth, wait, then enable it without reopening the app. Verify automatic reconnection.
4. Interrupt authentication and command acknowledgement. Confirm recovery, no duplicate action, and explicit automation pause for unknown command results.
5. Exercise two approaches in unlock-only mode; manual unlock followed by departure in lock-only mode; manual locking while still nearby.
6. Check open/unknown/stale closures, closing doors after reaching FAR, and actual boot/door bit meanings. Never interpret an absent auto-lock as proof of locking.
7. Restart the process, reboot and unlock the phone, update the APK, and revoke/regrant Bluetooth permissions. Treat user force-stop separately; no bypass is promised.
8. Check launcher confirmation and notification/widget actions. Verify that merely starting QuickCommandActivity with an external Intent sends no command.
9. Measure 8-hour battery consumption both near the vehicle and away from it against the previous build. Count successful approaches, p50/p95 latency and recoveries; do not claim savings until measured.

CompanionDeviceService/presence integration and a protocol heartbeat remain separate experiments requiring MAC/advertisement and protocol validation. The current change does not invent a BYD ping command or add a periodic process-restart mechanism.
