package com.multiapp.core.loader

import android.content.Context
import android.content.Intent
import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualIntentSnapshot

data class ProxyActivityLaunchSpec(
    val hostPackageName: String,
    val proxyActivityClassName: String,
    val token: String,
    val instanceId: String,
    val originPackageName: String,
    val guestActivityClassName: String,
    val launchMode: String?
)

class VirtualActivityManager(
    private val context: Context,
    private val proxyActivityRegistry: ProxyActivityRegistry,
    private val hostPackageName: String = context.packageName,
    private val activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager.global
) {
    init {
        proxyActivityRegistry.registerExisting(activityRecordManager.list())
    }

    companion object {
        const val EXTRA_VIRTUAL_ACTIVITY_TOKEN = "multiapp.virtualActivityToken"
        const val EXTRA_INSTANCE_ID = "multiapp.instanceId"
        const val EXTRA_ORIGIN_PACKAGE_NAME = "multiapp.originPackageName"
        const val EXTRA_GUEST_ACTIVITY_CLASS_NAME = "multiapp.guestActivityClassName"
        const val EXTRA_ORIGINAL_GUEST_INTENT = "multiapp.originalGuestIntent"
        const val EXTRA_HOST_PACKAGE_NAME = "multiapp.hostPackageName"
    }

    fun launchGuestLauncher(
        instanceId: String,
        originPackageName: String,
        guestActivityClassName: String,
        launchMode: String? = null
    ): Result<VirtualActivityRecord> {
        return runCatching {
            val launcherIntent = Intent()
            val record = allocateGuestActivity(
                VirtualActivityLaunchRequest(
                    instanceId = instanceId,
                    originPackageName = originPackageName,
                    guestActivityClassName = guestActivityClassName,
                    sourceIntent = launcherIntent,
                    reason = "launcher",
                    launchMode = launchMode
                )
            )
            context.startActivity(createProxyIntent(record, sourceIntent = launcherIntent, forceNewTask = true))
            record
        }
    }

    fun launchGuestActivity(request: VirtualActivityLaunchRequest): Result<VirtualActivityRecord> {
        return runCatching {
            val record = allocateGuestActivity(request)
            context.startActivity(createProxyIntent(record, sourceIntent = request.sourceIntent))
            record
        }
    }

    fun allocateGuestActivity(request: VirtualActivityLaunchRequest): VirtualActivityRecord {
        val record = proxyActivityRegistry.allocate(
            instanceId = request.instanceId,
            originPackageName = request.originPackageName,
            guestActivityClassName = request.guestActivityClassName,
            launchMode = request.launchMode
        )
        return activityRecordManager.registerLaunch(
            record,
            request.sourceIntent.safeFlags(),
            request.sourceIntent.toVirtualIntentSnapshot()
        ).activity
    }

    fun createProxyIntent(
        record: VirtualActivityRecord,
        sourceIntent: Intent? = null,
        forceNewTask: Boolean = false
    ): Intent {
        val spec = createProxyLaunchSpec(record)
        return Intent().apply {
            setClassName(spec.hostPackageName, spec.proxyActivityClassName)
            putExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN, spec.token)
            putExtra(EXTRA_INSTANCE_ID, spec.instanceId)
            putExtra(EXTRA_ORIGIN_PACKAGE_NAME, spec.originPackageName)
            putExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME, spec.guestActivityClassName)
            putExtra(EXTRA_HOST_PACKAGE_NAME, spec.hostPackageName)
            if (!spec.launchMode.isNullOrBlank()) {
                putExtra("multiapp.guestActivityLaunchMode", spec.launchMode)
            }
            if (sourceIntent != null) {
                putExtra(EXTRA_ORIGINAL_GUEST_INTENT, Intent(sourceIntent))
            }
            if (forceNewTask || sourceIntent?.safeFlags()?.hasFlag(Intent.FLAG_ACTIVITY_NEW_TASK) == true) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun createProxyLaunchSpec(record: VirtualActivityRecord): ProxyActivityLaunchSpec {
        return ProxyActivityLaunchSpec(
            hostPackageName = hostPackageName,
            proxyActivityClassName = record.proxyActivityClassName,
            token = record.token,
            instanceId = record.instanceId,
            originPackageName = record.originPackageName,
            guestActivityClassName = record.guestActivityClassName,
            launchMode = record.launchMode
        )
    }

    private fun Intent.toVirtualIntentSnapshot(): VirtualIntentSnapshot {
        val sourceExtras = runCatching { extras }.getOrNull()
        val extrasSnapshot = sourceExtras
            ?.keySet()
            ?.associateWith { "<present>" }
            .orEmpty()
        return VirtualIntentSnapshot(
            flags = safeFlags(),
            action = runCatching { action }.getOrNull(),
            dataUri = runCatching { dataString?.redactUriForEvidence() }.getOrNull(),
            categories = runCatching { categories.orEmpty().toSet() }.getOrDefault(emptySet()),
            extras = extrasSnapshot
        )
    }

    private fun Intent.safeFlags(): Int = runCatching { flags }.getOrDefault(0)

    private fun Int.hasFlag(flag: Int): Boolean = this and flag != 0

    private fun String.redactUriForEvidence(): String = EvidenceSanitizer.redactUriForEvidence(this)
}
