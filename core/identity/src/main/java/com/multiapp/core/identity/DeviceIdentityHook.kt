package com.multiapp.core.identity

import android.annotation.SuppressLint
import android.net.wifi.WifiInfo
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import com.multiapp.core.hook.HookEngine
import timber.log.Timber

/**
 * Device identity hook.
 *
 * Phase 4: Spoofs device-level identifiers so each cloned instance presents
 * a unique device fingerprint to the target app and remote servers.
 *
 * Hook points:
 * 1. TelephonyManager.getDeviceId() -> imei
 * 2. TelephonyManager.getImei() -> imei
 * 3. TelephonyManager.getSubscriberId() -> random IMSI
 * 4. Settings.Secure.getString(ANDROID_ID) -> androidId
 * 5. WifiInfo.getMacAddress() -> macAddress
 * 6. Build.getSerial() / Build.SERIAL -> serial
 */
class DeviceIdentityHook(private val hookEngine: HookEngine) : HookPoint {

    override fun apply(config: IdentityConfig, hookEngine: HookEngine) {
        Timber.d(
            "DeviceIdentityHook: apply called for instance=%s, imei=%s, androidId=%s",
            config.instanceId,
            config.imei,
            config.androidId
        )

        hookTelephonyGetDeviceId(config.imei)
        hookTelephonyGetImei(config.imei)
        hookTelephonyGetSubscriberId(config.instanceId)
        hookSettingsSecureAndroidId(config.androidId)
        hookWifiInfoMacAddress(config.macAddress)
        hookBuildGetSerial(config.serial)

        Timber.tag(TAG).i(
            "DeviceIdentityHook installed for instance=%s",
            config.instanceId
        )
    }

    companion object {
        private const val TAG = "DeviceIdentityHook"
    }

    private fun hookTelephonyGetDeviceId(imei: String) {
        try {
            val method = TelephonyManager::class.java.getDeclaredMethod("getDeviceId")
            hookEngine.hookMethod(
                method = method,
                afterCallback = { _, _, _ -> imei }
            )
            Timber.tag(TAG).d("Hooked TelephonyManager.getDeviceId()")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook TelephonyManager.getDeviceId()")
        }
    }

    private fun hookTelephonyGetImei(imei: String) {
        try {
            val method = TelephonyManager::class.java.getDeclaredMethod("getImei")
            hookEngine.hookMethod(
                method = method,
                afterCallback = { _, _, _ -> imei }
            )
            Timber.tag(TAG).d("Hooked TelephonyManager.getImei()")
        } catch (e: NoSuchMethodException) {
            Timber.tag(TAG).d("getImei() not available (pre-API 26), skipping")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook TelephonyManager.getImei()")
        }
    }

    private fun hookTelephonyGetSubscriberId(instanceId: String) {
        try {
            val method = TelephonyManager::class.java
                .getDeclaredMethod("getSubscriberId")
            val fakeImsi = generateFakeImsi(instanceId)
            hookEngine.hookMethod(
                method = method,
                afterCallback = { _, _, _ -> fakeImsi }
            )
            Timber.tag(TAG).d("Hooked TelephonyManager.getSubscriberId()")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook TelephonyManager.getSubscriberId()")
        }
    }

    @SuppressLint("HardwareIds")
    private fun hookSettingsSecureAndroidId(androidId: String) {
        try {
            val method = Settings.Secure::class.java.getDeclaredMethod(
                "getString",
                android.content.ContentResolver::class.java,
                String::class.java
            )
            hookEngine.hookMethod(
                method = method,
                afterCallback = { _, args, result ->
                    val key = args.getOrNull(1) as? String
                    if (key == Settings.Secure.ANDROID_ID) {
                        androidId
                    } else {
                        result
                    }
                }
            )
            Timber.tag(TAG).d("Hooked Settings.Secure.getString() for ANDROID_ID")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook Settings.Secure.getString()")
        }
    }

    private fun hookWifiInfoMacAddress(macAddress: String) {
        try {
            val method = WifiInfo::class.java.getDeclaredMethod("getMacAddress")
            hookEngine.hookMethod(
                method = method,
                afterCallback = { _, _, _ -> macAddress }
            )
            Timber.tag(TAG).d("Hooked WifiInfo.getMacAddress()")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook WifiInfo.getMacAddress()")
        }
    }

    private fun hookBuildGetSerial(serial: String) {
        try {
            hookEngine.hookStaticField(
                "android.os.Build",
                "SERIAL",
                serial
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "hookStaticField failed for Build.SERIAL")
        }

        try {
            hookEngine.hookStaticField(
                "android.os.Build",
                "UNKNOWN",
                serial
            )
        } catch (_: Exception) {
            // UNKNOWN may not exist on all API levels
        }

        try {
            val method = Build::class.java.getDeclaredMethod("getSerial")
            hookEngine.hookMethod(
                method = method,
                afterCallback = { _, _, _ -> serial }
            )
            Timber.tag(TAG).d("Hooked Build.getSerial()")
        } catch (e: NoSuchMethodException) {
            Timber.tag(TAG).d("Build.getSerial() not available (pre-API 26)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook Build.getSerial()")
        }
    }

    private fun generateFakeImsi(instanceId: String): String {
        val hash = instanceId.hashCode().toLong() and 0xFFFFFFFFL
        val sb = StringBuilder("460") // MCC for China
        sb.append(String.format("%012d", hash % 1_000_000_000_000L))
        return sb.toString().take(15)
    }
}
