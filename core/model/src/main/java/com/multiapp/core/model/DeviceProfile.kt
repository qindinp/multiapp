package com.multiapp.core.model

/**
 * Device identity profile for spoofing.
 * When assigned to a VirtualApp, that app sees these values
 * instead of the real device's Build.* fields.
 */
data class DeviceProfile(
    val id: String = "default",
    val name: String = "Default Device",
    val brand: String = "google",
    val manufacturer: String = "Google",
    val model: String = "Pixel 9",
    val device: String = "caiman",
    val product: String = "caiman",
    val board: String = "caiman",
    val hardware: String = "caiman",
    val fingerprint: String = "google/caiman/caiman:16/BP1A.250305.019/12345678:user/release-keys",
    val androidVersion: String = "16",
    val sdkInt: Int = 35,
    val buildId: String = "BP1A.250305.019",
    val serial: String = "MULTIAPP0001",
    val imei: String = "",
    val macAddress: String = "02:00:00:00:00:00",
    val screenWidthDp: Int = 412,
    val screenHeightDp: Int = 924,
    val densityDpi: Int = 420,
    val locale: String = "en_US",
    val timezone: String = "UTC",
    val carrierName: String = "",
    val mcc: String = "",
    val mnc: String = ""
)

/**
 * Network access policy for virtual apps.
 */
enum class NetworkPolicy {
    FULL_ACCESS,
    WIFI_ONLY,
    VPN_ONLY,
    OFFLINE,
    CUSTOM_DNS
}

/**
 * Virtual engine lifecycle status.
 */
enum class EngineStatus {
    NOT_INITIALIZED,
    INITIALIZING,
    READY,
    ERROR
}

/**
 * Result wrapper for ALL virtual engine operations.
 * Use this instead of throwing exceptions.
 */
sealed class VmResult<out T> {
    data class Success<T>(val data: T) : VmResult<T>()
    data class Error(val message: String, val exception: Throwable? = null) : VmResult<Nothing>()
    data object Loading : VmResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
    val isLoading: Boolean get() = this is Loading

    fun getOrNull(): T? = when (this) {
        is Success -> data
        else -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw exception ?: RuntimeException(message)
        is Loading -> throw IllegalStateException("Result is still loading")
    }

    inline fun <R> map(transform: (T) -> R): VmResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
        is Loading -> this
    }

    inline fun onSuccess(action: (T) -> Unit): VmResult<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (String, Throwable?) -> Unit): VmResult<T> {
        if (this is Error) action(message, exception)
        return this
    }
}
