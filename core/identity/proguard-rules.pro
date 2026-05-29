# ============================================================================
# core:identity — Device identity hook classes, Gson-serialized IdentityConfig
# ============================================================================

# IdentityConfig — serialized/deserialized via Gson (reflection-based)
-keep class com.multiapp.core.identity.IdentityConfig { *; }

# DeviceIdentityPool — object singleton, called by InstanceManager
-keep class com.multiapp.core.identity.DeviceIdentityPool { *; }

# Hook points — invoked via HookPoint interface + LSPlant reflection hooks
-keep class com.multiapp.core.identity.HookPoint { *; }
-keep class com.multiapp.core.identity.ActivityManagerHook { *; }
-keep class com.multiapp.core.identity.ActivityManagerHook$* { *; }
-keep class com.multiapp.core.identity.BuildFieldSpoof { *; }
-keep class com.multiapp.core.identity.ContentProviderHook { *; }
-keep class com.multiapp.core.identity.DeviceIdentityHook { *; }
-keep class com.multiapp.core.identity.DlopenHook { *; }
-keep class com.multiapp.core.identity.FileSystemHook { *; }
-keep class com.multiapp.core.identity.PackageIdentityHook { *; }
-keep class com.multiapp.core.identity.ProcFsHook { *; }
-keep class com.multiapp.core.identity.SignatureBypass { *; }
