package com.multiapp.core.hook

object NativeHookPolicyResolver {
    const val PR11_REGISTER_NATIVES_DIAGNOSTICS_PROPERTY =
        "debug.multiapp.pr11.register_natives_diagnostics"

    fun resolveProtectedRuntimePolicy(
        propertyReader: (name: String, defaultValue: String) -> String = ::readSystemProperty
    ): NativeHookPolicy {
        return if (isTruthy(propertyReader(PR11_REGISTER_NATIVES_DIAGNOSTICS_PROPERTY, "0"))) {
            NativeHookPolicy.registerNativesDiagnostic()
        } else {
            NativeHookPolicy.baseline()
        }
    }

    private fun readSystemProperty(name: String, defaultValue: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getDeclaredMethod("get", String::class.java, String::class.java)
            get.invoke(null, name, defaultValue) as String
        } catch (_: Throwable) {
            defaultValue
        }
    }

    private fun isTruthy(value: String): Boolean =
        value == "1" || value.equals("true", ignoreCase = true)
}
