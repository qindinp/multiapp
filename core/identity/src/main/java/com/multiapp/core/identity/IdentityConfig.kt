package com.multiapp.core.identity

/**
 * Identity configuration for a cloned app instance.
 * Contains all spoofed device and package identifiers.
 */
data class IdentityConfig(
    val instanceId: String,
    val stubPackageName: String,
    val originalPackageName: String,
    val authorityMap: Map<String, String>,
    val imei: String,
    val androidId: String,
    val macAddress: String,
    val serial: String,
    val buildModel: String,
    val buildManufacturer: String,
    val buildFingerprint: String,
    val buildBrand: String,
    val buildDevice: String,
    val buildProduct: String,
    val versionRelease: String,
    val sdkInt: Int
)
