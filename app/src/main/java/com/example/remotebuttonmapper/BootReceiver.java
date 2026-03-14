package com.example.remotebuttonmapper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    private static final String SERVICE_COMPONENT =
            "com.example.remotebuttonmapper/com.example.remotebuttonmapper.ButtonMapperService";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.htc.intent.action.QUICKBOOT_POWERON".equals(action) ||
            Intent.ACTION_USER_PRESENT.equals(action) ||
            Intent.ACTION_DREAMING_STOPPED.equals(action) ||
            "android.media.tv.action.INITIALIZE_PROGRAMS".equals(action)) {

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
