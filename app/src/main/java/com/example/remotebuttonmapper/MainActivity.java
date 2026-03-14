package com.example.remotebuttonmapper;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "ButtonMappings";
    private ListView listMappings;
    private MappingAdapter mappingAdapter;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (!isAccessibilityServiceEnabled()) {
            // Try to self-enable the accessibility service (requires WRITE_SECURE_SETTINGS granted via ADB)
            if (!tryEnableAccessibilityService()) {
                Toast.makeText(this, "Please enable the Accessibility Service", Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                } catch (android.content.ActivityNotFoundException e) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_SETTINGS);
                        startActivity(intent);
                    } catch (Exception ex) {
                        Toast.makeText(this, "Could not open Settings. Please open manually.", Toast.LENGTH_LONG).show();
                    }
                }
            } else {
                Toast.makeText(this, "Accessibility Service enabled automatically!", Toast.LENGTH_SHORT).show();
            }
        }


        
        Context directBootContext = createDeviceProtectedStorageContext();
        prefs = directBootContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        listMappings = findViewById(R.id.list_mappings);
        Button btnAdd = findViewById(R.id.btn_add_mapping);

        btnAdd.setOnClickListener(v -> showAddMappingDialog());

        refreshMappings();

        // Request ignore battery optimizations to prevent service kill
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            String packageName = getPackageName();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + packageName));
                startActivity(intent);
            }
        }

        // Request "Display over other apps" — required on Android 10+ for boot auto-launch
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this,
                        "Please enable 'Display over other apps' for auto-start on boot",
                        Toast.LENGTH_LONG).show();
                try {
                    Intent overlayIntent = new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(overlayIntent);
                } catch (android.content.ActivityNotFoundException e) {
                    // Some TVs don't have the per-app overlay settings page
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                    } catch (Exception ex) {
                        Log.w("MainActivity", "Could not open overlay settings", ex);
                    }
                }
            }
        }

        // TCL's Safety Guard explicitly launches apps on boot. To catch this automatic 
        // launch and hide our app's UI seamlessly, we minimize if the device booted within the last 90 seconds.
        long uptimeMs = android.os.SystemClock.elapsedRealtime();
        if (getIntent().getBooleanExtra("from_boot", false) || uptimeMs < 90 * 1000) {
            Log.i("MainActivity", "Launched during boot phase (uptime " + uptimeMs + "ms) — moving to back");
            moveTaskToBack(true);
        }
        
        // Handle Row Click (Press OK) -> Show Options Dialog
        listMappings.setOnItemClickListener((parent, view, position, id) -> {
            MappingItem item = (MappingItem) parent.getItemAtPosition(position);
            showOptionsDialog(item);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMappings();
    }

    private void showOptionsDialog(MappingItem item) {
        String[] options = {"Modify", "Delete"};

        new android.app.AlertDialog.Builder(this)
            .setTitle("Options for Key " + item.keyCode)
            .setItems(options, (dialog, which) -> {
                if (which == 0) { // Modify
                    showSelectAppDialog(item.keyCode);
                } else if (which == 1) { // Delete
                    prefs.edit().remove(item.keyCode).apply();
                    Toast.makeText(MainActivity.this, "Key " + item.keyCode + " mapping deleted", Toast.LENGTH_SHORT).show();
                    refreshMappings();
                }
            })
            .show();
    }

    private void showSelectAppDialog(String keyCode) {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_add_mapping); // Reusing layout, but will hide "Pressed Key" part or just use list
        // Actually, reusing logic is complex because of key capture. 
        // Let's just create a simple list dialog for selecting app.
        
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Select App for Key " + keyCode);

        List<AppItem> appItems = getInstalledApps();
        ArrayAdapter<AppItem> appAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, appItems);

        builder.setAdapter(appAdapter, (d, which) -> {
             AppItem selectedApp = appItems.get(which);
             prefs.edit().putString(keyCode, selectedApp.packageName).apply();
             refreshMappings();
             Toast.makeText(this, "Updated mapping for Key " + keyCode, Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo service : enabledServices) {
            if (service.getId().contains(getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private boolean tryEnableAccessibilityService() {
        String serviceComponent = getPackageName() + "/" + getPackageName() + ".ButtonMapperService";
        try {
            String enabledServices = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            // Step 1: Remove our service to force a fresh rebind
            String servicesWithout = removeServiceFromList(enabledServices, serviceComponent);
            Settings.Secure.putString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    servicesWithout);

            if (TextUtils.isEmpty(servicesWithout)) {
                Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            }

            Log.d("MainActivity", "Toggling accessibility service OFF to force rebind...");

            // Step 2: Use a background thread with sleep for reliable toggle
            new Thread(() -> {
                try {
                    Thread.sleep(2000); // Wait for system to process removal

                    String currentServices = Settings.Secure.getString(
                            getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

                    String newValue;
                    if (TextUtils.isEmpty(currentServices)) {
                        newValue = serviceComponent;
                    } else if (currentServices.contains(serviceComponent)) {
                        newValue = currentServices;
                    } else {
                        newValue = currentServices + ":" + serviceComponent;
                    }

                    Settings.Secure.putString(
                            getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            newValue);

                    Settings.Secure.putInt(
                            getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_ENABLED,
                            1);

                    Log.i("MainActivity", "Accessibility service toggled ON — service should now be bound.");
                } catch (Exception e) {
                    Log.e("MainActivity", "Failed to re-enable accessibility service after toggle.", e);
                }
            }).start();

            return true;
        } catch (SecurityException e) {
            Log.w("MainActivity", "WRITE_SECURE_SETTINGS not granted. User must enable manually.");
            return false;
        }
    }

    private String removeServiceFromList(String serviceList, String component) {
        if (TextUtils.isEmpty(serviceList)) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (String service : serviceList.split(":")) {
            if (!service.equals(component)) {
                if (result.length() > 0) {
                    result.append(":");
                }
                result.append(service);
            }
        }
        return result.toString();
    }

    private void refreshMappings() {
        Map<String, ?> allEntries = prefs.getAll();
        List<MappingItem> items = new ArrayList<>();
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Validate: key must be a numeric keycode, value must be a valid package name
            if (value instanceof String) {
                String pkg = (String) value;
                if (key.matches("^\\d+$") &&
                    pkg.matches("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")) {
                    items.add(new MappingItem(key, pkg));
                } else {
                    Log.w("MainActivity", "Skipping malformed mapping: " + key + " -> " + pkg);
                }
            }
        }
        mappingAdapter = new MappingAdapter(items);
        listMappings.setAdapter(mappingAdapter);
    }

    private List<AppItem> getInstalledApps() {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(intent, 0);
        
        List<AppItem> appItems = new ArrayList<>();
        for (ResolveInfo info : apps) {
             appItems.add(new AppItem(info.loadLabel(pm).toString(), info.activityInfo.packageName));
        }
        Collections.sort(appItems, (o1, o2) -> o1.name.compareToIgnoreCase(o2.name));
        return appItems;
    }

    private void showAddMappingDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_add_mapping);
        dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);

        TextView textPressedKey = dialog.findViewById(R.id.text_pressed_key);
        ListView listApps = dialog.findViewById(R.id.list_apps);
        Button btnCancel = dialog.findViewById(R.id.btn_cancel_dialog);

        final int[] capturedKeyCode = {-1};

        // Enable Recording Mode
        Context directBootContext = createDeviceProtectedStorageContext();
        SharedPreferences statePrefs = directBootContext.getSharedPreferences("ServiceState", MODE_PRIVATE);
        statePrefs.edit().putBoolean("is_recording", true).apply();

        // Register Receiver
        android.content.BroadcastReceiver keyReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.example.remotebuttonmapper.ACTION_KEY_CAPTURED".equals(intent.getAction())) {
                    int keyCode = intent.getIntExtra("keyCode", -1);
                    capturedKeyCode[0] = keyCode;
                    textPressedKey.setText("Key: " + keyCode);
                }
            }
        };
        registerReceiver(keyReceiver, new android.content.IntentFilter("com.example.remotebuttonmapper.ACTION_KEY_CAPTURED"), android.content.Context.RECEIVER_NOT_EXPORTED);

        // Load Apps
        List<AppItem> appItems = getInstalledApps();
        ArrayAdapter<AppItem> appAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, appItems);
        listApps.setAdapter(appAdapter);

        listApps.setOnItemClickListener((parent, view, position, id) -> {
            if (capturedKeyCode[0] != -1) {
                AppItem selectedApp = appItems.get(position);
                prefs.edit().putString(String.valueOf(capturedKeyCode[0]), selectedApp.packageName).apply();
                refreshMappings();
                dialog.dismiss();
            } else {
                Toast.makeText(this, "Please press a button on the remote first", Toast.LENGTH_SHORT).show();
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        
        dialog.setOnDismissListener(d -> {
            // Disable Recording Mode
            statePrefs.edit().putBoolean("is_recording", false).apply();
            try {
                unregisterReceiver(keyReceiver);
            } catch (IllegalArgumentException e) {
                // Ignore if not registered
            }
        });

        dialog.show();
    }

    // --- Helper Classes ---

    private static class MappingItem {
        String keyCode;
        String packageName;

        MappingItem(String keyCode, String packageName) {
            this.keyCode = keyCode;
            this.packageName = packageName;
        }
    }

    private class MappingAdapter extends BaseAdapter {
        private List<MappingItem> items;

        MappingAdapter(List<MappingItem> items) {
            this.items = items;
        }

        @Override
        public int getCount() { return items.size(); }
        @Override
        public Object getItem(int position) { return items.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_mapping, parent, false);
            }
            MappingItem item = items.get(position);
            
            TextView textKey = convertView.findViewById(R.id.text_key_code);
            TextView textPkg = convertView.findViewById(R.id.text_package_name);

            textKey.setText("Key: " + item.keyCode);
            textPkg.setText(item.packageName);

            return convertView;
        }
    }

    private static class AppItem {
        String name;
        String packageName;
        
        AppItem(String name, String packageName) {
            this.name = name;
            this.packageName = packageName;
        }
        
        @Override
        public String toString() {
            return name;
        }
    }
}
