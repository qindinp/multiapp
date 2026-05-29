# ============================================================================
# core:manifest — Manifest parsing, Gson-serialized config classes
# ============================================================================

# StubConfig — serialized/deserialized via Gson (reflection-based)
-keep class com.multiapp.core.manifest.StubConfig { *; }

# DeviceIdentityConfig — serialized/deserialized via Gson (reflection-based)
-keep class com.multiapp.core.manifest.DeviceIdentityConfig { *; }

# ManifestParser — used by StubBuilder and InstanceManager
-keep class com.multiapp.core.manifest.ManifestParser { *; }
-keep class com.multiapp.core.manifest.ManifestParser$* { *; }

# ManifestGenerator — used by StubBuilder
-keep class com.multiapp.core.manifest.ManifestGenerator { *; }

# ComponentExtractor — used by StubBuilder and InstanceManager
-keep class com.multiapp.core.manifest.ComponentExtractor { *; }

# AuthorityRewriter — used by ManifestGenerator
-keep class com.multiapp.core.manifest.AuthorityRewriter { *; }
