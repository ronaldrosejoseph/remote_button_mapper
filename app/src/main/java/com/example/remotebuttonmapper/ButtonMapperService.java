package com.example.remotebuttonmapper;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Map;

@SuppressLint("AccessibilityPolicy")
public class ButtonMapperService extends AccessibilityService {

    private static final String TAG = "ButtonMapperService";
    private static final String PREFS_NAME = "ButtonMappings";
    private static final String CHANNEL_ID = "button_mapper_service";
    private static final int NOTIFICATION_ID = 1;

    /** Per-key debounce for mapped-key app launches (elapsedRealtime). */
    private static final long DEBOUNCE_MS = 500;

    /** Per-key debounce for system/navigation keys — tight so fast navigation
     *  feels responsive.  Lag-burst protection is handled by the burst detector. */
    private static final long SYSTEM_KEY_DEBOUNCE_MS = 150;

    /** Per-key debounce for volume keys. */
    private static final long VOLUME_KEY_DEBOUNCE_MS = 200;

    /** Events for the same keycode within this eventTime window are hardware duplicates. */
    private static final long EVENT_TIME_DEDUP_MS = 80;

    // ── System-key burst detection ───────────────────────────────────────
    /** Two system-key DOWN events delivered this close together with a stale
     *  event time indicate a lag-induced burst.  Human presses never arrive
     *  this fast (normal inter-event gap is 100ms+). */
    private static final long SYSTEM_BURST_GAP_MS = 40;
    /** Delivery delay beyond which an event is considered "stale" (queued during lag). */
    private static final long SYSTEM_STALE_MS = 200;
    /** How long to block system keys after a burst is detected. */
    private static final long SYSTEM_BURST_COOLDOWN_MS = 400;

    // ── Mapped-key completion gating ─────────────────────────────────────
    /** After launchApp, wait this long for the target window to appear before
     *  timing out and allowing the next launch. */
    private static final long LAUNCH_COMPLETION_TIMEOUT_MS = 3000;

    private SharedPreferences mStatePrefs;
    private SharedPreferences mMappingsPrefs;

    /** Cached recording state — volatile so onKeyEvent sees changes from the UI thread instantly. */
    private volatile boolean mIsRecording = false;

    /** In-memory cache: keyCode (int) → packageName. Avoids disk I/O on every key event. */
    private final HashMap<Integer, String> mMappingsCache = new HashMap<>();

    /** Per-keycode timestamp of the last successful launch (elapsedRealtime), for debouncing. */
    private final HashMap<Integer, Long> mLastLaunchTime = new HashMap<>();

    /** Per-keycode timestamp of the last processed system key event (elapsedRealtime). */
    private final HashMap<Integer, Long> mLastSystemKeyTime = new HashMap<>();

    /** Per-keycode eventTime of the last processed event, for hardware-level dedup. */
    private final HashMap<Integer, Long> mLastEventDownTime = new HashMap<>();

    // ── System-key burst detection state ─────────────────────────────────
    /** elapsedRealtime of the last system-key DOWN event (any keycode). */
    private long mLastSystemKeyDeliveryTime;
    /** Block system keys until this elapsedRealtime after a burst. */
    private long mSystemBurstCooldownUntil;

    // ── Mapped-key completion gating ─────────────────────────────────────
    /** True while waiting for the launched app's window to appear. */
    private boolean mPendingLaunch;
    /** elapsedRealtime of the last launch, for the completion timeout. */
    private long mLastLaunchAnyKeyTime;

    /** Keeps mMappingsCache in sync when the UI adds/removes mappings. */
    private final SharedPreferences.OnSharedPreferenceChangeListener mMappingsListener =
            (prefs, key) -> {
                if (key == null) return;
                try {
                    int keyCode = Integer.parseInt(key);
                    String pkg = prefs.getString(key, null);
                    if (pkg != null) {
                        mMappingsCache.put(keyCode, pkg);
                    } else {
                        mMappingsCache.remove(keyCode);
                    }
                } catch (NumberFormatException ignored) {
                    // Non-numeric keys are not button mappings — skip
                }
            };

