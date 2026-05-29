# ============================================================================
# core:model — Data classes serialized with Gson (reflection-based)
# ============================================================================

# All model data classes — Gson uses reflection to serialize/deserialize fields
-keep class com.multiapp.core.model.ApkInfo { *; }
-keep class com.multiapp.core.model.VirtualApp { *; }
-keep class com.multiapp.core.model.DeviceProfile { *; }
-keep class com.multiapp.core.model.ProcessSlot { *; }
-keep class com.multiapp.core.model.StubMapping { *; }
-keep class com.multiapp.core.model.SystemApp { *; }
-keep class com.multiapp.core.model.LauncherItem { *; }
-keep class com.multiapp.core.model.LauncherItem$* { *; }

# Enums — Gson serializes enum names by reflection
-keepclassmembers enum com.multiapp.core.model.** {
    **[] $VALUES;
    public *;
}

# Constants and interfaces
-keep class com.multiapp.core.model.VirtualConstants { *; }
-keep class com.multiapp.core.model.VirtualIntentExtras { *; }
-keep class com.multiapp.core.model.NetworkPolicy { *; }
-keep class com.multiapp.core.model.EngineStatus { *; }
-keep class com.multiapp.core.model.VmResult { *; }
-keep class com.multiapp.core.model.VmResult$* { *; }
-keep class com.multiapp.core.model.SystemAppIcon { *; }
-keep class com.multiapp.core.model.GmsServiceRouter { *; }

# FileItem — if present
-keep class com.multiapp.core.model.FileItem { *; }
