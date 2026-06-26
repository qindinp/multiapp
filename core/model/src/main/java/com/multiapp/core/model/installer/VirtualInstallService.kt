package com.multiapp.core.model.installer

import com.multiapp.core.model.InstallArtifactManifest
import com.multiapp.core.model.VirtualPackageRecord

data class ImportResult(
    val packageRecord: VirtualPackageRecord,
    val manifest: InstallArtifactManifest,
    val recordPath: String
)

interface VirtualInstallService {
    suspend fun importFromInstalledPackage(packageName: String): Result<ImportResult>
    fun getInstallRecord(packageName: String): InstallRecord?
    fun listInstallRecords(): List<InstallRecord>
    fun deleteInstallRecord(packageName: String): Boolean
}
