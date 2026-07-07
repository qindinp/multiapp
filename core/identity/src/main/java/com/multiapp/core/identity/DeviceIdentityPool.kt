package com.multiapp.core.identity
import com.multiapp.core.model.IdentityConfig

import com.multiapp.core.common.maskSensitive
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Device identity pool.
 *
 * Generates deterministic device identity configurations for each cloned app
 * instance. Each call to [generateIdentity] with the same instanceId produces
 * the same set of device identifiers, ensuring consistency across multiple
 * systems (StubConfig, IdentitySpoofingEngine, etc.).
 *
 * Uses SecureRandom seeded by instanceId hash for deterministic generation.
 */
object DeviceIdentityPool {

    private val secureRandom by lazy { SecureRandom() }

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

    private fun <T> List<T>.secureRandom(rnd: SecureRandom): T = this[rnd.nextInt(size)]

    /**
     * Create a deterministic SecureRandom seeded by instanceId.
     * Same instanceId always produces the same random sequence.
     */
    private fun createSeededRandom(instanceId: String): SecureRandom {
        val seed = MessageDigest.getInstance("SHA-256")
            .digest(("multiapp_identity_$instanceId").toByteArray())
        return SecureRandom.getInstance("SHA1PRNG").apply {
            setSeed(seed)
        }
    }

    /**
     * Generate a deterministic identity configuration for a cloned app instance.
     *
     * Uses SecureRandom seeded by instanceId hash, ensuring the same instanceId
     * always produces the same identity. This allows IdentitySpoofingEngine and
     * StubConfig to use consistent device identifiers.
     *
     * @param instanceId          Unique identifier for this clone instance
     * @param originalPackageName The original app package name
     * @return A fully populated [IdentityConfig] with deterministic device identifiers
     */
    fun generateIdentity(instanceId: String, originalPackageName: String): IdentityConfig {
        val random = createSeededRandom(instanceId)

        val model = BUILD_MODELS.secureRandom(random)
        val manufacturer = BUILD_MANUFACTURERS.secureRandom(random)
        val brand = BUILD_BRANDS.secureRandom(random)
        val sdkInt = 33 + random.nextInt(3) // 33..35
        val versionRelease = VERSION_RELEASES.secureRandom(random)

        val device = model.lowercase().replace(" ", "_")
        val product = "${device}_user"

        val config = IdentityConfig(
            instanceId = instanceId,
            stubPackageName = "$originalPackageName.clone$instanceId",
            originalPackageName = originalPackageName,
            authorityMap = generateAuthorityMap(originalPackageName, instanceId),
            imei = generateImei(random),
            androidId = generateAndroidId(random),
            macAddress = generateMacAddress(random),
            serial = generateSerial(random),
            buildModel = model,
            buildManufacturer = manufacturer,
            buildFingerprint = "$brand/$device/$device:$versionRelease/${generateBuildId(random)}/${1000000000L + nextSecureLong(random, 8999999999L)}:user/release-keys",
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
            maskSensitive(config.imei)
        )

        return config
    }

    /**
     * Generate a random IMEI (15 digits, starts with 86).
     */
    private fun generateImei(rnd: SecureRandom): String {
        val sb = StringBuilder("86")
        repeat(13) {
            sb.append(rnd.nextInt(10))
        }
        return sb.toString()
    }

    /**
     * Generate a random Android ID (16 hex characters).
     */
    private fun generateAndroidId(rnd: SecureRandom): String {
        return buildString(16) {
            repeat(16) {
                append(HEX_CHARS[rnd.nextInt(HEX_CHARS.length)])
            }
        }
    }

    /**
     * Generate a random MAC address in AA:BB:CC:DD:EE:FF format.
     */
    private fun generateMacAddress(rnd: SecureRandom): String {
        val octets = List(6) {
            String.format("%02X", rnd.nextInt(256))
        }
        return octets.joinToString(":")
    }

    /**
     * Generate a random serial number (10 alphanumeric characters).
     */
    private fun generateSerial(rnd: SecureRandom): String {
        return buildString(10) {
            repeat(10) {
                append(ALPHANUMERIC_CHARS[rnd.nextInt(ALPHANUMERIC_CHARS.length)])
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
            "$originalPackageName.provider" to "$originalPackageName.provider.clone$instanceId",
            "$originalPackageName.fileprovider" to "$originalPackageName.fileprovider.clone$instanceId"
        )
    }

    /**
     * Generate a random Android build ID (e.g. "A1B2C3D4").
     */
    private fun generateBuildId(rnd: SecureRandom): String {
        val letters = ('A'..'Z')
        return "${letters.toList().secureRandom(rnd)}${1 + rnd.nextInt(9)}${letters.toList().secureRandom(rnd)}${100000 + rnd.nextInt(899999)}"
    }

    /**
     * Generate a secure random long in [0, bound) range.
     */
    private fun nextSecureLong(rnd: SecureRandom, bound: Long): Long {
        return (rnd.nextLong().toULong() % bound.toULong()).toLong()
    }
}
