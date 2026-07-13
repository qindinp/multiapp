package com.multiapp.core.model.instance

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Persistence-oriented record for a virtual app instance.
 *
 * This is the management/persistence layer model. It tracks schema versioning,
 * launch statistics, and lifecycle state. The runtime-layer model
 * (VirtualInstanceRecord in the parent package) handles process slots and
 * stub mapping.
 *
 * @property schemaVersion     Schema version for forward-compatible migration.
 * @property instanceId        Globally unique instance identifier (UUID).
 * @property originPackageName Original app package name, e.g. "com.whatsapp".
 * @property virtualPackageName Synthetic package name for this instance,
 *                              e.g. "com.multiapp.instance.a1b2c3".
 * @property displayName       User-visible display name.
 * @property iconPolicy        Icon resolution strategy.
 * @property dataRoot          Absolute path to the instance data directory.
 * @property compatibilityMode Runtime compatibility mode.
 * @property protectedBaselinePolicy Baseline protection policy identifier.
 * @property createdAtMs       Creation timestamp (epoch millis).
 * @property updatedAtMs       Last update timestamp (epoch millis).
 * @property lastLaunchAtMs    Last launch timestamp (epoch millis), null if never launched.
 * @property launchCount       Cumulative launch counter.
 * @property state             Current lifecycle state.
 */
data class VirtualInstanceRecord(
    val schemaVersion: Int = 2,
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val displayName: String,
    val iconPolicy: IconPolicy = IconPolicy.DEFAULT,
    val dataRoot: String,
    val compatibilityMode: CompatibilityMode,
    val protectedBaselinePolicy: String = "strict",
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastLaunchAtMs: Long? = null,
    val launchCount: Int = 0,
    val state: InstanceState = InstanceState.READY,
    val creationRequestId: String? = null,
    val creationRequestFingerprint: String? = null
)

enum class IconPolicy {
    DEFAULT,
    CUSTOM
}

enum class InstanceState {
    CREATING,
    READY,
    RUNNING,
    STOPPED,
    ERROR
}

enum class CompatibilityMode {
    STANDARD,
    LEGACY;

    companion object {
        val DEFAULT: CompatibilityMode = STANDARD
    }
}
