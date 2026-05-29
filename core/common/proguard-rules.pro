# ============================================================================
# core:common — Reflection utilities, HiddenApiBypass, Unsafe access
# ============================================================================

# ReflectionUtils — extension functions used via reflection across all modules
-keep class com.multiapp.core.common.ReflectionUtilsKt { *; }

# AndroidCompat — object singleton, uses VMRuntime + Unsafe reflection
-keep class com.multiapp.core.common.AndroidCompat { *; }

# CrashReporter
-keep class com.multiapp.core.common.CrashReporter { *; }

# Hidden API bypass library
-keep class org.lsposed.hiddenapibypass.** { *; }
