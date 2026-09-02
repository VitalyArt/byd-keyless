# Documentation screenshots

This directory contains real captures of BYD Keyless on an ARM64 Android phone, with the app language set to English. The main [README](../../README.md) displays the selected screens.

## Included captures

Captured on 2026-09-02 from the updated 0.1.0 build without voice control. Except for the redacted QR image described below, the files are unedited 1080 × 2340 PNG device screenshots.

- [Welcome](welcome.png): signed-out landing page with region selection and QR-code creation.
- [QR sign-in](qr-sign-in-redacted.png): authorization screen with the entire QR code hidden at the owner's request. This is an AI-edited derivative of a real screenshot, exported at 852 × 1846; it is not a pixel-exact original capture.
- [Digital key](key.png): vehicle overview, the app's masked VIN and current digital key status. The original VIN masking is unchanged.
- [Vehicle controls](controls.png): capability-based access and locate actions.
- [Access modes](access-settings.png): Off, Passive entry and Automatic access, with both automation switches disabled.
- [Safety and background operation](safety-settings.png): experimental-control warnings, language selection and background-service guidance.

Automatic unlock/lock were disabled for all captures. The digital-key screen was captured with the key active; controls and settings were captured with it stopped. The owner subsequently signed out before the welcome and QR captures. No sign-in was approved and no vehicle commands were invoked for the screenshots. No connection or command-success state was staged. The displayed distance is a signal-based estimate, not a verified measurement. The owner permitted the visible VIN suffix and requested full QR redaction. The unredacted QR capture is not included in this repository.

## QR privacy edit

The built-in imagegen tool covered the complete QR pattern with an opaque panel. The edited image was visually reviewed to confirm that no QR modules remain visible. The panel is a documentation edit, not an app feature.

Editing prompt: “Cover the entire QR pattern, including all finder squares and modules, with a completely opaque dark-gray square labeled QR CODE HIDDEN. Preserve the white card and Waiting for scan caption. Change only the QR area; preserve the rest of the screenshot, its text, icons, colors, proportions and framing. Do not add a scannable code.”

## Capture guidelines

- Use the current app build and English interface text.
- Disable automatic access before navigating the app for documentation. Do not invoke vehicle commands or change calibration values just to demonstrate a state.
- Include VINs only with the owner's explicit permission. Fully cover authorization QR codes before publication; do not rely on a light blur. Keep tokens, control passwords, BLE keys, account details and unrelated notifications out of the frame.
- Capture only the app, not a personal launcher, notification shade or lock screen.
- Preserve the actual UI: do not invent connection status, sensor readings or successful command results.
- Save captures as PNG files with descriptive lowercase names, review them at full resolution, and use relative image links so they render on GitHub.

Screenshots illustrate the interface, not vehicle compatibility or a successful real-world control test. Available controls and status vary with the vehicle, credential and connection state.
