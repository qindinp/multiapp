package com.multiapp.app.container

import android.app.ActivityManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.engine.EngineActivityTaskController
import com.multiapp.core.engine.EngineActivityTaskControllers
import com.multiapp.core.engine.EngineProxyActivityObserveRequest
import com.multiapp.core.engine.EngineProxyActivityRecords
import com.multiapp.core.model.virtual.VirtualActivityState

abstract class ProxyActivityBase : Activity() {

    companion object {
        private const val TAG = "ProxyActivity"
        private const val FALLBACK_ACTION_FINISH_UNSUBSTITUTED =
            ProxyActivityCapabilityPolicy.UNSUBSTITUTED_ACTION
        private const val EXTRA_PROXY_RECOVERY_ATTEMPT = "multiapp.proxyRecoveryAttempt"

        const val EXTRA_VIRTUAL_ACTIVITY_TOKEN = "multiapp.virtualActivityToken"
        const val EXTRA_INSTANCE_ID = "multiapp.instanceId"
        const val EXTRA_ORIGIN_PACKAGE_NAME = "multiapp.originPackageName"
        const val EXTRA_GUEST_ACTIVITY_CLASS_NAME = "multiapp.guestActivityClassName"
        const val EXTRA_GUEST_ACTIVITY_LAUNCH_MODE = "multiapp.guestActivityLaunchMode"
        const val EXTRA_GUEST_TASK_AFFINITY = "multiapp.guestTaskAffinity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleProxyIntent(intent, lifecycleEvent = "onCreate")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProxyIntent(intent, lifecycleEvent = "onNewIntent")
    }

    override fun onResume() {
        super.onResume()
        markLifecycleState(VirtualActivityState.RESUMED, "onResume")
    }

    override fun onPause() {
        markLifecycleState(VirtualActivityState.PAUSED, "onPause")
        super.onPause()
    }

