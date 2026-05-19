# Remote Button Mapper

Remap any button on your Android TV remote to launch a specific app. For example, remap the Netflix button to open YouTube, or the Prime Video button to open Plex.

The app runs as an accessibility service in the background, intercepting hardware key events and launching your chosen apps instantly.

---

## Features

- **Button remapping** — Map any remote button to any installed app
- **Foreground service** — Runs persistently without being killed by the OS
- **Direct boot aware** — Mappings survive encrypted reboots
- **Debounced key presses** — Prevents accidental double-launches from held or bounced buttons
- **Auto-start on boot** — The service re-enables itself after a reboot *(requires advanced setup — see below)*

---

## Installation

### 1. Install the APK

1. **Download the APK** from the [Releases page](https://github.com/ronaldrosejoseph/remote_button_mapper/releases) — grab the latest APK from the attachments
2. Transfer it to your TV using one of these methods:
   - **Send Files to TV** app (available on Play Store for both phone and TV)
   - **USB drive** — copy the APK to a USB drive and plug it into your TV
   - **Cloud storage** — upload to Google Drive, then download on your TV using a file manager
3. Open the APK file on your TV using a file manager to install it

### 2. Enable the Accessibility Service

This is **required** — the app cannot intercept button presses without it.

1. Open the **Remote Button Mapper** app on your TV
2. If the accessibility settings don't open automatically, go to **Settings → Accessibility** (or **Settings → System → Accessibility**)
3. Find **Remote Button Mapper** in the list
4. Toggle it **ON** and confirm any permission dialogs

Once enabled, you'll see a persistent notification confirming *"Button mapping is active"*.

### 3. Allow Display Over Other Apps

When you first open the app, a popup will ask you to **"Allow display over other apps"** — tap **Allow**. This permission prevents the TV from automatically stopping the app in the background.

---

## How to Use

### Adding a Button Mapping

1. Open the **Remote Button Mapper** app
2. Tap **"Add Mapping"**
3. **Press the button on your remote** that you want to remap — the key code will appear on screen
4. **Select the app** you want that button to launch from the list
5. The mapping is saved immediately

### Modifying or Deleting a Mapping

1. Open the app and find the mapping in the list
2. **Press OK** on the mapping
3. Choose **"Modify"** to change the target app, or **"Delete"** to remove the mapping

### System Keys (Not Remappable)

The following keys are always passed through to the system and cannot be remapped:

| Key | Reason |
|-----|--------|
| D-pad (Up/Down/Left/Right/Center) | Navigation |
| Enter, Back, Home, Escape | Core navigation |
| Menu, Settings | System access |
| Volume Up/Down/Mute | Media control |
| Power, Sleep, Wakeup | Power management |

### After a TV Restart

**You need to open the app first after every power reboot.** Going directly to Settings and toggling the accessibility service won't work — the app must be launched at least once so it can bind to the system. Open the Remote Button Mapper app and it will warn you if the service is not running, then guide you to re-enable it.

**Some TV operating systems may also put the app to sleep** in the background. If mappings suddenly stop working mid-use, the OS likely suspended the service. Simply open the app again to restore functionality.

**To fix both issues permanently**, follow the advanced setup steps below. They will:
- Re-enable the accessibility service automatically after every reboot
- Launch the app on startup
- Prevent the OS from ever putting the app to sleep

These steps require connecting your TV to a computer — some technical comfort is needed.

---

## Set Up Auto-Start After Reboot (Optional)

Without this, you'll need to manually re-enable the accessibility service each time the TV restarts. These steps make it automatic — the app will recover on its own after a reboot.

> **Already have ADB?** You can install the APK directly:
> ```bash
> adb install remote-button-mapper.apk
> ```

### Prerequisite: Enable USB Debugging

To run ADB commands, you first need USB debugging enabled on your TV.

1. Enable Developer Options: go to **Settings → Device Preferences → About**, find **Build** and press OK on it 7 times
2. Go to **Settings → Device Preferences → Developer options**
3. Enable **USB debugging**
4. Connect your TV to your computer via USB, or connect wirelessly:
   ```bash
   adb connect <TV_IP_ADDRESS>:5555
   ```

### Grant WRITE_SECURE_SETTINGS

Allows the app to programmatically enable its own accessibility service after a reboot.

```bash
adb shell pm grant com.example.remotebuttonmapper android.permission.WRITE_SECURE_SETTINGS
```

### Exempt from Battery Optimization

Prevents the OS from killing the service to save power.

```bash
adb shell dumpsys deviceidle whitelist +com.example.remotebuttonmapper
```

### Grant Overlay Permission

Required on Android 10+ for the app to auto-launch on boot.

```bash
adb shell appops set com.example.remotebuttonmapper SYSTEM_ALERT_WINDOW allow
```

---

## Troubleshooting

### Service not working after reboot

1. Verify `WRITE_SECURE_SETTINGS` is granted:
   ```bash
   adb shell dumpsys package com.example.remotebuttonmapper | grep WRITE_SECURE_SETTINGS
   ```
2. Check if the service is running:
   ```bash
   adb shell dumpsys accessibility | grep ButtonMapperService
   ```

### Mapped button still opens the default app

The accessibility service may have lost its binding. Re-enable it:

```bash
adb shell settings put secure enabled_accessibility_services com.example.remotebuttonmapper/com.example.remotebuttonmapper.ButtonMapperService
adb shell settings put secure accessibility_enabled 1
```

### View live logs

```bash
adb logcat -s ButtonMapperService:D BootReceiver:D MainActivity:D
```

Look for:
- `"Mapping found for key X"` — successful remap
- `"Debounced key X (too soon)"` — rapid press was correctly filtered
- `"Service Connected"` — service is alive and bound

---

## Building from Source

```bash
git clone https://github.com/ronaldrosejoseph/remote_button_mapper
cd remote_button_mapper
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

Install directly via:

```bash
./gradlew installDebug
```

---

## Architecture

| Component | Purpose |
|-----------|---------|
| `ButtonMapperService` | Accessibility service that intercepts key events, looks up mappings from an in-memory cache, debounces presses, and launches target apps |
| `MainActivity` | UI for adding/modifying/deleting button mappings, stored in device-protected SharedPreferences |
| `BootReceiver` | Listens for boot-completed broadcasts and sets a flag for service recovery |

---

## License

Private project — all rights reserved.
