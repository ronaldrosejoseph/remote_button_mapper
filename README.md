# Remote Button Mapper

Remap any button on your Android TV remote to launch a specific app. For example, remap the Netflix button to open YouTube, or the Prime Video button to open Plex.

The app runs as an accessibility service in the background, intercepting hardware key events and launching your chosen apps instantly.

---

## Features

- **Button remapping** — Map any remote button to any installed app
- **Auto-start on boot** — The service starts automatically when the TV powers on
- **Foreground service** — Runs persistently without being killed by the OS
- **Direct boot aware** — Mappings survive encrypted reboots
- **Debounced key presses** — Prevents accidental double-launches from held or bounced buttons

---

## Installation

### 1. Install the APK

Sideload the APK via ADB:

```bash
adb install remote-button-mapper.apk
```

### 2. Enable Developer Options on Android TV

> **Note:** These steps may vary slightly depending on your TV manufacturer (TCL, Sony, Xiaomi, etc.).

1. Open **Settings** on your Android TV
2. Scroll down to **Device Preferences** (or **System** on some TVs)
3. Select **About**
4. Find **Build** (or **Build number**) and **press OK on it 7 times** rapidly
5. You should see a toast message: *"You are now a developer!"*
6. Go back — **Developer options** will now appear under **Device Preferences** or **System**

### 3. Enable USB Debugging (required for ADB)

1. Go to **Settings → Device Preferences → Developer options**
2. Enable **USB debugging**
3. Connect your TV to your computer via USB or ensure both are on the same network
4. For wireless ADB: `adb connect <TV_IP_ADDRESS>:5555`

---

## Granting Permissions via ADB

The app requires two special permissions that can only be granted via ADB. These enable the service to **auto-enable itself on boot** without manual intervention.

### Grant WRITE_SECURE_SETTINGS (auto-enable accessibility service)

This allows the app to programmatically toggle its own accessibility service on/off, which is essential for auto-recovery after a reboot.

```bash
adb shell pm grant com.example.remotebuttonmapper android.permission.WRITE_SECURE_SETTINGS
```

### Exempt from battery optimization

Prevents the OS from killing the service to save power:

```bash
adb shell dumpsys deviceidle whitelist +com.example.remotebuttonmapper
```

### Grant overlay permission (auto-launch on boot)

Required on Android 10+ for the boot receiver to launch the activity:

```bash
adb shell appops set com.example.remotebuttonmapper SYSTEM_ALERT_WINDOW allow
```

---

## Enabling the Accessibility Service

The app will try to auto-enable itself on first launch if `WRITE_SECURE_SETTINGS` has been granted. If it cannot, you'll need to enable it manually:

1. Go to **Settings → Accessibility** (or **Settings → System → Accessibility**)
2. Find **Remote Button Mapper** in the list
3. Toggle it **ON**
4. Confirm any permission dialogs

Once enabled, you'll see a persistent notification confirming *"Button mapping is active"*.

> **After a reboot**, the app automatically re-enables the accessibility service if `WRITE_SECURE_SETTINGS` was granted — no manual steps needed.

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
git clone <repository-url>
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