    override fun onStop() {
        markLifecycleState(VirtualActivityState.STOPPED, "onStop")
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            finishActivityRecord("onDestroy")
        } else {
            markLifecycleState(VirtualActivityState.STOPPED, "onDestroy")
        }
        super.onDestroy()
    }

    private fun handleProxyIntent(proxyIntent: Intent, lifecycleEvent: String) {
        val instanceId = proxyIntent.getStringExtra(EXTRA_INSTANCE_ID)
        val token = proxyIntent.getStringExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN).orEmpty()
        val tokenForEvidence = EvidenceSanitizer.redactTokenForEvidence(token)
        val guestActivity = proxyIntent.getStringExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME).orEmpty()
        val originPackage = proxyIntent.getStringExtra(EXTRA_ORIGIN_PACKAGE_NAME).orEmpty()
        if (!instanceId.isNullOrBlank()) {
            restoreActivityTaskState(instanceId, lifecycleEvent)
        }
        val observation = EngineProxyActivityRecords().observeProxyIntent(
            EngineProxyActivityObserveRequest(
                proxyActivityClassName = javaClass.name,
                proxyIntent = proxyIntent,
                instanceId = instanceId,
                token = token,
                guestActivityClassName = guestActivity,
                originPackageName = originPackage
            )
        )
        val recoveryAttempt = proxyIntent.getIntExtra(EXTRA_PROXY_RECOVERY_ATTEMPT, 0)

        if (instanceId.isNullOrBlank()) {
            Log.e(TAG, "Proxy launched without instanceId: proxy=${javaClass.name}")
            finish()
            return
        }

        val fallbackAction = FALLBACK_ACTION_FINISH_UNSUBSTITUTED

        Log.i(
            TAG,
            "Proxy resumed: proxy=${javaClass.name}, lifecycle=$lifecycleEvent, instanceId=$instanceId, " +
            "token=$tokenForEvidence, origin=$originPackage, guest=$guestActivity, " +
            "recordFound=${observation.recordFound}, recordRecovered=${observation.recordRecovered}, " +
            "recoveryAttempt=$recoveryAttempt, fallbackAction=$fallbackAction, " +
            "capabilityOwner=${ProxyActivityCapabilityPolicy.CAPABILITY_OWNER}"
        )
        val taskDescriptionLabel = ProxyTaskDescriptions.label(
            originPackageName = originPackage,
            instanceId = instanceId
        )
        applyTaskDescription(taskDescriptionLabel)
        writeProxyEvidence(
            instanceId = instanceId,
            evidence = ProxyActivityEvidence(
                proxyActivityClassName = javaClass.name,
                token = token,
                originPackageName = originPackage,
                guestActivityClassName = guestActivity,
                recordFound = observation.recordFound,
                recordRecovered = observation.recordRecovered,
                pendingNewIntent = observation.pendingNewIntent,
                result = observation.result,
                pendingNewIntentConsumed = false,
                resultConsumed = false,
                lifecycleEvent = lifecycleEvent,
                taskDescriptionLabel = taskDescriptionLabel,
                taskId = observation.taskId,
                taskAffinity = observation.taskAffinity,
                launchMode = observation.launchMode,
                intentFlags = observation.intentFlags,
                fallbackAction = fallbackAction
            )
        )
        persistActivityTaskState(instanceId, lifecycleEvent)
        finishUnsubstitutedProxy(
            instanceId = instanceId,
            token = token,
            guestActivity = guestActivity,
            lifecycleEvent = lifecycleEvent,
            recoveryAttempt = recoveryAttempt
        )
    }

    private fun finishUnsubstitutedProxy(
        instanceId: String,
        token: String,
        guestActivity: String,
        lifecycleEvent: String,
        recoveryAttempt: Int
    ) {
        Log.w(
            TAG,
            "Finishing unsubstituted proxy Activity: proxy=${javaClass.name}, lifecycle=$lifecycleEvent, " +
                "instanceId=$instanceId, token=${EvidenceSanitizer.redactTokenForEvidence(token)}, " +
                    "guest=$guestActivity, recoveryAttempt=$recoveryAttempt"
        )
        finish()
    }

    private fun activityTaskController(): EngineActivityTaskController =
        EngineActivityTaskControllers.fileBacked(this)

    private fun restoreActivityTaskState(instanceId: String, lifecycleEvent: String) {
        runCatching {
            val result = activityTaskController().restorePersistedIfEmpty(instanceId)
            writeActivityTaskStateEvidence(
                instanceId = instanceId,
                lifecycleEvent = lifecycleEvent,
                status = result.status,
                activityCount = result.activityCount,
                taskCount = result.taskCount,
                detail = result.detail
            )
        }.onFailure { error ->
            writeActivityTaskStateEvidence(
                instanceId = instanceId,
                lifecycleEvent = lifecycleEvent,
                status = "FAIL",
                activityCount = 0,
                taskCount = null,
                detail = error.message ?: error.javaClass.name
            )
        }
    }

    private fun persistActivityTaskState(instanceId: String, lifecycleEvent: String) {
        runCatching {
            val result = activityTaskController().persist(instanceId)
            writeActivityTaskStateEvidence(
                instanceId = instanceId,
                lifecycleEvent = lifecycleEvent,
                status = result.status,
                activityCount = result.activityCount,
                taskCount = result.taskCount,
                detail = result.detail
            )
        }.onFailure { error ->
            writeActivityTaskStateEvidence(
                instanceId = instanceId,
                lifecycleEvent = lifecycleEvent,
                status = "FAIL",
                activityCount = 0,
                taskCount = null,
                detail = error.message ?: error.javaClass.name
            )
        }
    }

    private fun markLifecycleState(state: VirtualActivityState, lifecycleEvent: String) {
        val instanceId = intent?.getStringExtra(EXTRA_INSTANCE_ID).orEmpty()
        val token = intent?.getStringExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN).orEmpty()
        if (instanceId.isBlank() || token.isBlank()) return
        runCatching {
            val result = activityTaskController().markState(instanceId, token, state)
            writeActivityTaskStateEvidence(
                instanceId = instanceId,
                lifecycleEvent = lifecycleEvent,
                status = result.status,
                activityCount = result.activityCount,
                taskCount = result.taskCount,
                detail = result.detail
            )
        }.onFailure { error ->
            writeActivityTaskStateEvidence(
                instanceId = instanceId,
                lifecycleEvent = lifecycleEvent,
                status = "FAIL",
                activityCount = 0,
                taskCount = null,
                detail = error.message ?: error.javaClass.name
            )
        }
    }

    private fun finishActivityRecord(lifecycleEvent: String) {
        val instanceId = intent?.getStringExtra(EXTRA_INSTANCE_ID).orEmpty()
        val token = intent?.getStringExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN).orEmpty()
        if (instanceId.isBlank() || token.isBlank()) return
        runCatching {
            val result = activityTaskController().finish(instanceId, token)
            writeActivityTaskStateEvidence(
                instanceId = instanceId,
                lifecycleEvent = lifecycleEvent,
                status = result.status,
                activityCount = result.activityCount,
                taskCount = result.taskCount,
                detail = result.detail
            )
        }.onFailure { error ->
            writeActivityTaskStateEvidence(
                instanceId = instanceId,
                lifecycleEvent = lifecycleEvent,
                status = "FAIL",
                activityCount = 0,
                taskCount = null,
                detail = error.message ?: error.javaClass.name
            )
        }
    }

    private fun writeActivityTaskStateEvidence(
        instanceId: String,
        lifecycleEvent: String,
        status: String,
        activityCount: Int,
        taskCount: Int?,
        detail: String = ""
    ) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = this,
                instanceId = instanceId,
                component = "activity-task-state-proxy",
                fields = linkedMapOf(
                    "status" to status,
                    "stage" to "proxy-$lifecycleEvent",
                    "detail" to detail,
                    "activityCount" to activityCount.toString(),
                    "taskCount" to (taskCount?.toString() ?: "")
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write proxy task-state evidence for instanceId=$instanceId", error)
        }
    }

    private fun applyTaskDescription(label: String) {
        runCatching {
            setTaskDescription(ActivityManager.TaskDescription(label))
        }.onFailure { error ->
            Log.w(TAG, "Unable to set proxy task description: $label", error)
        }
    }

    private fun writeProxyEvidence(
        instanceId: String,
        evidence: ProxyActivityEvidence
    ) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = this,
                instanceId = instanceId,
                component = "activity-proxy",
                fields = evidence.toFields()
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write proxy evidence for instanceId=$instanceId", error)
        }
    }

}

