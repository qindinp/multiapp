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
    ): Result<VirtualInstanceRecord> {
        val baseDirRef = arrayOfNulls<File>(1)
        return runCatching {
            // Validate that InstallRecord exists if store is provided.
            // This prevents creating instances that HostedRuntimeBootstrap cannot start.
            if (installRecordStore != null) {
                val installRecord = installRecordStore.load(originPackageName)
                require(installRecord != null) {
                    "InstallRecord not found for package: $originPackageName. " +
                        "Call VirtualInstallService.importFromMetadata() before creating an instance."
                }
            }

            val instanceId = UUID.randomUUID().toString()
            val shortId = instanceId.replace("-", "").take(12)
            val virtualPackageName = "com.multiapp.instance.$shortId"

            val baseDir = File(dataRootBase, instanceId)
            baseDirRef[0] = baseDir
            val dataRoot = InstanceDataRoot.fromBaseDir(instanceId, baseDir)

            // Create directory structure
            baseDir.mkdirs()
            dataRoot.dataDir.mkdirs()
            dataRoot.cacheDir.mkdirs()
            dataRoot.filesDir.mkdirs()
            dataRoot.sharedPrefsDir.mkdirs()
            dataRoot.databaseDir.mkdirs()
            dataRoot.externalFilesDir?.mkdirs()

            val now = clock()

            val record = VirtualInstanceRecord(
                instanceId = instanceId,
                originPackageName = originPackageName,
                virtualPackageName = virtualPackageName,
                displayName = displayName,
                dataRoot = baseDir.absolutePath,
                compatibilityMode = compatibilityMode,
                createdAtMs = now,
                updatedAtMs = now,
                state = InstanceState.READY
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

        // Clean up data root directory
        val baseDir = File(record.dataRoot)
        if (baseDir.exists()) {
            val deleted = runCatching { dataRootDeleter(baseDir) }.getOrDefault(false)
            if (!deleted || baseDir.exists()) return false
        }

        return store.delete(instanceId)
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
