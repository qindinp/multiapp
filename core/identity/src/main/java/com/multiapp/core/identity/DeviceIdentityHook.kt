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
class DeviceIdentityHook : HookPoint {

    override fun apply(config: IdentityConfig) {
        Timber.d(
            "DeviceIdentityHook: apply called for instance=%s, imei=%s, androidId=%s",
            config.instanceId,
            config.imei,
            config.androidId
        )
        applyInternal(config)
    }

    companion object {

        private const val TAG = "DeviceIdentityHook"

        fun apply(config: IdentityConfig) {
            Timber.d(
                "DeviceIdentityHook: companion apply called for instance=%s",
                config.instanceId
            )
            applyInternal(config)
        }

        private fun applyInternal(config: IdentityConfig) {
            val hookEngine = HookEngine.getInstance()

            hookTelephonyGetDeviceId(hookEngine, config.imei)
            hookTelephonyGetImei(hookEngine, config.imei)
            hookTelephonyGetSubscriberId(hookEngine, config.instanceId)
            hookSettingsSecureAndroidId(hookEngine, config.androidId)
            hookWifiInfoMacAddress(hookEngine, config.macAddress)
            hookBuildGetSerial(hookEngine, config.serial)

            Timber.tag(TAG).i(
                "DeviceIdentityHook installed for instance=%s",
                config.instanceId
            )
        }

        /**
         * Hook TelephonyManager.getDeviceId() to return the spoofed IMEI.
         */
        private fun hookTelephonyGetDeviceId(hookEngine: HookEngine, imei: String) {
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

        /**
         * Hook TelephonyManager.getImei() to return the spoofed IMEI.
         * getImei() was added in API 26.
         */
        private fun hookTelephonyGetImei(hookEngine: HookEngine, imei: String) {
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

        /**
         * Hook TelephonyManager.getSubscriberId() to return a random IMSI.
         * The IMSI is derived from the instance ID for consistency.
         */
        private fun hookTelephonyGetSubscriberId(
            hookEngine: HookEngine,
            instanceId: String
        ) {
            try {
                val method = TelephonyManager::class.java
                    .getDeclaredMethod("getSubscriberId")
                // Generate a consistent fake IMSI from the instance ID
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

        /**
         * Hook Settings.Secure.getString() to return the spoofed Android ID
         * when the caller requests ANDROID_ID.
         */
        @SuppressLint("HardwareIds")
        private fun hookSettingsSecureAndroidId(
            hookEngine: HookEngine,
            androidId: String
        ) {
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

        /**
         * Hook WifiInfo.getMacAddress() to return the spoofed MAC address.
         */
        private fun hookWifiInfoMacAddress(
            hookEngine: HookEngine,
            macAddress: String
        ) {
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

        /**
         * Hook Build.getSerial() and rewrite Build.SERIAL / Build.getSerial()
         * to return the spoofed serial number.
         */
        private fun hookBuildGetSerial(hookEngine: HookEngine, serial: String) {
            // Rewrite Build.SERIAL static field
            hookEngine.hookStaticField(
                "android.os.Build",
                "SERIAL",
                serial
            )

            // Also set Build.UNKNOWN if it exists and matches
            try {
                hookEngine.hookStaticField(
                    "android.os.Build",
                    "UNKNOWN",
                    serial
                )
            } catch (_: Exception) {
                // UNKNOWN may not exist on all API levels
            }

            // Hook Build.getSerial() method (API 26+)
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

        /**
         * Generate a consistent fake IMSI (15 digits) from the instance ID.
         * Uses the instance ID hash to ensure the same instance always
         * gets the same IMSI.
         */
        private fun generateFakeImsi(instanceId: String): String {
            val hash = instanceId.hashCode().toLong() and 0xFFFFFFFFL
            val sb = StringBuilder("460") // MCC for China
            sb.append(String.format("%012d", hash % 1_000_000_000_000L))
            return sb.toString().take(15)
        }
    }
}
