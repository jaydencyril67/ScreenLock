# Phantom Lock

A custom Samsung Galaxy S20-inspired lock screen for Android, designed for the Galaxy A02 / Android 11.

## Lock-screen experience

- Phantom launches immediately on the dynamic screen-on broadcast while protection is enabled.
- The Activity uses Android's show-when-locked and turn-screen-on behavior to appear above the native keyguard.
- The lock UI includes a dark atmospheric wallpaper, large live clock, date, battery percentage, a bold fingerprint mark and a small padlock.
- The imported fingerprint artwork is intentionally shown without a surrounding sensor ring.
- Tapping the fingerprint before arming displays **“Fingerprint doesn't match”**.
- Tapping the small padlock silently arms exactly one fingerprint tap.
- The next fingerprint tap uses Android biometric authentication when strong biometric hardware is enrolled. Otherwise, on the Galaxy A02, the armed tap dismisses the visual lock layer.
- The armed state resets when the screen turns off.

## Authentication and hardware boundary

The fingerprint artwork is visual; it does not read touch patterns as biometric data.

On a phone with an enrolled strong fingerprint sensor, Phantom opens Android's genuine biometric prompt after the padlock has armed the next tap. It does not request Android device credentials as a fallback and does not require the user to set a phone PIN, pattern, or password just to activate Phantom.

The Galaxy A02 does not have a fingerprint reader. On that device, the armed tap dismisses the visual lock layer directly. This is a gesture-based visual lock, not real fingerprint security. A phone with no hardware fingerprint sensor cannot perform fingerprint verification in software.

## Setup

1. Grant **Display over other apps** permission.
2. Activate Phantom Lock.
3. Turn the screen off, then wake it with the power button.
4. Tap the fingerprint once to see the mismatch message.
5. Tap the small padlock, then tap the fingerprint to dismiss the lock surface.

A persistent foreground-service notification provides an emergency **Turn off** action. Protection restores after reboot when it was previously enabled and overlay permission remains granted.

## Wake and keyguard behavior

The service responds immediately to the dynamic screen-on broadcast without an extra launch delay. Its foreground service and user-granted overlay permission allow the background lock Activity launch. The Activity uses Android's supported show-when-locked and turn-screen-on APIs to appear above the native lock screen.

Android and Samsung still control keyguard scheduling, battery restrictions and protected system surfaces. Phantom requests the earliest app-controlled launch, but it cannot replace the operating system's keyguard or guarantee a zero-frame transition on every firmware build.

## Artwork attribution

The fingerprint and padlock vector drawables are based on Google Material Design Icons, distributed under the Apache License 2.0. See THIRD_PARTY_NOTICES.md. The fingerprint artwork is adapted for Phantom's larger, bolder presentation; it is not Samsung's proprietary firmware asset.

## Build

The GitHub Actions workflow builds the debug APK on each push to main. Download the screenlock-debug-apk artifact from the workflow run.
