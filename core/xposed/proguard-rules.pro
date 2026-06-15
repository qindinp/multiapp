# ============================================================================
# core:xposed — Xposed API compatibility layer
# ============================================================================

# Xposed API classes — accessed by Xposed modules via reflection
-keep class de.robv.android.xposed.** { *; }

# XC_MethodHook$MethodHookParam — accessed via reflection by modules
-keep class de.robv.android.xposed.XC_MethodHook$MethodHookParam { *; }

# XposedBridge — entry point for Xposed modules
-keep class de.robv.android.xposed.XposedBridge { *; }
-keep class de.robv.android.xposed.XposedBridgeImpl { *; }

# XposedHelpers — reflection-heavy utility class
-keep class de.robv.android.xposed.XposedHelpers { *; }

# ModuleLoader — loads Xposed module DEX files
-keep class de.robv.android.xposed.ModuleLoader { *; }
-keep class de.robv.android.xposed.ModuleLoader$* { *; }
