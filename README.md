# Phantom Lock

A Samsung S20-inspired secondary lock layer for Android, designed for the Galaxy A02 / Android 11.

## How it works

1. Grant **Display over other apps** permission.
2. Set a secure Android screen lock (PIN, pattern or password).
3. Activate Phantom Lock.
4. Your normal Samsung lock screen remains the first security layer.
5. After Android unlocks, Phantom displays its full-screen lock layer over apps.
6. Triple-tap the fingerprint artwork to open Android's genuine biometric/device-credential prompt.
7. Successful Android authentication dismisses Phantom until the next screen-off / unlock cycle.

The protection service runs in the foreground and restores itself after reboot while protection is enabled. Its persistent notification includes a **Turn off** action as an emergency exit.

## Authentication and hardware boundary

The fingerprint illustration is a visual design element. It does not read fingerprints from the touchscreen.

The Galaxy A02 has no fingerprint sensor, so this device uses the Android system PIN, pattern or password prompt. On supported devices with an enrolled strong fingerprint sensor, Android may offer biometric authentication through the same system prompt.

Phantom Lock does not replace, bypass, or draw over Android's secure keyguard. It adds a secondary app-lock layer after the native keyguard has been dismissed. Android may hide application overlays in protected system surfaces or security-sensitive apps.

## Permissions

- Display over other apps: show the secondary lock layer.
- Foreground service: keep protection active while the phone is in use.
- Boot completed: restore protection after a reboot.
- Use biometric: allow Android's own biometric/device-credential prompt.

## Build

The GitHub Actions workflow builds the debug APK on every push to `main`. Download the `screenlock-debug-apk` artifact from the workflow run.
