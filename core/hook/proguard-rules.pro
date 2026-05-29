# ============================================================================
# core:hook — LSPlant hook engine, native JNI bridge, shadowhook, dexlib2
# ============================================================================

# LSPlant hook framework — loaded via Class.forName("io.github.lsplant.LSPlant")
-keep class io.github.lsplant.** { *; }

# shadowhook native hook library
-keep class com.bytedance.shadowhook.** { *; }

# Native methods — JNI bridge to libmultiapp-native.so
-keepclasseswithmembernames class * {
    native <methods>;
}

# HookEngine — uses LSPlant via reflection (Class.forName, getMethod)
-keep class com.multiapp.core.hook.HookEngine { *; }
-keep class com.multiapp.core.hook.HookEngine$* { *; }

# NativeHookBridge — JNI native methods + reflection-based setupForLoader
-keep class com.multiapp.core.hook.NativeHookBridge { *; }
-keep class com.multiapp.core.hook.NativeHookBridge$* { *; }

# FileAccessInterceptor — used by loader module
-keep class com.multiapp.core.hook.FileAccessInterceptor { *; }

# Anti-detection bypass classes — hook via reflection on packer classes
-keep class com.multiapp.core.hook.antidetection.** { *; }

# Hook engine internals
-keep class com.multiapp.core.hook.AntiDetectionEngine { *; }
-keep class com.multiapp.core.hook.IdentitySpoofingEngine { *; }
-keep class com.multiapp.core.hook.RuntimeInspector { *; }
-keep class com.multiapp.core.hook.SpeedController { *; }
-keep class com.multiapp.core.hook.TimePrisonManager { *; }
-keep class com.multiapp.core.hook.VulnerabilityRadar { *; }
-keep class com.multiapp.core.hook.AppGenomeMapper { *; }
-keep class com.multiapp.core.hook.BinaryDiffEngine { *; }

# DEX patching — uses dexlib2 reflection
-keep class com.multiapp.core.hook.dexpatch.** { *; }
-keep class org.jf.dexlib2.** { *; }
