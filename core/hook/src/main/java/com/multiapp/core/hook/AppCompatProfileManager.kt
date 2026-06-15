package com.multiapp.core.hook

import android.util.Log

class AppCompatProfileManager {

    companion object {
        private const val TAG = "AppCompatProfileManager"

        @Volatile
        private var instance: AppCompatProfileManager? = null

        fun getInstance(): AppCompatProfileManager {
            return instance ?: synchronized(this) {
                instance ?: AppCompatProfileManager().also { instance = it }
            }
        }
    }

    private val profiles = mutableMapOf<String, AppCompatProfile>()

    init {
        register(QqReaderCompatProfile())
    }

    fun register(profile: AppCompatProfile) {
        profiles[profile.packageName] = profile
        Log.d(TAG, "Registered profile for ${profile.packageName} (packer=${profile.knownPacker})")
    }

    fun lookup(packageName: String): AppCompatProfile? {
        return profiles[packageName]
    }

    fun getOrGeneric(packageName: String): AppCompatProfile {
        return profiles[packageName] ?: GenericPackerProfile(packageName)
    }

    fun getAllRegistered(): List<AppCompatProfile> = profiles.values.toList()
}
