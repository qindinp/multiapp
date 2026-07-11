package com.multiapp.core.loader

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import com.multiapp.core.model.virtual.VirtualContextConfig

internal object HostedActivityIdentity {

    fun applicationInfoForRuntime(
        config: VirtualContextConfig,
        source: ApplicationInfo
    ): ApplicationInfo = ApplicationInfo(source).apply {
        packageName = config.originPackageName
        className = source.className
        name = source.name
        sourceDir = config.sourceDir
        publicSourceDir = config.publicSourceDir
        if (config.splitSourceDirs.isNotEmpty()) {
            splitSourceDirs = config.splitSourceDirs.toTypedArray()
        }
        val publicDirs = config.splitPublicSourceDirs.ifEmpty { config.splitSourceDirs }
        if (publicDirs.isNotEmpty()) {
            splitPublicSourceDirs = publicDirs.toTypedArray()
        }
        if (config.splitNames.isNotEmpty()) {
            splitNames = config.splitNames.toTypedArray()
        }
        dataDir = config.dataDir
        ApplicationInfoNativePathCompat.applyTo(this, config.dataDir, config.nativeLibraryDir)
        writeStringField(this, "credentialProtectedDataDir", config.dataDir)
        writeStringField(this, "deviceProtectedDataDir", config.dataDir)
        processName = runtimeProcessName(config)
        taskAffinity = runtimeTaskAffinity(config)
        theme = config.packageSnapshot?.themeId?.takeIf { it != 0 } ?: source.theme
        nonLocalizedLabel = config.applicationLabel ?: source.nonLocalizedLabel ?: config.originPackageName
        enabled = true
    }

    fun activityInfoForRecord(
        config: VirtualContextConfig,
        guestActivityClassName: String,
        applicationInfo: ApplicationInfo
    ): ActivityInfo {
        val snapshot = config.packageSnapshot
        val componentInfo = snapshot?.activities?.firstOrNull {
            it.name == guestActivityClassName || it.targetActivityName == guestActivityClassName
        }
        if (snapshot != null && componentInfo != null) {
            return ActivityInfo(VirtualPackageInfoFactory.activityInfo(snapshot, componentInfo)).apply {
                packageName = config.originPackageName
                name = guestActivityClassName
                theme = componentInfo.themeId.takeIf { it != 0 } ?: snapshot.themeId
                this.applicationInfo = applicationInfo
                processName = processName ?: applicationInfo.processName
                taskAffinity = taskAffinity ?: applicationInfo.taskAffinity
            }
        }
        return ActivityInfo().apply {
            packageName = config.originPackageName
            name = guestActivityClassName
            this.applicationInfo = applicationInfo
            enabled = true
            exported = false
            theme = applicationInfo.theme
            processName = applicationInfo.processName
            taskAffinity = applicationInfo.taskAffinity
        }
    }

    private fun runtimeProcessName(config: VirtualContextConfig): String =
        config.packageSnapshot?.processName ?: config.originPackageName

    private fun runtimeTaskAffinity(config: VirtualContextConfig): String =
        config.packageSnapshot?.taskAffinity ?: config.originPackageName

    private fun writeStringField(target: Any, fieldName: String, value: String) {
        runCatching {
            target.javaClass.getField(fieldName).set(target, value)
        }
    }
}
