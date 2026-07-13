package com.multiapp.core.model.instance

import com.multiapp.core.model.installer.InstallRecordStore
import java.io.File
import java.util.UUID

/**
 * Default implementation of [InstanceManager].
 *
 * Delegates persistence to [InstanceRecordStore] and manages data root
 * directories under [dataRootBase].
 *
 * Thread safety: single-writer (primary process), multi-reader (all processes).
 *
 * @param store             Record persistence backend.
 * @param dataRootBase      Base directory for instance data roots.
 * @param installRecordStore Optional install record store for validation.
 *                           When provided, [createInstance] verifies that an InstallRecord
 *                           exists for the origin package before creating the instance.
 *                           This prevents creating instances that cannot be bootstrapped.
 * @param clock             Clock supplier for timestamps (default: system clock).
 */
class DefaultInstanceManager(
    private val store: InstanceRecordStore,
    private val dataRootBase: File,
    private val installRecordStore: InstallRecordStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dataRootDeleter: (File) -> Boolean = { directory -> directory.deleteRecursively() }
) : InstanceManager {

    override fun createInstance(
        originPackageName: String,
        displayName: String,
        compatibilityMode: CompatibilityMode
    ): Result<VirtualInstanceRecord> = createInstance(
        InstanceManager.CreationRequest(
            originPackageName = originPackageName,
            displayName = displayName,
            compatibilityMode = compatibilityMode
        )
    )

    @Synchronized
    override fun createInstance(request: InstanceManager.CreationRequest): Result<VirtualInstanceRecord> {
        request.creationRequestId?.let { creationRequestId ->
            store.listAll().firstOrNull { it.creationRequestId == creationRequestId }?.let { existing ->
                return if (
                    existing.originPackageName == request.originPackageName &&
                    existing.displayName == request.displayName &&
                    existing.compatibilityMode == request.compatibilityMode &&
                    existing.creationRequestFingerprint == request.creationRequestFingerprint
                ) {
                    Result.success(existing)
                } else {
                    Result.failure(
                        IllegalStateException("creationRequestId already belongs to a different instance request")
                    )
                }
            }
        }
        val baseDirRef = arrayOfNulls<File>(1)
        return runCatching {
            // Validate that InstallRecord exists if store is provided.
            // This prevents creating instances that HostedRuntimeBootstrap cannot start.
            if (installRecordStore != null) {
                val installRecord = installRecordStore.load(request.originPackageName)
                require(installRecord != null) {
                    "InstallRecord not found for package: ${request.originPackageName}. " +
                        "Call VirtualInstallService.importFromMetadata() before creating an instance."
                }
            }

            val instanceId = UUID.randomUUID().toString()
            val shortId = instanceId.replace("-", "").take(12)
            val virtualPackageName = "com.multiapp.instance.$shortId"

            val baseDir = File(dataRootBase, instanceId)
            baseDirRef[0] = baseDir
            val dataRoot = InstanceDataRoot.fromBaseDir(instanceId, baseDir)

            // A persisted instance must never point at a partially-created data root.
            listOfNotNull(
                baseDir,
                dataRoot.dataDir,
                dataRoot.cacheDir,
                dataRoot.filesDir,
                dataRoot.sharedPrefsDir,
                dataRoot.databaseDir,
                dataRoot.externalFilesDir
            ).forEach { directory ->
                check(directory.isDirectory || directory.mkdirs()) {
                    "Failed to create instance data directory: ${directory.absolutePath}"
                }
            }

            val now = clock()

            val record = VirtualInstanceRecord(
                instanceId = instanceId,
                originPackageName = request.originPackageName,
                virtualPackageName = virtualPackageName,
                displayName = request.displayName,
                dataRoot = baseDir.absolutePath,
                compatibilityMode = request.compatibilityMode,
                createdAtMs = now,
                updatedAtMs = now,
                state = InstanceState.READY,
                creationRequestId = request.creationRequestId,
                creationRequestFingerprint = request.creationRequestFingerprint
            )

            store.save(record).getOrThrow()
            record
        }.onFailure {
            baseDirRef[0]?.takeIf { it.exists() }?.deleteRecursively()
        }
    }

    override fun getInstance(instanceId: String): VirtualInstanceRecord? {
        return store.load(instanceId)
    }

    override fun getInstanceByOrigin(originPackageName: String): List<VirtualInstanceRecord> {
        return store.loadByOrigin(originPackageName)
    }

    override fun listInstances(): List<VirtualInstanceRecord> {
        return store.listAll()
    }

    override fun deleteInstance(instanceId: String): Boolean {
        val record = store.load(instanceId) ?: return false
        val rootBase = runCatching { dataRootBase.canonicalFile }.getOrNull() ?: return false
        val expectedDataRoot = runCatching { File(rootBase, instanceId).canonicalFile }.getOrNull() ?: return false
        val recordedDataRoot = runCatching { File(record.dataRoot).canonicalFile }.getOrNull() ?: return false
        if (recordedDataRoot != expectedDataRoot || recordedDataRoot.parentFile != rootBase) return false
        if (!recordedDataRoot.exists()) return store.delete(instanceId)

        val tombstone = File(rootBase, ".$instanceId.delete-${UUID.randomUUID()}")
        if (!recordedDataRoot.renameTo(tombstone)) return false
        if (!store.delete(instanceId)) {
            tombstone.renameTo(recordedDataRoot)
            return false
        }

        val deleted = runCatching { dataRootDeleter(tombstone) }.getOrDefault(false)
        if (deleted && !tombstone.exists()) return true

        if (!recordedDataRoot.exists()) tombstone.renameTo(recordedDataRoot)
        store.save(record)
        return false
    }

    override fun updateLaunchState(instanceId: String): VirtualInstanceRecord? {
        val record = store.load(instanceId) ?: return null
        val now = clock()

        val updated = record.copy(
            launchCount = record.launchCount + 1,
            lastLaunchAtMs = now,
            updatedAtMs = now
        )

        store.save(updated).getOrThrow()
        return updated
    }

    override fun getDataRoot(instanceId: String): InstanceDataRoot? {
        val record = store.load(instanceId) ?: return null
        val baseDir = File(record.dataRoot)
        return InstanceDataRoot.fromBaseDir(instanceId, baseDir)
    }
}
