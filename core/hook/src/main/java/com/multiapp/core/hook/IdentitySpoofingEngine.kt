package com.multiapp.core.hook

import android.content.Context
import android.os.Build
import com.multiapp.core.common.AndroidCompat
import com.multiapp.core.common.findField
import com.multiapp.core.common.removeFinalModifier
import com.multiapp.core.common.runSafe
import com.multiapp.core.model.DeviceProfile
import timber.log.Timber
import java.security.MessageDigest
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentitySpoofingEngine @Inject constructor(
    private val hookEngine: HookEngine,
    private val timePrisonManager: TimePrisonManager
) {
    companion object {
        private const val TAG = "IdentitySpoof"
        private val BUILD_FIELDS = listOf(
            "BRAND", "MANUFACTURER", "MODEL", "DEVICE", "PRODUCT",
            "BOARD", "HARDWARE", "FINGERPRINT", "DISPLAY", "HOST",
            "ID", "TAGS", "TYPE", "USER"
        )
        private val VERSION_FIELDS = listOf(
            "RELEASE", "INCREMENTAL", "CODENAME", "BASE_OS", "SECURITY_PATCH"
        )
        private val VERSION_INT_FIELDS = listOf("SDK_INT", "PREVIEW_SDK_INT")
    }

    private var initialized = false
    private val identityCache = ConcurrentHashMap<String, String>()
    private val spoofedInstances = mutableMapOf<String, SpoofState>()
    private var originalBuildValues: Map<String, Any?> = emptyMap()
    private var originalVersionValues: Map<String, Any?> = emptyMap()
    private var originalSerial: String? = null
    private var isGlobalSpoofApplied = false

    fun initialize() {
        if (initialized) return
        initialized = true
        Timber.tag(TAG).i("IdentitySpoofingEngine initialized")
    }

    fun applyDeviceProfile(profile: DeviceProfile, instanceId: String) {
        Timber.tag(TAG).i("Applying device profile '${profile.name}' for instance: $instanceId")
        if (!isGlobalSpoofApplied) backupOriginalValues()
        spoofBuildFields(profile)
        spoofBuildVersionFields(profile)
        spoofSerial(profile)
        spoofAndroidId(instanceId)
        spoofTelephony(profile, instanceId)
        spoofWifi(profile, instanceId)
        spoofTimezone(profile)
        installLsplantMethodHooks(instanceId)
        if (!timePrisonManager.isActive(instanceId)) {
            timePrisonManager.configureTimePrison(instanceId, TimePrisonConfig())
        }
        spoofedInstances[instanceId] = SpoofState(
            profile = profile, appliedAt = System.currentTimeMillis(),
            androidId = generateConsistentAndroidId(instanceId),
            spoofedFields = BUILD_FIELDS + VERSION_FIELDS + listOf("SERIAL")
        )
        isGlobalSpoofApplied = true
        Timber.tag(TAG).i("Device profile applied: ${countSpoofedFields()} fields modified")
    }

    fun spoofBuildFields(profile: DeviceProfile) {
        val buildClass = Build::class.java
        val fieldMap = mapOf(
            "BRAND" to profile.brand, "MANUFACTURER" to profile.manufacturer,
            "MODEL" to profile.model, "DEVICE" to profile.device,
            "PRODUCT" to profile.product, "BOARD" to profile.board,
            "HARDWARE" to profile.hardware, "FINGERPRINT" to profile.fingerprint,
            "DISPLAY" to profile.buildId,
            "HOST" to "build.${profile.manufacturer.lowercase()}.com",
            "ID" to profile.buildId, "TAGS" to "release-keys",
            "TYPE" to "user", "USER" to "android-build"
        )
        var successCount = 0
        for ((fieldName, value) in fieldMap) {
            if (setStaticStringField(buildClass, fieldName, value)) successCount++
        }
        Timber.tag(TAG).d("Spoofed $successCount/${fieldMap.size} Build fields")
    }

    fun spoofBuildVersionFields(profile: DeviceProfile) {
        val versionClass = Build.VERSION::class.java
        val stringFields = mapOf(
            "RELEASE" to profile.androidVersion,
            "INCREMENTAL" to extractIncremental(profile.buildId),
            "CODENAME" to "REL", "BASE_OS" to "",
            "SECURITY_PATCH" to generateSecurityPatch(profile.buildId)
        )
        var successCount = 0
        for ((fieldName, value) in stringFields) {
            if (setStaticStringField(versionClass, fieldName, value)) successCount++
        }
        if (setStaticIntField(versionClass, "SDK_INT", profile.sdkInt)) successCount++
        if (setStaticIntField(versionClass, "PREVIEW_SDK_INT", 0)) successCount++
        Timber.tag(TAG).d("Spoofed $successCount/${stringFields.size + 2} Build.VERSION fields")
    }

    fun spoofSerial(profile: DeviceProfile) {
        val serial = profile.serial.ifEmpty { generateConsistentSerial(profile.fingerprint) }
        if (setStaticStringField(Build::class.java, "SERIAL", serial)) {
            Timber.tag(TAG).d("Spoofed Build.SERIAL = $serial")
        }
        hookEngine.hookStaticField("android.os.Build", "SERIAL", serial)
    }

    fun spoofAndroidId(instanceId: String) {
        val spoofedId = generateConsistentAndroidId(instanceId)
        try {
            val secureClass = Class.forName("android.provider.Settings\$Secure")
            val cacheField = findField(secureClass, "sNameValueCache")
            if (cacheField != null) {
                cacheField.isAccessible = true
                val cache = cacheField.get(null)
                if (cache != null) {
                    val valuesField = findField(cache::class.java, "mValues")
                    if (valuesField != null) {
                        valuesField.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        val values = valuesField.get(cache) as? MutableMap<String, String>
                        if (values != null) {
                            values["android_id"] = spoofedId
                            Timber.tag(TAG).d("Injected ANDROID_ID into Settings cache: $spoofedId")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to spoof ANDROID_ID via Settings cache")
        }
        Timber.tag(TAG).d("ANDROID_ID for instance $instanceId: $spoofedId")
    }

    fun spoofAndroidId(context: Context, instanceId: String) {
        val spoofedId = generateConsistentAndroidId(instanceId)
        try {
            spoofAndroidId(instanceId)
            Timber.tag(TAG).d("ANDROID_ID spoofed for instance $instanceId: $spoofedId")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to spoof ANDROID_ID with context")
        }
    }

    fun spoofTelephony(profile: DeviceProfile, instanceId: String) {
        try {
            val imei = profile.imei.ifEmpty { generateConsistentImei(instanceId) }
            val imsi = generateConsistentImsi(instanceId, profile.mcc, profile.mnc)
            val phoneNumber = generateConsistentPhone(instanceId)
            val simSerial = generateConsistentSimSerial(instanceId)
            val spoofState = spoofedInstances[instanceId]
            spoofedInstances[instanceId] = (spoofState ?: SpoofState(
                profile = profile, appliedAt = System.currentTimeMillis(),
                androidId = generateConsistentAndroidId(instanceId)
            )).copy(imei = imei, imsi = imsi, phoneNumber = phoneNumber, simSerial = simSerial)
            try {
                val tmClass = Class.forName("android.telephony.TelephonyManager")
                val deviceIdField = findField(tmClass, "mImei")
                if (deviceIdField != null) {
                    deviceIdField.isAccessible = true
                    Timber.tag(TAG).d("Found TelephonyManager.mImei field for injection")
                }
            } catch (_: Exception) { }
            Timber.tag(TAG).d("Telephony spoofed for instance $instanceId: IMEI=$imei")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to spoof telephony for instance $instanceId")
        }
    }

    fun spoofWifi(profile: DeviceProfile, instanceId: String) {
        val macAddress = profile.macAddress.ifEmpty { generateConsistentMac(instanceId) }
        try {
            val wifiInfoClass = Class.forName("android.net.wifi.WifiInfo")
            val macField = findField(wifiInfoClass, "mMacAddress")
            if (macField != null) Timber.tag(TAG).d("Found WifiInfo.mMacAddress for injection")
            val state = spoofedInstances[instanceId]
            if (state != null) spoofedInstances[instanceId] = state.copy(macAddress = macAddress)
            Timber.tag(TAG).d("WiFi MAC spoofed for instance $instanceId: $macAddress")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to spoof WiFi MAC for instance $instanceId")
        }
    }

    fun spoofTimezone(profile: DeviceProfile) {
        if (profile.timezone.isEmpty() || profile.timezone == "UTC") return
        try {
            val tz = TimeZone.getTimeZone(profile.timezone)
            TimeZone.setDefault(tz)
            Timber.tag(TAG).d("Timezone spoofed to: ${profile.timezone}")
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to spoof timezone to ${profile.timezone}: ${e.message}")
        }
    }

    fun spoofAdvertisingId(instanceId: String) {
        val adId = generateConsistentUuid(instanceId, "adid")
        val state = spoofedInstances[instanceId]
        if (state != null) spoofedInstances[instanceId] = state.copy(advertisingId = adId)
        Timber.tag(TAG).d("Advertising ID spoofed for instance $instanceId: $adId")
    }

    fun generateConsistentAndroidId(instanceId: String): String {
        return sha256Hex("multiapp_android_id_$instanceId").take(16)
    }

    private fun generateConsistentImei(instanceId: String): String {
        val hash = sha256Hex("multiapp_imei_$instanceId")
        val tac = "35${hash.filter { it.isDigit() }.take(6)}"
        val snr = hash.filter { it.isDigit() }.drop(6).take(6)
        val body = "$tac$snr".take(14).padEnd(14, '0')
        return body + luhnCheckDigit(body)
    }

    private fun generateConsistentImsi(instanceId: String, mcc: String, mnc: String): String {
        val effectiveMcc = mcc.ifEmpty { "310" }
        val effectiveMnc = mnc.ifEmpty { "260" }
        val hash = sha256Hex("multiapp_imsi_$instanceId")
        val msin = hash.filter { it.isDigit() }.take(15 - effectiveMcc.length - effectiveMnc.length)
        return "$effectiveMcc$effectiveMnc$msin".take(15).padEnd(15, '0')
    }

    private fun generateConsistentPhone(instanceId: String): String {
        val hash = sha256Hex("multiapp_phone_$instanceId")
        return "+1${hash.filter { it.isDigit() }.take(10)}"
    }

    private fun generateConsistentSimSerial(instanceId: String): String {
        val hash = sha256Hex("multiapp_sim_$instanceId")
        return "89${hash.filter { it.isDigit() }.take(18)}".take(20).padEnd(20, '0')
    }

    private fun generateConsistentMac(instanceId: String): String {
        val hash = sha256Hex("multiapp_mac_$instanceId")
        val bytes = hash.take(12)
        val firstByte = (bytes.take(2).toInt(16) or 0x02) and 0xFE
        val rest = bytes.drop(2).chunked(2).joinToString(":")
        return String.format("%02x:%s", firstByte, rest)
    }

    private fun generateConsistentSerial(seed: String): String {
        return sha256Hex("multiapp_serial_$seed").uppercase().take(16)
    }

    private fun generateConsistentUuid(instanceId: String, purpose: String): String {
        val hash = sha256Hex("multiapp_${purpose}_$instanceId")
        return "${hash.substring(0, 8)}-${hash.substring(8, 12)}-4${hash.substring(13, 16)}-${hash.substring(16, 20)}-${hash.substring(20, 32)}"
    }

    private fun installLsplantMethodHooks(instanceId: String) {
        val state = spoofedInstances[instanceId] ?: return
        val imei = state.imei; val imsi = state.imsi; val simSerial = state.simSerial
        val macAddress = state.macAddress
        val serial = state.profile.serial.ifEmpty { generateConsistentSerial(state.profile.fingerprint) }
        tryHookMethod("android.telephony.TelephonyManager", "getImei") { _, _, _ -> imei }
        tryHookMethod("android.telephony.TelephonyManager", "getDeviceId") { _, _, _ -> imei }
        tryHookMethod("android.telephony.TelephonyManager", "getImei", Int::class.javaPrimitiveType!!) { _, _, _ -> imei }
        tryHookMethod("android.telephony.TelephonyManager", "getDeviceId", Int::class.javaPrimitiveType!!) { _, _, _ -> imei }
        tryHookMethod("android.telephony.TelephonyManager", "getSubscriberId") { _, _, _ -> imsi }
        tryHookMethod("android.telephony.TelephonyManager", "getSimSerialNumber") { _, _, _ -> simSerial }
        tryHookMethod("android.telephony.TelephonyManager", "getLine1Number") { _, _, _ -> state.phoneNumber }
        tryHookMethod("android.net.wifi.WifiInfo", "getMacAddress") { _, _, _ -> macAddress }
        tryHookMethod("android.bluetooth.BluetoothAdapter", "getAddress") { _, _, _ -> macAddress }
        tryHookMethod("android.os.Build", "getSerial") { _, _, _ -> serial }
        Timber.tag(TAG).d("LSPlant method hooks installed for instance $instanceId")
    }

    private fun tryHookMethod(
        className: String, methodName: String, vararg paramTypes: Class<*>,
        afterCallback: (receiver: Any?, args: Array<Any?>, result: Any?) -> Any?
    ) {
        try {
            val clazz = Class.forName(className)
            val method = clazz.getMethod(methodName, *paramTypes)
            hookEngine.hookMethod(method, beforeCallback = null, afterCallback = afterCallback)
        } catch (_: NoSuchMethodException) { } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to hook $className.$methodName: ${e.message}")
        }
    }

    fun resetAllSpoofing() {
        if (!isGlobalSpoofApplied) { Timber.tag(TAG).d("No spoofing to reset"); return }
        Timber.tag(TAG).i("Resetting all identity spoofing...")
        val buildClass = Build::class.java
        for ((fieldName, value) in originalBuildValues) {
            try {
                val field = buildClass.getDeclaredField(fieldName)
                field.isAccessible = true; removeFinalModifier(field); field.set(null, value)
            } catch (e: Exception) { Timber.tag(TAG).w("Failed to restore Build.$fieldName: ${e.message}") }
        }
        val versionClass = Build.VERSION::class.java
        for ((fieldName, value) in originalVersionValues) {
            try {
                val field = versionClass.getDeclaredField(fieldName)
                field.isAccessible = true; removeFinalModifier(field); field.set(null, value)
            } catch (e: Exception) { Timber.tag(TAG).w("Failed to restore Build.VERSION.$fieldName: ${e.message}") }
        }
        if (originalSerial != null) setStaticStringField(Build::class.java, "SERIAL", originalSerial!!)
        hookEngine.unhookAll()
        timePrisonManager.removeAll()
        spoofedInstances.clear(); identityCache.clear(); isGlobalSpoofApplied = false
        Timber.tag(TAG).i("All identity spoofing reset to original values")
    }

    fun resetSpoofingForInstance(instanceId: String) {
        spoofedInstances.remove(instanceId)
        timePrisonManager.removeTimePrison(instanceId)
        Timber.tag(TAG).d("Spoofing state cleared for instance: $instanceId")
        if (spoofedInstances.isEmpty()) resetAllSpoofing()
    }

    fun getSpoofState(instanceId: String): SpoofState? = spoofedInstances[instanceId]
    fun getSpoofedImei(instanceId: String): String? = spoofedInstances[instanceId]?.imei
    fun getSpoofedAndroidId(instanceId: String): String? = spoofedInstances[instanceId]?.androidId
    fun getSpoofedMacAddress(instanceId: String): String? = spoofedInstances[instanceId]?.macAddress
    fun countSpoofedFields(): Int = spoofedInstances.values.sumOf { it.spoofedFields.size }
    fun isAnySpoofingActive(): Boolean = isGlobalSpoofApplied
    fun getTimePrisonManager(): TimePrisonManager = timePrisonManager
    fun clearCache() { identityCache.clear(); Timber.tag(TAG).d("Identity computation cache cleared") }

    private fun backupOriginalValues() {
        val buildClass = Build::class.java
        val buildBackup = mutableMapOf<String, Any?>()
        for (fieldName in BUILD_FIELDS) {
            try { val field = buildClass.getDeclaredField(fieldName); field.isAccessible = true; buildBackup[fieldName] = field.get(null) } catch (_: Exception) { }
        }
        originalBuildValues = buildBackup
        val versionClass = Build.VERSION::class.java
        val versionBackup = mutableMapOf<String, Any?>()
        for (fieldName in VERSION_FIELDS + VERSION_INT_FIELDS) {
            try { val field = versionClass.getDeclaredField(fieldName); field.isAccessible = true; versionBackup[fieldName] = field.get(null) } catch (_: Exception) { }
        }
        originalVersionValues = versionBackup
        try { val serialField = buildClass.getDeclaredField("SERIAL"); serialField.isAccessible = true; originalSerial = serialField.get(null) as? String } catch (_: Exception) { }
        Timber.tag(TAG).d("Original values backed up: ${buildBackup.size} Build + ${versionBackup.size} VERSION fields")
    }

    private fun setStaticStringField(clazz: Class<*>, fieldName: String, value: String): Boolean {
        return try {
            val field = clazz.getDeclaredField(fieldName); field.isAccessible = true; removeFinalModifier(field); field.set(null, value); true
        } catch (e: Exception) { Timber.tag(TAG).w("Failed to set $fieldName: ${e.message}"); false }
    }

    private fun setStaticIntField(clazz: Class<*>, fieldName: String, value: Int): Boolean {
        return try {
            val field = clazz.getDeclaredField(fieldName); field.isAccessible = true; removeFinalModifier(field); field.setInt(null, value); true
        } catch (e: Exception) { Timber.tag(TAG).w("Failed to set int $fieldName: ${e.message}"); false }
    }

    private fun sha256Hex(input: String): String {
        return identityCache.getOrPut(input) {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private fun luhnCheckDigit(number: String): Char {
        var sum = 0; var alternate = true
        for (i in number.length - 1 downTo 0) {
            var n = number[i] - '0'
            if (alternate) { n *= 2; if (n > 9) n -= 9 }
            sum += n; alternate = !alternate
        }
        return ((10 - (sum % 10)) % 10 + '0'.code).toChar()
    }

    private fun extractIncremental(buildId: String): String = buildId.replace(".", "").takeLast(8)

    private fun generateSecurityPatch(buildId: String): String {
        val regex = Regex("(\\d{6})")
        val match = regex.find(buildId)
        if (match != null) {
            val dateStr = match.value
            return "20${dateStr.take(2)}-${dateStr.substring(2, 4)}-${dateStr.substring(4, 6)}"
        }
        return "2025-03-01"
    }
}

data class SpoofState(
    val profile: DeviceProfile,
    val appliedAt: Long,
    val androidId: String = "",
    val imei: String = "",
    val imsi: String = "",
    val phoneNumber: String = "",
    val simSerial: String = "",
    val macAddress: String = "",
    val advertisingId: String = "",
    val spoofedFields: List<String> = emptyList()
)
