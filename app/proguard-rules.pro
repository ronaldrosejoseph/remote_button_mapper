# Remote Button Mapper — ProGuard Rules
# ========================================

# Keep the accessibility service (system needs to find it by class name)
-keep class com.example.remotebuttonmapper.ButtonMapperService { *; }

# Keep the boot receiver (system needs to instantiate it)
-keep class com.example.remotebuttonmapper.BootReceiver { *; }

# Keep the main activity (referenced in manifest)
-keep class com.example.remotebuttonmapper.MainActivity { *; }

# Keep accessibility service config XML references
-keepattributes *Annotation*

# Remove all Log calls in release builds for security
# (prevents leaking internal info through logcat)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
