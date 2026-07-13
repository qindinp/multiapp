package com.multiapp.core.model.instance

/**
 * Manages the lifecycle of virtual app instances.
 *
 * Responsibilities:
 * - Create and delete instances with data root isolation.
 * - Query instances by ID or origin package name.
 * - Track launch state (count, last launch time).
 *
 * Implementations must be safe for single-writer / multi-reader use.
 * The primary process writes; all processes may read.
 */
interface InstanceManager {

    data class CreationRequest(
        val originPackageName: String,
        val displayName: String,
        val compatibilityMode: CompatibilityMode = CompatibilityMode.DEFAULT,
        val creationRequestId: String? = null,
        val creationRequestFingerprint: String? = null
    ) {
        init {
            require(originPackageName.isNotBlank()) { "originPackageName must not be blank" }
            require(displayName.isNotBlank()) { "displayName must not be blank" }
            require(creationRequestId == null || creationRequestId.isNotBlank()) {
                "creationRequestId must not be blank"
            }
            require(creationRequestFingerprint == null || creationRequestId != null) {
                "creationRequestFingerprint requires creationRequestId"
            }
            require(
                creationRequestFingerprint == null ||
                    creationRequestFingerprint.length == SHA_256_HEX_LENGTH &&
                    creationRequestFingerprint.all { it in '0'..'9' || it in 'a'..'f' }
            ) {
                "creationRequestFingerprint must be a lowercase SHA-256 digest"
            }
        }

        private companion object {
            const val SHA_256_HEX_LENGTH = 64
        }
    }

    /**
     * Create a new virtual instance for the given origin app.
     *
     * @param originPackageName Original app package name.
     * @param displayName       User-visible display name.
     * @param compatibilityMode Runtime compatibility mode.
     * @return Success with the created record, or failure.
     */
    fun createInstance(
        originPackageName: String,
        displayName: String,
        compatibilityMode: CompatibilityMode = CompatibilityMode.DEFAULT
    ): Result<VirtualInstanceRecord>

    fun createInstance(request: CreationRequest): Result<VirtualInstanceRecord> =
        createInstance(
            originPackageName = request.originPackageName,
            displayName = request.displayName,
            compatibilityMode = request.compatibilityMode
        )

    /**
     * Get an instance by its unique ID.
     *
     * @return The record if found, null otherwise.
     */
    fun getInstance(instanceId: String): VirtualInstanceRecord?

    /**
     * Get all instances cloned from the given origin package.
     */
    fun getInstanceByOrigin(originPackageName: String): List<VirtualInstanceRecord>

    /**
     * List all known instances.
     */
    fun listInstances(): List<VirtualInstanceRecord>

    fun getInstanceByCreationRequestId(creationRequestId: String): VirtualInstanceRecord? =
        listInstances().firstOrNull { it.creationRequestId == creationRequestId }

    /**
     * Delete an instance and clean up its data root.
     *
     * @return true if the instance existed and was deleted.
     */
    fun deleteInstance(instanceId: String): Boolean

    /**
     * Record a launch for the given instance.
     *
     * Increments [VirtualInstanceRecord.launchCount] and updates
     * [VirtualInstanceRecord.lastLaunchAtMs].
     *
     * @return The updated record, or null if the instance was not found.
     */
    fun updateLaunchState(instanceId: String): VirtualInstanceRecord?

    /**
     * Get the data root for an instance.
     *
     * @return The data root if the instance exists, null otherwise.
     */
    fun getDataRoot(instanceId: String): InstanceDataRoot?
}
