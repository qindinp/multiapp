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
