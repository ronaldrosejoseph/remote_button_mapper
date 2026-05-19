package com.example.remotebuttonmapper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED,
                 Intent.ACTION_LOCKED_BOOT_COMPLETED,
                 "android.intent.action.QUICKBOOT_POWERON",
                 "com.htc.intent.action.QUICKBOOT_POWERON",
                 Intent.ACTION_USER_PRESENT,
                 Intent.ACTION_DREAMING_STOPPED,
                 "android.media.tv.action.INITIALIZE_PROGRAMS" -> {

                Log.d(TAG, "Boot completed received: " + action);

                // Use goAsync() so the receiver process stays alive during our toggle work
                final PendingResult pendingResult = goAsync();
                final boolean isFullBoot = !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action);
                final Context appContext = context.getApplicationContext();

                new Thread(() -> {
                    try {
                        // Set flag as fallback for the accessibility service
                        if (isFullBoot) {
                            Context deviceContext = appContext.createDeviceProtectedStorageContext();
                            SharedPreferences statePrefs = deviceContext.getSharedPreferences(
                                    "ServiceState", Context.MODE_PRIVATE);
                            statePrefs.edit().putBoolean("boot_just_completed", true).apply();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error during boot processing", e);
                    } finally {
                        pendingResult.finish();
                    }
                }).start();
            }
        }
    }
}
