# ============================================================================
# core:loader — AppComponentFactory entry point for Stub APK
# ============================================================================

# LoaderFactory — declared as appComponentFactory in AndroidManifest.xml
# System instantiates via reflection using this class name
-keep class com.multiapp.core.loader.LoaderFactory { *; }

# LoadedApkSwapper — uses reflection on ActivityThread internals
-keep class com.multiapp.core.loader.LoadedApkSwapper { *; }

# NativeLibHandler
-keep class com.multiapp.core.loader.NativeLibHandler { *; }
