package com.multiapp.app.container

import android.content.Context
import java.io.File

/** Shared filesystem layout for v2 hosted container runtime state. */
object ContainerRuntimePaths {
    const val INSTANCES_DIR = "instances"
    const val INSTALLS_DIR = "installs"
    const val ARTIFACTS_DIR = "artifacts"
    const val INSTANCE_DATA_DIR = "instance_data"
    const val INSTANCE_LIB_DIR = "lib"
    const val HOSTED_LAUNCH_EVIDENCE_DIR = "hosted_launch_evidence"

    fun instanceStoreDir(context: Context): File = instanceStoreDir(context.filesDir)

    fun instanceStoreDir(filesDir: File): File = ensureDir(filesDir, INSTANCES_DIR)

    fun installStoreDir(context: Context): File = installStoreDir(context.filesDir)

    fun installStoreDir(filesDir: File): File = ensureDir(filesDir, INSTALLS_DIR)

    fun artifactDir(context: Context): File = artifactDir(context.filesDir)

    fun artifactDir(filesDir: File): File = ensureDir(filesDir, ARTIFACTS_DIR)

    fun instanceDataRootBase(context: Context): File = instanceDataRootBase(context.filesDir)

    fun instanceDataRootBase(filesDir: File): File = ensureDir(filesDir, INSTANCE_DATA_DIR)

    fun instanceDataRoot(context: Context, instanceId: String): File =
        instanceDataRoot(context.filesDir, instanceId)

    fun instanceDataRoot(filesDir: File, instanceId: String): File =
        ensureDir(instanceDataRootBase(filesDir), instanceId)

    fun nativeLibraryDir(dataRoot: File): File = File(dataRoot, INSTANCE_LIB_DIR)

    fun hostedLaunchEvidenceDir(context: Context): File =
        hostedLaunchEvidenceDir(context.filesDir)

    fun hostedLaunchEvidenceDir(filesDir: File): File =
        ensureDir(filesDir, HOSTED_LAUNCH_EVIDENCE_DIR)

    fun hostedLaunchEvidenceFile(context: Context, instanceId: String): File =
        File(hostedLaunchEvidenceDir(context), "$instanceId.properties")

    fun hostedLaunchEvidenceFile(filesDir: File, instanceId: String): File =
        File(hostedLaunchEvidenceDir(filesDir), "$instanceId.properties")

    fun hostedRuntimeEvidenceFile(context: Context, instanceId: String, component: String): File =
        hostedRuntimeEvidenceFile(context.filesDir, instanceId, component)

    fun hostedRuntimeEvidenceFile(filesDir: File, instanceId: String, component: String): File =
        File(hostedLaunchEvidenceDir(filesDir), "$instanceId.$component.properties")

    fun nativeLibraryDirOrNull(dataRootPath: String?): String? {
        if (dataRootPath.isNullOrBlank()) return null
        return nativeLibraryDir(File(dataRootPath)).takeIf { it.isDirectory }?.absolutePath
    }

    private fun ensureDir(parent: File, child: String): File =
        File(parent, child).apply { mkdirs() }
}
