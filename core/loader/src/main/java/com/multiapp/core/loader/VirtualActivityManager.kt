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
    val launchMode: String?,
    val taskAffinity: String?,
    val engineLaunchIdentity: VirtualActivityLaunchIdentity? = null
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
        const val EXTRA_GUEST_ACTIVITY_LAUNCH_MODE = "multiapp.guestActivityLaunchMode"
        const val EXTRA_GUEST_TASK_AFFINITY = "multiapp.guestTaskAffinity"
        const val EXTRA_RESULT_TO_TOKEN = "multiapp.resultToToken"
        const val EXTRA_RESULT_REQUEST_CODE = "multiapp.resultRequestCode"
        const val EXTRA_ENGINE_RUNTIME_EPOCH = "multiapp.engine.runtimeEpoch"
        const val EXTRA_ENGINE_SESSION_ID = "multiapp.engine.sessionId"
        const val EXTRA_ENGINE_PROCESS_SLOT = "multiapp.engine.processSlot"
        const val EXTRA_ENGINE_PROXY_ACTIVITY_CLASS_NAME = "multiapp.engine.proxyActivityClassName"
        const val EXTRA_ENGINE_LAUNCH_CAPABILITY = "multiapp.engine.launchCapability"
    }

    fun launchGuestLauncher(
        instanceId: String,
        originPackageName: String,
        guestActivityClassName: String,
        launchMode: String? = null,
        taskAffinity: String? = null,
        engineLaunchIdentity: VirtualActivityLaunchIdentity? = null
    ): Result<VirtualActivityRecord> {
        return runCatching {
            val launcherIntent = Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolvedTaskAffinity = taskAffinity ?: rootTaskAffinity(originPackageName, instanceId)
            val record = allocateGuestActivity(
                VirtualActivityLaunchRequest(
                    instanceId = instanceId,
                    originPackageName = originPackageName,
                    guestActivityClassName = guestActivityClassName,
                    sourceIntent = launcherIntent,
                    reason = "launcher",
                    launchMode = launchMode,
                    taskAffinity = resolvedTaskAffinity
                )
            )
            val proxyIntent = createProxyIntent(
                record,
                sourceIntent = launcherIntent,
                forceNewTask = true,
                engineLaunchIdentity = engineLaunchIdentity
            ).putExtra(EXTRA_ORIGINAL_GUEST_INTENT, Intent(launcherIntent))
            context.startActivity(proxyIntent)
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
        val taskAffinity = request.taskAffinity ?: rootTaskAffinity(
            originPackageName = request.originPackageName,
            instanceId = request.instanceId
        )
        val record = proxyActivityRegistry.allocate(
            instanceId = request.instanceId,
            originPackageName = request.originPackageName,
            guestActivityClassName = request.guestActivityClassName,
            launchMode = request.launchMode,
            taskKey = taskAffinity,
            taskAffinity = taskAffinity
        )
        return activityRecordManager.registerLaunch(
            record.copy(
                resultToToken = request.resultToToken,
                resultRequestCode = request.resultRequestCode
            ),
            request.sourceIntent.safeFlags(),
            request.sourceIntent.toVirtualIntentSnapshot()
        ).activity
    }

    fun createProxyIntent(
        record: VirtualActivityRecord,
        sourceIntent: Intent? = null,
        forceNewTask: Boolean = false,
        engineLaunchIdentity: VirtualActivityLaunchIdentity? = null
    ): Intent {
        val spec = createProxyLaunchSpec(record, engineLaunchIdentity)
        return Intent().apply {
            setClassName(spec.hostPackageName, spec.proxyActivityClassName)
            putExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN, spec.token)
            putExtra(EXTRA_INSTANCE_ID, spec.instanceId)
            putExtra(EXTRA_ORIGIN_PACKAGE_NAME, spec.originPackageName)
            putExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME, spec.guestActivityClassName)
            putExtra(EXTRA_HOST_PACKAGE_NAME, spec.hostPackageName)
            if (!spec.launchMode.isNullOrBlank()) {
                putExtra(EXTRA_GUEST_ACTIVITY_LAUNCH_MODE, spec.launchMode)
            }
            if (!spec.taskAffinity.isNullOrBlank()) {
                putExtra(EXTRA_GUEST_TASK_AFFINITY, spec.taskAffinity)
            }
            spec.engineLaunchIdentity?.let { identity ->
                require(identity.instanceId == spec.instanceId) { "engine launch instance mismatch" }
                require(identity.proxyActivityClassName == spec.proxyActivityClassName) {
                    "engine launch proxy mismatch"
                }
                require(identity.guestActivityClassName == spec.guestActivityClassName) {
                    "engine launch guest Activity mismatch"
                }
                putExtra(EXTRA_ENGINE_RUNTIME_EPOCH, identity.runtimeEpoch)
                putExtra(EXTRA_ENGINE_SESSION_ID, identity.engineSessionId)
                putExtra(EXTRA_ENGINE_PROCESS_SLOT, identity.processSlot)
                putExtra(EXTRA_ENGINE_PROXY_ACTIVITY_CLASS_NAME, identity.proxyActivityClassName)
                putExtra(EXTRA_ENGINE_LAUNCH_CAPABILITY, identity.capabilityToken)
            }
            if (!record.resultToToken.isNullOrBlank() && record.resultRequestCode >= 0) {
                putExtra(EXTRA_RESULT_TO_TOKEN, record.resultToToken)
                putExtra(EXTRA_RESULT_REQUEST_CODE, record.resultRequestCode)
            }
            if (sourceIntent != null) {
                VirtualActivityIntentStore.remember(spec.token, sourceIntent)
            }
            if (forceNewTask || sourceIntent?.safeFlags()?.hasFlag(Intent.FLAG_ACTIVITY_NEW_TASK) == true) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun createProxyLaunchSpec(
        record: VirtualActivityRecord,
        engineLaunchIdentity: VirtualActivityLaunchIdentity? = null
    ): ProxyActivityLaunchSpec {
        return ProxyActivityLaunchSpec(
            hostPackageName = hostPackageName,
            proxyActivityClassName = record.proxyActivityClassName,
            token = record.token,
            instanceId = record.instanceId,
            originPackageName = record.originPackageName,
            guestActivityClassName = record.guestActivityClassName,
            launchMode = record.launchMode,
            taskAffinity = record.taskAffinity,
            engineLaunchIdentity = engineLaunchIdentity
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

    private fun rootTaskAffinity(originPackageName: String, instanceId: String): String =
        "$originPackageName:$instanceId"
}
