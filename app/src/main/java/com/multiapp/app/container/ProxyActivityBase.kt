package com.multiapp.app.container

import android.app.ActivityManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.multiapp.core.engine.EngineProxyActivityObserveRequest
import com.multiapp.core.engine.EngineProxyActivityRecords

abstract class ProxyActivityBase : Activity() {

    companion object {
        private const val TAG = "ProxyActivity"
        private const val FALLBACK_ACTION_FINISH_UNSUBSTITUTED = "finishUnsubstitutedProxy"
        private const val FALLBACK_ACTION_PREWARM_RELAUNCH = "prewarmAndRelaunchProxy"
        private const val EXTRA_PROXY_RECOVERY_ATTEMPT = "multiapp.proxyRecoveryAttempt"
        private const val MAX_PROXY_RECOVERY_ATTEMPTS = 1

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

    private fun handleProxyIntent(proxyIntent: Intent, lifecycleEvent: String) {
        val instanceId = proxyIntent.getStringExtra(EXTRA_INSTANCE_ID)
        val token = proxyIntent.getStringExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN).orEmpty()
        val guestActivity = proxyIntent.getStringExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME).orEmpty()
        val originPackage = proxyIntent.getStringExtra(EXTRA_ORIGIN_PACKAGE_NAME).orEmpty()
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

        val fallbackAction = if (canRecoverUnsubstitutedProxy(
                recoveryAttempt = recoveryAttempt,
                guestActivity = guestActivity,
                originPackage = originPackage
            )
        ) {
            FALLBACK_ACTION_PREWARM_RELAUNCH
        } else {
            FALLBACK_ACTION_FINISH_UNSUBSTITUTED
        }

        Log.i(
            TAG,
            "Proxy resumed: proxy=${javaClass.name}, lifecycle=$lifecycleEvent, instanceId=$instanceId, " +
                "token=$token, origin=$originPackage, guest=$guestActivity, " +
                "recordFound=${observation.recordFound}, recordRecovered=${observation.recordRecovered}, " +
                "recoveryAttempt=$recoveryAttempt, fallbackAction=$fallbackAction"
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
                fallbackAction = fallbackAction
            )
        )
        if (fallbackAction == FALLBACK_ACTION_PREWARM_RELAUNCH) {
            prewarmAndRelaunchUnsubstitutedProxy(
                instanceId = instanceId,
                proxyIntent = proxyIntent,
                recoveryAttempt = recoveryAttempt,
                token = token,
                guestActivity = guestActivity,
                lifecycleEvent = lifecycleEvent
            )
        } else {
            finishUnsubstitutedProxy(
                instanceId = instanceId,
                token = token,
                guestActivity = guestActivity,
                lifecycleEvent = lifecycleEvent,
                recoveryAttempt = recoveryAttempt
            )
        }
    }

    private fun canRecoverUnsubstitutedProxy(
        recoveryAttempt: Int,
        guestActivity: String,
        originPackage: String
    ): Boolean =
        recoveryAttempt < MAX_PROXY_RECOVERY_ATTEMPTS &&
            guestActivity.isNotBlank() &&
            originPackage.isNotBlank()

    private fun prewarmAndRelaunchUnsubstitutedProxy(
        instanceId: String,
        proxyIntent: Intent,
        recoveryAttempt: Int,
        token: String,
        guestActivity: String,
        lifecycleEvent: String
    ) {
        val hostContext = applicationContext ?: this
        Thread(
            {
                prepareBackgroundLooperIfNeeded()
                val bindResult = HostedActivityRuntimeBinder().ensureBound(hostContext, instanceId)
                Handler(Looper.getMainLooper()).post {
                    val relaunched = relaunchProxyAfterPrewarm(
                        bindResult = bindResult,
                        proxyIntent = proxyIntent,
                        nextRecoveryAttempt = recoveryAttempt + 1
                    )
                    writeProxyRecoveryEvidence(
                        instanceId = instanceId,
                        token = token,
                        guestActivity = guestActivity,
                        lifecycleEvent = lifecycleEvent,
                        recoveryAttempt = recoveryAttempt,
                        bindResult = bindResult,
                        relaunched = relaunched
                    )
                    finish()
                }
                if (bindResult is HostedActivityRuntimeBindResult.Bound &&
                    bindResult.status == "BOUND" &&
                    bindResult.result.guestApplication != null
                ) {
                    Looper.loop()
                }
            },
            "multiapp-proxy-recover-${instanceId.take(8)}"
        ).start()
    }

    private fun prepareBackgroundLooperIfNeeded() {
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }
    }

    private fun relaunchProxyAfterPrewarm(
        bindResult: HostedActivityRuntimeBindResult,
        proxyIntent: Intent,
        nextRecoveryAttempt: Int
    ): Boolean {
        if (bindResult !is HostedActivityRuntimeBindResult.Bound) return false
        if (!bindResult.result.success || bindResult.result.guestClassLoader == null) return false
        return runCatching {
            val relaunchIntent = Intent(proxyIntent)
                .putExtra(EXTRA_PROXY_RECOVERY_ATTEMPT, nextRecoveryAttempt)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val hostContext = applicationContext ?: this
            hostContext.startActivity(relaunchIntent)
        }.onFailure { error ->
            Log.w(TAG, "Unable to relaunch proxy after runtime prewarm: proxy=${javaClass.name}", error)
        }.isSuccess
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
                "instanceId=$instanceId, token=$token, guest=$guestActivity, recoveryAttempt=$recoveryAttempt"
        )
        finish()
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

    private fun writeProxyRecoveryEvidence(
        instanceId: String,
        token: String,
        guestActivity: String,
        lifecycleEvent: String,
        recoveryAttempt: Int,
        bindResult: HostedActivityRuntimeBindResult,
        relaunched: Boolean
    ) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = this,
                instanceId = instanceId,
                component = "activity-proxy-recovery",
                fields = linkedMapOf(
                    "status" to if (relaunched) "RELAUNCHED" else "FAILED",
                    "stage" to "ACTIVITY_PROXY_RECOVERY",
                    "detail" to javaClass.name,
                    "lifecycleEvent" to lifecycleEvent,
                    "token" to token,
                    "guestActivityClassName" to guestActivity,
                    "recoveryAttempt" to recoveryAttempt.toString(),
                    "fallbackAction" to FALLBACK_ACTION_PREWARM_RELAUNCH,
                    "runtimeBindStatus" to bindResult.status,
                    "runtimeBindDetail" to bindResult.detail,
                    "runtimeBindErrorClass" to ((bindResult as? HostedActivityRuntimeBindResult.Failed)
                        ?.errorClassName.orEmpty()),
                    "runtimeBindErrorMessage" to ((bindResult as? HostedActivityRuntimeBindResult.Failed)
                        ?.errorMessage.orEmpty()),
                    "relaunched" to relaunched.toString()
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write proxy recovery evidence for instanceId=$instanceId", error)
        }
    }
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
