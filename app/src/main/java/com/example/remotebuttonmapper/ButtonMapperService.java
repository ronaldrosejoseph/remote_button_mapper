package com.example.remotebuttonmapper;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

public class ButtonMapperService extends AccessibilityService {

    private static final String TAG = "ButtonMapperService";
    private static final String PREFS_NAME = "ButtonMappings";
    private static final String CHANNEL_ID = "button_mapper_service";
    private static final int NOTIFICATION_ID = 1;

    /** Debounce cooldown: ignore repeated launches of the same key within this window. */
    private static final long DEBOUNCE_MS = 300;

    private SharedPreferences mStatePrefs;
    private SharedPreferences mMappingsPrefs;

    /** Cached recording state — volatile so onKeyEvent sees changes from the UI thread instantly. */
    private volatile boolean mIsRecording = false;

    /** In-memory cache: keyCode (int) → packageName. Avoids disk I/O on every key event. */
    private final HashMap<Integer, String> mMappingsCache = new HashMap<>();

    /** Per-keycode timestamp of the last successful launch, used for debouncing. */
    private final HashMap<Integer, Long> mLastLaunchTime = new HashMap<>();

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
        // Not used
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
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            try {
                int keyCode = Integer.parseInt(entry.getKey());
                if (entry.getValue() instanceof String) {
                    mMappingsCache.put(keyCode, (String) entry.getValue());
                }
            } catch (NumberFormatException ignored) {
                // Skip non-numeric keys
            }
        }
        Log.d(TAG, "Loaded " + mMappingsCache.size() + " mappings into cache");
    }

    @SuppressWarnings("deprecation")
    private void startForegroundNotification() {
        try {
            createNotificationChannel();

            Intent notificationIntent = new Intent(this, MainActivity.class);
            notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }

            Notification notification = builder
                    .setContentTitle("Remote Button Mapper")
                    .setContentText("Button mapping is active")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build();

            startForeground(NOTIFICATION_ID, notification);
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

        // 1. Always pass through Navigation/System keys immediately — zero overhead path
        if (isSystemKey(keyCode)) {
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
                // Debounce: skip if same key was launched within DEBOUNCE_MS
                long now = SystemClock.elapsedRealtime();
                Long lastLaunch = mLastLaunchTime.get(keyCode);
                if (lastLaunch != null && (now - lastLaunch) < DEBOUNCE_MS) {
                    Log.d(TAG, "Debounced key " + keyCode + " (too soon)");
                    return true;
                }
                mLastLaunchTime.put(keyCode, now);

                Log.d(TAG, "Mapping found for key " + keyCode + ": " + packageName);
                launchApp(packageName);
            }
            // Consume both DOWN and UP to prevent system default (like opening Netflix)
            return true;
        }

        // Unmapped, non-system key — pass through immediately (avoid super dispatch overhead)
        return false;
    }

    private boolean isSystemKey(int keyCode) {
        switch (keyCode) {
            // D-pad navigation
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_DPAD_UP_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN_LEFT:
            case KeyEvent.KEYCODE_DPAD_DOWN_RIGHT:
            // System navigation (some remotes/OS versions use these instead of DPAD)
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP:
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN:
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT:
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT:
            case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS:
            case KeyEvent.KEYCODE_NAVIGATE_NEXT:
            case KeyEvent.KEYCODE_NAVIGATE_IN:
            case KeyEvent.KEYCODE_NAVIGATE_OUT:
            case KeyEvent.KEYCODE_TAB:
            // Core system keys
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_MENU:
            // Volume & power
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
                return true;
            default:
                return false;
        }
    }

    private boolean launchApp(String packageName) {
        PackageManager pm = getPackageManager();
        Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
        if (launchIntent != null) {
            launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(launchIntent);
            return true;
        } else {
            Log.e(TAG, "No launch intent for: " + packageName);
            return false;
        }
    }
}
