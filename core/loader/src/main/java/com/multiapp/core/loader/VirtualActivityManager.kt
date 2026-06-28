package com.multiapp.core.loader

import android.content.Context
import android.content.Intent
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.VirtualActivityRecord

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
        guestActivityClassName: String
    ): Result<VirtualActivityRecord> {
        return runCatching {
            val record = allocateGuestActivity(
                VirtualActivityLaunchRequest(
                    instanceId = instanceId,
                    originPackageName = originPackageName,
                    guestActivityClassName = guestActivityClassName,
                    sourceIntent = Intent(),
                    reason = "launcher"
                )
            )
            context.startActivity(createProxyIntent(record, sourceIntent = Intent()))
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
        return activityRecordManager.registerLaunch(record, request.sourceIntent.flags).activity
    }

    fun createProxyIntent(record: VirtualActivityRecord, sourceIntent: Intent? = null): Intent {
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
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
}
