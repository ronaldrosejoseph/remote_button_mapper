# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device/emulator
./gradlew installDebug

# Clean build
./gradlew clean assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Project Overview

An Android TV app that remotes remote buttons to launch any installed app. Runs as an accessibility service that intercepts `KeyEvent`s, looks up mappings from an in-memory cache, and launches the target app.

## Architecture

Three components, all under `app/src/main/java/com/example/remotebuttonmapper/`:

- **`ButtonMapperService`** — An `AccessibilityService` (`onKeyEvent`) that intercepts hardware button presses. Three-tier routing: (1) system/navigation keys pass through immediately, (2) recording mode broadcasts the keycode to the UI, (3) mapped keys launch the target app. No debounce, no burst detection, no completion gate, no special key handling.

- **`MainActivity`** — Leanback-friendly UI for managing mappings. "Add Mapping" opens a dialog that sets `is_recording=true` in SharedPreferences, listens for `ACTION_KEY_CAPTURED` broadcasts from the service, then lets the user pick a target app from installed apps. Mappings persist in device-protected SharedPreferences (survives encrypted boot). Also handles accessibility service auto-enablement on first launch.

- **`BootReceiver`** — `BroadcastReceiver` for `BOOT_COMPLETED` (regular, locked, and OEM-specific variants). Sets a `boot_just_completed` flag in SharedPreferences. The service recovers automatically via `WRITE_SECURE_SETTINGS`.

### Key design decisions

1. **Direct-boot aware storage** — All SharedPreferences use `createDeviceProtectedStorageContext()`, so mappings survive reboots before the user unlocks the device.
2. **In-memory cache** — `mMappingsCache` avoids SharedPreferences disk I/O on every key event (the hot path). The `OnSharedPreferenceChangeListener` keeps it in sync when the UI modifies mappings.
3. **Recording mode** — A `volatile boolean mIsRecording` flag toggled via SharedPreferences lets the service broadcast pressed keycodes as `ACTION_KEY_CAPTURED` intents back to the dialog. The service consumes both ACTION_DOWN and ACTION_UP to prevent the default app from opening.
4. **Staleness check** — At the top of `onKeyEvent`, every ACTION_DOWN compares `event.getEventTime()` to `SystemClock.uptimeMillis()`. If delivery delay exceeds 300ms, the event was queued during a system lag spike and is discarded. This catches the TV startup burst with no state, no timers, and no cooldown. Normal events (~5-50ms) pass through instantly.
5. **No tests** — The project doesn't have a test directory or test dependencies configured.

### Permissions required via ADB

- `WRITE_SECURE_SETTINGS` — auto-enable accessibility service without user going to Settings
- Battery optimization whitelist — prevent OS from killing the service
- `SYSTEM_ALERT_WINDOW` — overlay permission for boot auto-launch on Android 10+