    /** Keeps mIsRecording in sync — eliminates SharedPreferences read from the key-event hot path. */
    private final SharedPreferences.OnSharedPreferenceChangeListener mStateListener =
            (prefs, key) -> {
                if ("is_recording".equals(key)) {
                    mIsRecording = prefs.getBoolean(key, false);
                }
            };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Completion gate: when a launched app's window appears, allow the next launch.
        if (mPendingLaunch
            && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null && !pkg.equals(getPackageName())) {
                mPendingLaunch = false;
            }
        }
    }

    @Override
    public void onInterrupt() {
        // Not used
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Service Connected");

        Context directBootContext = createDeviceProtectedStorageContext();
        mStatePrefs = directBootContext.getSharedPreferences("ServiceState", MODE_PRIVATE);
        mMappingsPrefs = directBootContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Seed cached recording state
        mIsRecording = mStatePrefs.getBoolean("is_recording", false);

        // Populate in-memory cache from current mappings
        loadMappingsCache();

        // Listen for future changes so caches stay current
        mMappingsPrefs.registerOnSharedPreferenceChangeListener(mMappingsListener);
        mStatePrefs.registerOnSharedPreferenceChangeListener(mStateListener);

        startForegroundNotification();

        // Show status toast so user knows the service is running
        try {
            Toast.makeText(this, "✓ Remote Button Mapper is active", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Could not show toast", e);
        }
    }

    @Override
    public void onDestroy() {
        // Unregister listener to avoid leaks
        if (mMappingsPrefs != null) {
            mMappingsPrefs.unregisterOnSharedPreferenceChangeListener(mMappingsListener);
        }
        if (mStatePrefs != null) {
            mStatePrefs.unregisterOnSharedPreferenceChangeListener(mStateListener);
        }
        Log.w(TAG, "Service destroyed — this should not happen normally.");
        super.onDestroy();
    }

    /** Load all mappings from SharedPreferences into the in-memory HashMap. */
    private void loadMappingsCache() {
        mMappingsCache.clear();
        Map<String, ?> all = mMappingsPrefs.getAll();
        all.forEach((key, value) -> {
            try {
                int keyCode = Integer.parseInt(key);
                if (value instanceof String pkg) {
                    mMappingsCache.put(keyCode, pkg);
                }
            } catch (NumberFormatException ignored) {
                // Skip non-numeric keys
            }
        });
        Log.d(TAG, "Loaded " + mMappingsCache.size() + " mappings into cache");
    }

    private void startForegroundNotification() {
        try {
            createNotificationChannel();

            Intent notificationIntent = new Intent(this, MainActivity.class);
            notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Remote Button Mapper")
                    .setContentText("Button mapping is active")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            Log.i(TAG, "Foreground notification started — service is now persistent.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground notification", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Button Mapper Service",
                    NotificationManager.IMPORTANCE_LOW); // Low = no sound, shows in tray
            channel.setDescription("Keeps the button mapper running in the background");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        // 1. Always pass through Navigation/System keys — zero overhead path
        if (isSystemKey(keyCode)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                long now = SystemClock.elapsedRealtime();
                long eventTime = event.getEventTime();

                // Burst cooldown — after a lag burst is detected, block all
                // system keys for a beat so queued events don't flood through.
                if (now < mSystemBurstCooldownUntil) {
                    return true;
                }

                // Event-time dedup: hardware-level duplicates
                Long lastEventTime = mLastEventDownTime.get(keyCode);
                if (lastEventTime != null && Math.abs(eventTime - lastEventTime) < EVENT_TIME_DEDUP_MS) {
                    return true;
                }

                // Per-key debounce — tight (150ms) so fast navigation feels responsive
                long debounceMs = isVolumeKey(keyCode) ? VOLUME_KEY_DEBOUNCE_MS : SYSTEM_KEY_DEBOUNCE_MS;
                Long last = mLastSystemKeyTime.get(keyCode);
                if (last != null && (now - last) < debounceMs) {
                    return true;
                }

                // Burst detection: two system-key events arriving <40ms apart
                // AND the current event is stale (>200ms delivery delay) →
                // lag-induced burst.  Normal presses never trigger this because
                // inter-event gaps are 100ms+ and delivery delay is ~5-50ms.
                long gap = (mLastSystemKeyDeliveryTime != 0)
                        ? (now - mLastSystemKeyDeliveryTime) : Long.MAX_VALUE;
                boolean stale = (now - eventTime) > SYSTEM_STALE_MS;
                if (gap < SYSTEM_BURST_GAP_MS && stale) {
                    mSystemBurstCooldownUntil = now + SYSTEM_BURST_COOLDOWN_MS;
                    Log.w(TAG, "System-key burst — cooldown " + SYSTEM_BURST_COOLDOWN_MS + "ms");
                    return true;
                }

                if (mLastSystemKeyTime.size() > 50) {
                    mLastSystemKeyTime.clear();
                    mLastEventDownTime.clear();
                }
                mLastSystemKeyTime.put(keyCode, now);
                mLastEventDownTime.put(keyCode, eventTime);
                mLastSystemKeyDeliveryTime = now;
            }
            return false;
        }

        // Re-initialize prefs only if null (service restart edge case)
        if (mStatePrefs == null || mMappingsPrefs == null) {
            Context directBootContext = createDeviceProtectedStorageContext();
            mStatePrefs = directBootContext.getSharedPreferences("ServiceState", MODE_PRIVATE);
            mMappingsPrefs = directBootContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            mIsRecording = mStatePrefs.getBoolean("is_recording", false);
            loadMappingsCache();
            mMappingsPrefs.registerOnSharedPreferenceChangeListener(mMappingsListener);
            mStatePrefs.registerOnSharedPreferenceChangeListener(mStateListener);
        }

        // 2. Recording Mode: consume BOTH Down and Up
        if (mIsRecording) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                // Validate keycode is in a reasonable range (some custom remotes use 4000+)
                if (keyCode < 0 || keyCode > 10000) {
                    Log.w(TAG, "Ignoring out-of-range keyCode: " + keyCode);
                    return true;
                }
                Log.d(TAG, "Recording Key: " + keyCode);
                // Broadcast key to UI only on DOWN — restricted to our app package only
                Intent intent = new Intent("com.example.remotebuttonmapper.ACTION_KEY_CAPTURED");
                intent.setPackage(getPackageName());
                intent.putExtra("keyCode", keyCode);
                sendBroadcast(intent);
            }
            return true; // Consume ALL events for this key
        }

        // 3. Normal Mapping Mode: fast in-memory lookup instead of disk-backed getString()
        String packageName = mMappingsCache.get(keyCode);

        if (packageName != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                long now = SystemClock.elapsedRealtime();
                long eventTime = event.getEventTime();

                // ── Layer 1: event-time dedup (hardware duplicates) ────────
                Long lastEventTime = mLastEventDownTime.get(keyCode);
                if (lastEventTime != null && Math.abs(eventTime - lastEventTime) < EVENT_TIME_DEDUP_MS) {
                    return true;
                }

                // ── Layer 2: per-key elapsed-time debounce ─────────────────
                Long lastLaunch = mLastLaunchTime.get(keyCode);
                if (lastLaunch != null && (now - lastLaunch) < DEBOUNCE_MS) {
                    return true;
                }

                // ── Layer 3: completion gate ───────────────────────────────
                // Wait for the previous launch's app window to actually appear
                // before allowing the next launch.  This naturally absorbs
                // lag bursts — the first queued event launches the app, and
                // subsequent queued events are blocked until the window shows
                // up (or the timeout expires).
                if (mPendingLaunch) {
                    if ((now - mLastLaunchAnyKeyTime) < LAUNCH_COMPLETION_TIMEOUT_MS) {
                        return true;
                    }
                    mPendingLaunch = false; // timeout — allow this launch
                }

                // ── Record & launch ────────────────────────────────────────
                if (mLastLaunchTime.size() > 50) {
                    mLastLaunchTime.clear();
                    mLastEventDownTime.clear();
                }
                mLastLaunchTime.put(keyCode, now);
                mLastEventDownTime.put(keyCode, eventTime);
                mLastLaunchAnyKeyTime = now;
                mPendingLaunch = true;

                launchApp(packageName);
            }
            // Consume both DOWN and UP to prevent system default
            return true;
        }

        // Unmapped, non-system key — pass through immediately (avoid super dispatch overhead)
        return false;
    }

    private boolean isSystemKey(int keyCode) {
        return switch (keyCode) {
            // D-pad navigation
            // System navigation (some remotes/OS versions use these instead of DPAD)
            // Core system keys
            // Volume & power
            case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                 KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
                 KeyEvent.KEYCODE_DPAD_UP_LEFT, KeyEvent.KEYCODE_DPAD_UP_RIGHT,
                 KeyEvent.KEYCODE_DPAD_DOWN_LEFT, KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
                 KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,
                 KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT,
                 KeyEvent.KEYCODE_NAVIGATE_PREVIOUS, KeyEvent.KEYCODE_NAVIGATE_NEXT,
                 KeyEvent.KEYCODE_NAVIGATE_IN, KeyEvent.KEYCODE_NAVIGATE_OUT, KeyEvent.KEYCODE_TAB,
                 KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
                 KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_MENU,
                 KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                 KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP,
                 KeyEvent.KEYCODE_WAKEUP -> true;
            default -> false;
        };
    }

    private boolean isVolumeKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP
            || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    private void launchApp(String packageName) {
        PackageManager pm = getPackageManager();
        Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
        if (launchIntent != null) {
            launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(launchIntent);
        } else {
            Log.e(TAG, "No launch intent for: " + packageName);
        }
    }
}
