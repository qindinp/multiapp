# ============================================================================
# core:apk — APK parsing, ClassLoader creation, Unsafe + reflection
# ============================================================================

# ApkParser — uses AssetManager reflection (addAssetPath, openXmlResourceParser)
-keep class com.multiapp.core.apk.ApkParser { *; }

# VirtualClassLoader — uses Unsafe, Class.forName on dalvik.system and internal APIs
-keep class com.multiapp.core.apk.VirtualClassLoader { *; }
