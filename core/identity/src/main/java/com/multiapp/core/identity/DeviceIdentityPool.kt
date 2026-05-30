package com.multiapp.core.identity

import timber.log.Timber
import kotlin.random.Random

/**
 * Device identity pool.
 *
 * Generates randomized but realistic device identity configurations for
 * each cloned app instance. Each call to [generateIdentity] produces a
 * unique set of device identifiers that looks like a real device.
 */
object DeviceIdentityPool {

    private val BUILD_MODELS = listOf(
        "SM-S9380",
        "SM-S9360",
        "SM-S9310",
        "SM-S9280",
        "Pixel 9 Pro",
        "Pixel 9",
        "Pixel 8 Pro",
        "23127PN0CC",
        "2304FPN6DC",
        "RMX3700",
        "V2305A",
        "PGKM10",
        "ASUS_AI2401",
        "LE2120",
        "NX769J"
    )

    private val BUILD_MANUFACTURERS = listOf(
        "samsung",
        "Google",
        "Xiaomi",
        "OPPO",
        "vivo",
        "OnePlus",
        "ASUS",
        "ZTE",
        "realme"
    )

    private val BUILD_BRANDS = listOf(
        "samsung",
        "google",
        "Xiaomi",
        "OPPO",
        "vivo",
        "OnePlus",
        "asus",
        "nubia",
        "realme"
    )

    private val VERSION_RELEASES = listOf("13", "14", "15")

    private const val HEX_CHARS = "0123456789abcdef"
    private const val ALPHANUMERIC_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /**
     * Generate a random identity configuration for a cloned app instance.
     *
     * @param instanceId          Unique identifier for this clone instance
     * @param originalPackageName The original app package name
     * @return A fully populated [IdentityConfig] with randomized device identifiers
     */
    fun generateIdentity(instanceId: String, originalPackageName: String): IdentityConfig {
        val model = BUILD_MODELS.random()
        val manufacturer = BUILD_MANUFACTURERS.random()
        val brand = BUILD_BRANDS.random()
        val sdkInt = Random.nextInt(33, 36)
        val versionRelease = VERSION_RELEASES.random()

        val device = model.lowercase().replace(" ", "_")
        val product = "${device}_user"

        val config = IdentityConfig(
            instanceId = instanceId,
            stubPackageName = "$originalPackageName.clone$instanceId",
            originalPackageName = originalPackageName,
            authorityMap = generateAuthorityMap(originalPackageName, instanceId),
            imei = generateImei(),
            androidId = generateAndroidId(),
            macAddress = generateMacAddress(),
            serial = generateSerial(),
            buildModel = model,
            buildManufacturer = manufacturer,
            buildFingerprint = "$brand/$device/$device:$versionRelease/${generateBuildId()}/${Random.nextLong(1000000000L, 9999999999L)}:user/release-keys",
            buildBrand = brand,
            buildDevice = device,
            buildProduct = product,
            versionRelease = versionRelease,
            sdkInt = sdkInt
        )

        Timber.d(
            "DeviceIdentityPool: generated identity for instance=%s, model=%s, imei=%s",
            instanceId,
            config.buildModel,
            config.imei
        )

        return config
    }

    /**
     * Generate a random IMEI (15 digits, starts with 86).
     */
    private fun generateImei(): String {
        val sb = StringBuilder("86")
        repeat(13) {
            sb.append(Random.nextInt(0, 10))
        }
        return sb.toString()
    }

    /**
     * Generate a random Android ID (16 hex characters).
     */
    private fun generateAndroidId(): String {
        return buildString(16) {
            repeat(16) {
                append(HEX_CHARS[Random.nextInt(HEX_CHARS.length)])
            }
        }
    }

    /**
     * Generate a random MAC address in AA:BB:CC:DD:EE:FF format.
     */
    private fun generateMacAddress(): String {
        val octets = List(6) {
            String.format("%02X", Random.nextInt(0, 256))
        }
        return octets.joinToString(":")
    }

    /**
     * Generate a random serial number (10 alphanumeric characters).
     */
    private fun generateSerial(): String {
        return buildString(10) {
            repeat(10) {
                append(ALPHANUMERIC_CHARS[Random.nextInt(ALPHANUMERIC_CHARS.length)])
            }
        }
    }

    /**
     * Generate an authority map for ContentProviders.
     * Rewrites original authorities to unique per-instance authorities.
     */
    private fun generateAuthorityMap(
        originalPackageName: String,
        instanceId: String
    ): Map<String, String> {
        // 与 AuthorityRewriter 保持一致：直接追加 .$instanceId
        return mapOf(
            "$originalPackageName.provider" to "$originalPackageName.provider.$instanceId",
            "$originalPackageName.fileprovider" to "$originalPackageName.fileprovider.$instanceId"
        )
    }

    /**
     * Generate a random Android build ID (e.g. "A1B2C3D4").
     */
    private fun generateBuildId(): String {
        val letters = ('A'..'Z')
        return "${letters.random()}${Random.nextInt(1, 10)}${letters.random()}${Random.nextInt(100000, 999999)}"
    }
}
