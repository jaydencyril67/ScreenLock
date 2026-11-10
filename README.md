# Phantom

Phantom is evolving from a lockscreen utility into a standalone Android Home experience for the Galaxy A02 / Android 11.

## Home launcher foundation

- `PhantomHomeActivity` is a dedicated Android Home activity, registered with `MAIN`, `HOME`, and `DEFAULT` intent categories.
- The launcher is separate from `MainActivity` (setup and settings) and `PhantomLockActivity` (the existing lock surface).
- The Home screen reuses the user's selected Phantom wallpaper, uses the bundled STIX Two Text family, and provides a searchable app drawer that launches installed apps.
- Select Phantom from **Settings → Apps → Default apps → Home app**, or use the setup button in Phantom's app screen.
- Samsung's status bar, navigation system, notification shade, and Android settings remain system-owned. Phantom does not pretend to replace privileged Samsung firmware components.
- The current launcher foundation is intentionally lightweight. Custom control centre, notification presentation, ambient display, and edge-lighting modules are separate follow-up work; each will be implemented only within Android's available permissions and hardware capabilities.

## Lock-screen experience

- When the device becomes non-interactive, the foreground service prepares the full-screen lock Activity without turning the display back on.
- The prepared Activity remains alive with Android's show-when-locked behavior, ready to appear over the native keyguard as the display wakes.
- The screen-on broadcast is retained as a recovery fallback when the prepared Activity is missing.
- The lock UI includes a selectable local-photo wallpaper, a premium single-line clock and expanded date, battery percentage, a bold fingerprint mark, a local-file music player, and flashlight/camera shortcuts.
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

1. Open Phantom and choose **Set Phantom as default Home** to open Android's Home-app settings.
2. Select Phantom as the Home app.
3. Grant **Display over other apps** permission for the existing lock surface.
4. Activate Phantom protection.
5. Turn the screen off, then wake it with the power button.
6. Tap the fingerprint once to see the mismatch message.
7. Tap the small padlock, then tap the fingerprint to dismiss the lock surface.

A persistent foreground-service notification provides an emergency **Turn off** action. Protection restores after reboot when it was previously enabled and overlay permission remains granted.

## Wake and keyguard behavior

The earlier implementation waited for ACTION_SCREEN_ON and only then started the lock Activity. Android documents ACTION_SCREEN_ON as the point when the device has already woken and become interactive, so the native keyguard can begin appearing before an app launched in response is ready. The updated service instead starts the lock Activity on ACTION_SCREEN_OFF, when the device becomes non-interactive. In this prepare-only mode, the Activity uses showWhenLocked but explicitly does not turn the display back on. Its window and UI are created before the next wake. ACTION_SCREEN_ON remains a fallback if the prepared Activity was killed or the screen-off event was missed. Background Activity launch relies on the user-granted SYSTEM_ALERT_WINDOW permission.

Android and Samsung still control keyguard scheduling, battery restrictions and protected system surfaces. Phantom requests the earliest app-controlled launch, but it cannot replace the operating system's keyguard or guarantee a zero-frame transition on every firmware build.

## Artwork attribution

The fingerprint and padlock vector drawables are based on Google Material Design Icons, distributed under the Apache License 2.0. See THIRD_PARTY_NOTICES.md. The fingerprint artwork is adapted for Phantom's larger, bolder presentation; it is not Samsung's proprietary firmware asset.

## App icon

Phantom Lock uses a dedicated charcoal-and-copper adaptive launcher icon on Android 8.0 and newer.

## Local music and device shortcuts

- The music player uses audio files that the user explicitly selects from the phone's document picker. It supports a multi-track queue, previous, play/pause, next, embedded artwork and track metadata where available.
- Playback is handled by an independent service, so leaving the lock screen to open the camera or another screen does not immediately destroy the player.
- Phantom does not inspect other apps' media sessions, request notification-listener access, or display notification previews.
- The flashlight shortcut checks camera permission before accessing the camera hardware and toggles the device torch. The camera shortcut opens the phone's camera app.

## Distribution variants

The sideload and Play build variants now use the same local-file music player. Neither variant declares a notification-listener service or needs notification-listener access. The GitHub debug workflow still builds both variants as a compilation check and publishes the sideload debug APK directly.

## Build and downloads

- **Normal installation:** open the repository's **Releases** page and download the `PhantomLock-x.y.z.apk` asset. This is the signed sideload edition as a direct APK file, not an Actions ZIP.
- **Debug/testing only:** the push workflow builds both variants to check compilation, but publishes the sideload debug APK as a direct GitHub Release asset. GitHub Actions workflow artifacts are ZIP-wrapped, so use the direct APK release asset instead.
- The manual release workflow publishes the signed sideload APK directly as a GitHub Release asset.
- The Play-distribution variant can be built with `gradle :app:assemblePlayRelease`; the sideload release uses `gradle :app:assembleSideloadRelease`.
