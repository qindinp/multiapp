package com.multiapp.app.container

import android.content.Context
import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.model.virtual.ProxySlotContract
import java.io.File

/** Shared filesystem layout for v2 hosted container runtime state. */
object ContainerRuntimePaths {
    const val INSTANCES_DIR = "instances"
    const val INSTALLS_DIR = "installs"
    const val ARTIFACTS_DIR = "artifacts"
    const val INSTANCE_DATA_DIR = "instance_data"
    const val INSTANCE_LIB_DIR = "lib"
    const val HOSTED_LAUNCH_EVIDENCE_DIR = "hosted_launch_evidence"
    const val PROXY_ACTIVITY_SLOTS_FILE = ProxySlotContract.SLOT_ASSIGNMENT_FILE
    const val ENGINE_RUNTIME_SLOTS_FILE = "engine_runtime_slots.properties"

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

    fun proxyActivitySlotsFile(context: Context): File =
        proxyActivitySlotsFile(context.filesDir)

    fun proxyActivitySlotsFile(filesDir: File): File =
        File(filesDir, PROXY_ACTIVITY_SLOTS_FILE)

    fun engineRuntimeSlotsFile(context: Context): File =
        engineRuntimeSlotsFile(context.filesDir)

    fun engineRuntimeSlotsFile(filesDir: File): File =
        File(filesDir, ENGINE_RUNTIME_SLOTS_FILE)

    fun hostedLaunchEvidenceFile(context: Context, instanceId: String): File =
        hostedLaunchEvidenceFile(context.filesDir, instanceId)

    fun hostedLaunchEvidenceFile(filesDir: File, instanceId: String): File {
        val evidenceDir = hostedLaunchEvidenceDir(filesDir).canonicalFile
        val safeInstanceId = EvidenceSanitizer.safeEvidenceSegment(instanceId, "instanceId")
        val file = File(evidenceDir, "$safeInstanceId.properties").canonicalFile
        require(file.parentFile == evidenceDir) { "hosted launch evidence path escapes evidence dir" }
        return file
    }

    fun hostedRuntimeEvidenceFile(context: Context, instanceId: String, component: String): File =
        hostedRuntimeEvidenceFile(context.filesDir, instanceId, component)

    fun hostedRuntimeEvidenceFile(filesDir: File, instanceId: String, component: String): File {
        val evidenceDir = hostedLaunchEvidenceDir(filesDir).canonicalFile
        val safeInstanceId = EvidenceSanitizer.safeEvidenceSegment(instanceId, "instanceId")
        val safeComponent = EvidenceSanitizer.safeEvidenceSegment(component, "component")
        val file = File(evidenceDir, "$safeInstanceId.$safeComponent.properties").canonicalFile
        require(file.parentFile == evidenceDir) { "hosted runtime evidence path escapes evidence dir" }
        return file
    }

    fun nativeLibraryDirOrNull(dataRootPath: String?): String? {
        if (dataRootPath.isNullOrBlank()) return null
        return nativeLibraryDir(File(dataRootPath)).takeIf { it.isDirectory }?.absolutePath
    }

    private fun ensureDir(parent: File, child: String): File =
        File(parent, child).apply { mkdirs() }
}
