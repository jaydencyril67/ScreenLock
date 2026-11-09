# Phantom Lock

A minimal, Samsung Galaxy S20-inspired screen-on lock surface for Android, designed for the Galaxy A02 / Android 11.

## User experience

- Phantom appears when the display wakes after the power button is pressed.
- The lock surface contains only a fingerprint symbol and a small padlock.
- It has no clock, branding, extra buttons, or default vibration.
- Tapping the fingerprint before arming shows **“Fingerprint doesn't match”**.
- Tapping the small padlock silently arms one fingerprint tap. The next fingerprint tap dismisses the lock surface.
- The armed state is one-use and resets when the screen turns off.

## Authentication boundary

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

## Android behavior

The app dynamically listens for screen-on and screen-off broadcasts. It launches a full-screen Activity using Android's show-when-locked and turn-screen-on window behavior. The user-granted overlay permission is required by this implementation for the screen-on Activity launch from the background.

OEM power management can affect background services. On the Galaxy A02, keep Phantom Lock allowed to run in the background if Samsung's battery controls offer that setting.

## Artwork attribution

The fingerprint and padlock vector drawables are based on Google Material Design Icons, distributed under the Apache License 2.0. See THIRD_PARTY_NOTICES.md. The artwork is an S20-inspired approximation, not Samsung's proprietary firmware asset.

## Build

The GitHub Actions workflow builds the debug APK on each push to main. Download the screenlock-debug-apk artifact from the workflow run.
