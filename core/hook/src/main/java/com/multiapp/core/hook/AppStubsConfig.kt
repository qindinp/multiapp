package com.multiapp.core.hook

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Per-app configuration for native method stubs, packer class names,
 * and obfuscated type mappings.
 *
 * Loaded from `assets/stubs/{packageName}.json`. When no config file
 * exists for a given package, callers fall back to hardcoded defaults.
 *
 * Thread-safe: instances are immutable after construction.
 */
data class AppStubsConfig(
    val packageName: String,
    val knownPacker: String? = null,
    val packerStubClasses: List<String> = emptyList(),
    val packerInterfaceMethods: Map<String, String> = emptyMap(),
    val nativeMethodStubs: Map<String, List<NativeMethodEntry>> = emptyMap(),
    val registerNativesInterceptClasses: Map<String, List<NativeMethodEntry>> = emptyMap(),
    val registerNativesLogClasses: List<String> = emptyList(),
    val obfuscatedTypeMap: Map<String, String> = emptyMap(),
    val headerProviderClass: String? = null,
    val findClassTargets: List<String> = emptyList(),
    val protocolFallbackClasses: Map<String, String> = emptyMap(),
    val easyEncryptMd5Key: String? = null,
) {
    data class NativeMethodEntry(
        val name: String,
        val signature: String,
        val role: String? = null,
    )

    companion object {
        private const val TAG = "AppStubsConfig"
        private const val ASSETS_DIR = "stubs"

        private val cache = mutableMapOf<String, AppStubsConfig?>()

        @Synchronized
        fun load(context: Context, packageName: String): AppStubsConfig? {
            cache[packageName]?.let { return it }

            val config = try {
                val path = "$ASSETS_DIR/$packageName.json"
                val json = context.assets.open(path).bufferedReader().use { it.readText() }
                fromJson(json)
            } catch (e: Exception) {
                Log.d(TAG, "No stubs config for $packageName: ${e.message}")
                null
            }

            cache[packageName] = config
            return config
        }

        fun fromJson(json: String): AppStubsConfig {
            val root = JSONObject(json)

            val packerStubClasses = root.optJSONArray("packerStubClasses")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList()

            val packerInterfaceMethods = root.optJSONObject("packerInterfaceMethods")
                ?.let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }
                ?: emptyMap()

            val nativeMethodStubs = root.optJSONObject("nativeMethodStubs")
                ?.let { parseNativeMethodStubsMap(it) }
                ?: emptyMap()

            val interceptClasses = root.optJSONObject("registerNativesInterceptClasses")
                ?.let { parseNativeMethodStubsMap(it) }
                ?: emptyMap()

            val logClasses = root.optJSONArray("registerNativesLogClasses")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList()

            val obfuscatedTypeMap = root.optJSONObject("obfuscatedTypeMap")
                ?.let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }
                ?: emptyMap()

            val findClassTargets = root.optJSONArray("findClassTargets")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList()

            val protocolFallbackClasses = root.optJSONObject("protocolFallbackClasses")
                ?.let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }
                ?: emptyMap()

            return AppStubsConfig(
                packageName = root.getString("packageName"),
                knownPacker = root.optJSONObject("packer")?.optString("type"),
                packerStubClasses = packerStubClasses,
                packerInterfaceMethods = packerInterfaceMethods,
                nativeMethodStubs = nativeMethodStubs,
                registerNativesInterceptClasses = interceptClasses,
                registerNativesLogClasses = logClasses,
                obfuscatedTypeMap = obfuscatedTypeMap,
                headerProviderClass = root.optJSONObject("onlineReading")
                    ?.optString("headerProviderClass"),
                findClassTargets = findClassTargets,
                protocolFallbackClasses = protocolFallbackClasses,
                easyEncryptMd5Key = root.optJSONObject("crypto")
                    ?.optString("easyEncryptMd5Key"),
            )
        }

        private fun parseNativeMethodStubsMap(
            obj: JSONObject
        ): Map<String, List<NativeMethodEntry>> {
            return obj.keys().asSequence().associateWith { className ->
                val arr = obj.getJSONArray(className)
                (0 until arr.length()).map { i ->
                    val entry = arr.getJSONObject(i)
                    NativeMethodEntry(
                        name = entry.getString("name"),
                        signature = entry.getString("signature"),
                        role = entry.optString("role").takeIf { it.isNotEmpty() },
                    )
                }
            }
        }

        @Synchronized
        fun clearCache() {
            cache.clear()
        }
    }
}
