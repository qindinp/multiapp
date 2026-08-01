# ============================================
# MultiApp ProGuard Rules
# ============================================

# --- Hilt DI ---
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# --- Model classes (Gson serialization) ---
# -keep class com.multiapp.core.model.** { *; }

# 只保留需要序列化的数据类
-keep class com.multiapp.core.model.VirtualConstants { *; }
-keep class com.multiapp.core.model.VirtualAppInfo { *; }
-keep class com.multiapp.core.model.InstanceConfig { *; }

# 保留所有使用 @SerializedName 注解的字段
-keepclassmembers class com.multiapp.core.model.** {
    @com.google.gson.annotations.SerializedName <fields>;
}

# 保留 Parcelable 相关
-keep class com.multiapp.core.model.** implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keep class com.multiapp.core.manifest.StubConfig { *; }
-keep class com.multiapp.core.manifest.DeviceIdentityConfig { *; }
-keepclassmembers class * { @com.google.gson.annotations.SerializedName <fields>; }
-keepattributes Signature
-keepattributes *Annotation*

# --- LoaderFactory (appComponentFactory, called by system via reflection) ---
-keep class com.multiapp.core.loader.LoaderFactory { *; }
-keep class com.multiapp.core.loader.LoaderFactory$SignatureDisguiseManager { *; }
-keep class com.multiapp.core.loader.LoadedApkSwapper { *; }
-keep class com.multiapp.core.loader.NativeLibHandler { *; }

# --- HookPoint interface (reflection) ---
-keep class com.multiapp.core.identity.HookPoint { *; }

# --- All Hook points (instantiated by LoaderFactory) ---
-keep class com.multiapp.core.identity.*Hook { *; }
-keep class com.multiapp.core.identity.*Spoof { *; }
-keep class com.multiapp.core.identity.*Bypass { *; }
-keep class com.multiapp.core.identity.SignatureBypass { *; }
-keep class com.multiapp.core.identity.BuildFieldSpoof { *; }

# --- NativeHookBridge JNI methods ---
-keep class com.multiapp.core.hook.NativeHookBridge { native <methods>; }
-keep class com.multiapp.core.hook.HookEngine { *; }

# --- LSPlant / ShadowHook ---
-keep class org.lsposed.lsplant.** { *; }
-keep class com.bytedance.shadowhook.** { *; }

# --- Anti-detection (reflection-heavy) ---
-keep class com.multiapp.core.hook.AntiDetectionEngine { *; }
-keep class com.multiapp.core.hook.DetectionLevel { *; }
-keep class com.multiapp.core.hook.antidetection.** { *; }

# --- StubBuilder (runtime reflection on Android internals) ---
-keep class com.multiapp.core.stub.StubBuilder { *; }
-keep class com.multiapp.core.stub.ApkSigningHelper { *; }

# --- InstanceManager ---
-keep class com.multiapp.core.instance.InstanceManager { *; }
-keep class com.multiapp.core.instance.InstanceInfo { *; }
-keep class com.multiapp.core.instance.InstanceStatus { *; }

# --- Installer ---
-keep class com.multiapp.core.installer.StubInstaller { *; }
-keep class com.multiapp.core.installer.StubInstaller$InstallResult { *; }

# --- CrashReporter ---
-keep class com.multiapp.core.common.CrashReporter { *; }

# --- Android API hidden access ---
-dontwarn android.app.ActivityThread
-dontwarn android.app.LoadedApk
-dontwarn android.content.pm.PackageInstaller
-dontwarn dalvik.system.InMemoryDexClassLoader
