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

    // With key filtering on, every key waits for this service before reaching
    // the app. On a loaded TV the pipeline backs up, then flushes, and the
    // focused list jumps several steps at once. These values drain the flush.
    private static final long STALE_MS = 300;
    private static final long BURST_GAP_MS = 40;
    private static final long BURST_STALE_MS = 200;
    private static final long BURST_COOLDOWN_MS = 400;

    private SharedPreferences mStatePrefs;
    private SharedPreferences mMappingsPrefs;
    private volatile boolean mIsRecording = false;
    private final HashMap<Integer, String> mMappingsCache = new HashMap<>();
    private long mLastSystemKeyDownTime;
    private long mSystemBurstCooldownUntil;

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
                }
            };

    private final SharedPreferences.OnSharedPreferenceChangeListener mStateListener =
            (prefs, key) -> {
                if ("is_recording".equals(key)) {
                    mIsRecording = prefs.getBoolean(key, false);
                }
            };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Service Connected");

        Context directBootContext = createDeviceProtectedStorageContext();
        mStatePrefs = directBootContext.getSharedPreferences("ServiceState", MODE_PRIVATE);
        mMappingsPrefs = directBootContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        mIsRecording = mStatePrefs.getBoolean("is_recording", false);
        loadMappingsCache();

        mMappingsPrefs.registerOnSharedPreferenceChangeListener(mMappingsListener);
        mStatePrefs.registerOnSharedPreferenceChangeListener(mStateListener);

        startForegroundNotification();

        try {
            Toast.makeText(this, "✓ Remote Button Mapper is active", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Could not show toast", e);
        }
    }

    @Override
    public void onDestroy() {
        if (mMappingsPrefs != null) {
            mMappingsPrefs.unregisterOnSharedPreferenceChangeListener(mMappingsListener);
        }
        if (mStatePrefs != null) {
            mStatePrefs.unregisterOnSharedPreferenceChangeListener(mStateListener);
        }
        Log.w(TAG, "Service destroyed.");
        super.onDestroy();
    }

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
            Log.i(TAG, "Foreground notification started.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground notification", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Button Mapper Service",
                    NotificationManager.IMPORTANCE_LOW);
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
        boolean isDown = event.getAction() == KeyEvent.ACTION_DOWN;

        // 1. System/navigation keys — pass through, but eat a lag burst so a
        // queued flush does not jump the focus several steps at once.
        if (isSystemKey(keyCode)) {
            return isDown && shouldSwallowSystemKey(event);
        }

        // Staleness check: if the event spent >300ms queued in the system
        // during a lag spike (e.g. TV startup at 100% utilization), discard
        // it.  Normal delivery delay is ~5-50ms.
        if (isDown && SystemClock.uptimeMillis() - event.getEventTime() > STALE_MS) {
            return true;
        }

        // Re-initialize prefs if null (service restart edge case)
        if (mStatePrefs == null || mMappingsPrefs == null) {
            Context directBootContext = createDeviceProtectedStorageContext();
            mStatePrefs = directBootContext.getSharedPreferences("ServiceState", MODE_PRIVATE);
            mMappingsPrefs = directBootContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            mIsRecording = mStatePrefs.getBoolean("is_recording", false);
            loadMappingsCache();
            mMappingsPrefs.registerOnSharedPreferenceChangeListener(mMappingsListener);
            mStatePrefs.registerOnSharedPreferenceChangeListener(mStateListener);
        }

        // 2. Recording Mode
        if (mIsRecording) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode < 0 || keyCode > 10000) {
                    return true;
                }
                Intent intent = new Intent("com.example.remotebuttonmapper.ACTION_KEY_CAPTURED");
                intent.setPackage(getPackageName());
                intent.putExtra("keyCode", keyCode);
                sendBroadcast(intent);
            }
            return true;
        }

        // 3. Normal Mapping Mode
        if (event.getRepeatCount() > 0) return true;
        String packageName = mMappingsCache.get(keyCode);
        if (packageName != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                launchApp(packageName);
            }
            return true;
        }

        return false;
    }

    /**
     * True when a system-key DOWN belongs to a queued lag burst and should be
     * swallowed. A human cannot press two remote buttons within 40ms, so a
     * tight delivery pair with an already-stale event time means the input
     * pipeline is flushing its backlog. Eat system keys briefly to drain it.
     */
    private boolean shouldSwallowSystemKey(KeyEvent event) {
        long now = SystemClock.uptimeMillis();
        long delay = now - event.getEventTime();

        if (now < mSystemBurstCooldownUntil) {
            return true;
        }

        long gap = now - mLastSystemKeyDownTime;
        boolean hasPrevious = mLastSystemKeyDownTime != 0;
        mLastSystemKeyDownTime = now;

        if (hasPrevious && delay > BURST_STALE_MS && gap < BURST_GAP_MS) {
            mSystemBurstCooldownUntil = now + BURST_COOLDOWN_MS;
            Log.d(TAG, "Lag burst (delay " + delay + "ms) — swallowing system keys");
            return true;
        }

        return delay > STALE_MS;
    }

    private boolean isSystemKey(int keyCode) {
        return switch (keyCode) {
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