internal object ProxyActivityCapabilityPolicy {
    const val CAPABILITY_OWNER = "VIRTUAL_INSTRUMENTATION_FALLBACK"
    const val PROXY_MAY_CONSUME_CAPABILITY = false
    const val UNSUBSTITUTED_ACTION = "finishUnsubstitutedProxy"
}

class ProxyActivity0 : ProxyActivityBase()

class ProxyActivity1 : ProxyActivityBase()

class ProxyActivity2 : ProxyActivityBase()

class ProxyActivity3 : ProxyActivityBase()

class ProxyActivity4 : ProxyActivityBase()

class ProxyActivity5 : ProxyActivityBase()

class ProxyActivity6 : ProxyActivityBase()

class ProxyActivity7 : ProxyActivityBase()

class ProxyActivitySingleTop0 : ProxyActivityBase()

class ProxyActivitySingleTop1 : ProxyActivityBase()

class ProxyActivitySingleTop2 : ProxyActivityBase()

class ProxyActivitySingleTop3 : ProxyActivityBase()

class ProxyActivitySingleTop4 : ProxyActivityBase()

class ProxyActivitySingleTop5 : ProxyActivityBase()

class ProxyActivitySingleTop6 : ProxyActivityBase()

class ProxyActivitySingleTop7 : ProxyActivityBase()

class ProxyActivitySingleTask0 : ProxyActivityBase()

class ProxyActivitySingleTask1 : ProxyActivityBase()

class ProxyActivitySingleTask2 : ProxyActivityBase()

class ProxyActivitySingleTask3 : ProxyActivityBase()

class ProxyActivitySingleTask4 : ProxyActivityBase()

class ProxyActivitySingleTask5 : ProxyActivityBase()

class ProxyActivitySingleTask6 : ProxyActivityBase()

class ProxyActivitySingleTask7 : ProxyActivityBase()

internal object ProxyTaskDescriptions {
    fun label(originPackageName: String, instanceId: String): String {
        val origin = originPackageName.takeIf { it.isNotBlank() } ?: "Guest"
        val suffix = instanceId.takeIf { it.isNotBlank() }?.let { id ->
            if (id.length <= 8) id else id.take(8)
        } ?: "unknown"
        return "$origin #$suffix"
    }
}
