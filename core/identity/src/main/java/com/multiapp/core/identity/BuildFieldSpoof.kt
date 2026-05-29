package com.multiapp.core.identity

import android.os.Build
import com.multiapp.core.hook.HookEngine
import timber.log.Timber

/**
 * Build field spoof hook.
 *
 * Phase 4: Overrides android.os.Build static fields using a combination of
 * native JNI field modification and LSPlant (ART hooking) to change values
 * reported by Build.MODEL, Build.MANUFACTURER, Build.FINGERPRINT, etc.
 *
 * Native layer (libbuild_spoof.so) uses JNI SetStaticObjectField for direct
 * field replacement. LSPlant is used as a fallback for fields that cannot be
 * modified via JNI alone.
 */
class BuildFieldSpoof : HookPoint {

    override fun apply(config: IdentityConfig) {
        Timber.d(
            "BuildFieldSpoof: apply called for instance=%s, model=%s, manufacturer=%s",
            config.instanceId,
            config.buildModel,
            config.buildManufacturer
        )
        applyInternal(config)
    }

    companion object {

        private const val TAG = "BuildFieldSpoof"

        init {
            try {
                System.loadLibrary("build_spoof")
            } catch (e: UnsatisfiedLinkError) {
                Timber.tag(TAG).w("libbuild_spoof.so not loaded, using reflection-only mode: %s", e.message)
            }
        }

        /**
         * Native method to set a static field value on the target class.
         * Uses JNI SetStaticObjectField / SetStaticIntField internally.
         *
         * @param className  Fully-qualified class name (e.g. "android.os.Build")
         * @param fieldName  Static field name (e.g. "MODEL")
         * @param value      New value to assign
         */
        @JvmStatic
        external fun nativeSetStaticFieldValue(className: String, fieldName: String, value: Any)

        fun apply(config: IdentityConfig) {
            Timber.d(
                "BuildFieldSpoof: companion apply called for instance=%s",
                config.instanceId
            )
            applyInternal(config)
        }

        private fun applyInternal(config: IdentityConfig) {
            val hookEngine = HookEngine.getInstance()

            // Spoof Build object fields
            spoofBuildStringFields(hookEngine, config)

            // Spoof Build.VERSION fields
            spoofVersionFields(hookEngine, config)

            Timber.tag(TAG).i(
                "BuildFieldSpoof installed for instance=%s, model=%s",
                config.instanceId, config.buildModel
            )
        }

        /**
         * Spoof Build static String fields via reflection + native fallback.
         * Fields: MODEL, MANUFACTURER, FINGERPRINT, BRAND, DEVICE, PRODUCT
         */
        private fun spoofBuildStringFields(
            hookEngine: HookEngine,
            config: IdentityConfig
        ) {
            val fieldMap = mapOf(
                "MODEL" to config.buildModel,
                "MANUFACTURER" to config.buildManufacturer,
                "FINGERPRINT" to config.buildFingerprint,
                "BRAND" to config.buildBrand,
                "DEVICE" to config.buildDevice,
                "PRODUCT" to config.buildProduct
            )

            for ((fieldName, value) in fieldMap) {
                var success = hookEngine.hookStaticField(
                    "android.os.Build",
                    fieldName,
                    value
                )

                if (!success) {
                    // Fallback to native JNI method
                    success = tryNativeSetField("android.os.Build", fieldName, value)
                }

                if (!success) {
                    // Final fallback: direct reflection with final modifier removal
                    success = tryReflectionSetField(
                        Build::class.java, fieldName, value
                    )
                }

                Timber.tag(TAG).d(
                    "Build.%s = %s (success=%b)",
                    fieldName, value, success
                )
            }
        }

        /**
         * Spoof Build.VERSION static fields.
         * Fields: RELEASE (String), SDK_INT (Int)
         */
        private fun spoofVersionFields(
            hookEngine: HookEngine,
            config: IdentityConfig
        ) {
            // RELEASE is a String field
            var success = hookEngine.hookStaticField(
                "android.os.Build\$VERSION",
                "RELEASE",
                config.versionRelease
            )
            if (!success) {
                success = tryNativeSetField(
                    "android.os.Build\$VERSION", "RELEASE", config.versionRelease
                )
            }
            if (!success) {
                success = tryReflectionSetField(
                    Build.VERSION::class.java, "RELEASE", config.versionRelease
                )
            }
            Timber.tag(TAG).d("Build.VERSION.RELEASE = %s (success=%b)", config.versionRelease, success)

            // SDK_INT is an int field
            success = hookEngine.hookStaticField(
                "android.os.Build\$VERSION",
                "SDK_INT",
                config.sdkInt
            )
            if (!success) {
                success = tryNativeSetField(
                    "android.os.Build\$VERSION", "SDK_INT", config.sdkInt
                )
            }
            if (!success) {
                success = tryReflectionSetField(
                    Build.VERSION::class.java, "SDK_INT", config.sdkInt
                )
            }
            Timber.tag(TAG).d("Build.VERSION.SDK_INT = %d (success=%b)", config.sdkInt, success)
        }

        /**
         * Try setting a field via the native JNI method.
         * Returns true if successful.
         */
        private fun tryNativeSetField(
            className: String,
            fieldName: String,
            value: Any
        ): Boolean {
            return try {
                nativeSetStaticFieldValue(className, fieldName, value)
                true
            } catch (e: UnsatisfiedLinkError) {
                Timber.tag(TAG).d("Native method not available for %s.%s", className, fieldName)
                false
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Native set failed for %s.%s", className, fieldName)
                false
            }
        }

        /**
         * Try setting a field via direct reflection, removing final modifier.
         * Returns true if successful.
         */
        private fun tryReflectionSetField(
            clazz: Class<*>,
            fieldName: String,
            value: Any
        ): Boolean {
            return try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true

                // Remove final modifier
                try {
                    val accessFlagsField = java.lang.reflect.Field::class.java
                        .getDeclaredField("accessFlags")
                    accessFlagsField.isAccessible = true
                    accessFlagsField.setInt(
                        field,
                        field.modifiers and java.lang.reflect.Modifier.FINAL.inv()
                    )
                } catch (_: Exception) {
                    try {
                        val modField = java.lang.reflect.Field::class.java
                            .getDeclaredField("modifiers")
                        modField.isAccessible = true
                        modField.setInt(
                            field,
                            field.modifiers and java.lang.reflect.Modifier.FINAL.inv()
                        )
                    } catch (_: Exception) { /* best effort */ }
                }

                field.set(null, value)
                true
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Reflection set failed for %s.%s", clazz.name, fieldName)
                false
            }
        }
    }
}
