# Phantom Lock

A dark, glass-inspired Android lock-screen-style interface for the Samsung Galaxy A02.

## Current interaction

- Touching the on-screen fingerprint normally produces haptic feedback and a "Fingerprint not matched" message.
- Tapping the small locked keypad icon silently arms the next fingerprint touch.
- The next fingerprint touch launches Android's genuine device-credential authentication (PIN, pattern, or password).
- The hidden arming state is automatically cleared after use or timeout.

## Important security boundary

The Galaxy A02 does not have an in-display fingerprint sensor. The on-screen fingerprint is a visual/touch interaction, not biometric verification. Android's system credential prompt remains the actual authentication authority.

This app is a lock-screen-style experience opened as an app. It does not replace or bypass Samsung's secure system lock screen.

## Build

The GitHub Actions workflow builds a debug APK. Open the workflow run and download the `app-debug` artifact.
