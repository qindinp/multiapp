package com.multiapp.app.container

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.multiapp.core.engine.EngineActivityLaunchRequest
import com.multiapp.core.engine.EngineActivityLaunchIdentity
import com.multiapp.core.engine.EngineActivityLauncher
import com.multiapp.core.engine.EngineActivityProxyLauncher
import com.multiapp.core.engine.EngineActivityTaskControllers
import com.multiapp.core.engine.EngineLaunchSpec
import com.multiapp.core.engine.EngineProxyActivitySlots
import com.multiapp.core.engine.EngineProcessBootstrapState
import com.multiapp.core.model.engine.EngineResultStatus

class EngineReadyActivityLauncher(
    context: Context,
    private val proxyLauncher: EngineActivityProxyLauncher = EngineActivityProxyLauncher()
) : EngineActivityLauncher {
    private val hostContext = context.applicationContext ?: context

    override fun launch(spec: EngineLaunchSpec) {
        validateSpec(spec)
        hostContext.sendBroadcast(EngineForegroundLaunchRequest.intent(hostContext, spec))
    }

    internal fun launchFromHost(request: EngineForegroundLaunchRequest) {
        val expectedProcessSlot = EngineProxyActivitySlots.processSlotForClassName(
            hostPackageName = hostContext.packageName,
            className = request.proxySlot
        )
        check(expectedProcessSlot == request.processSlot) {
            "proxy slot process mismatch: expected=$expectedProcessSlot,actual=${request.processSlot}"
        }
        proxyLauncher.launchGuestLauncher(
            EngineActivityLaunchRequest(
                hostContext = hostContext,
                candidateProxyActivityClassNames = listOf(request.proxySlot),
                proxyLaunchModeByClassName = EngineProxyActivitySlots.launchModeByClassName(hostContext.packageName),
                instanceId = request.instanceId,
                originPackageName = request.originPackageName,
                guestActivityClassName = request.guestActivityClassName,
                launchMode = request.launchMode,
                taskAffinity = request.taskAffinity,
                launchAction = request.launchAction,
                launchIdentity = EngineActivityLaunchIdentity(
                    capabilityToken = request.launchCapabilityToken,
                    instanceId = request.instanceId,
                    runtimeEpoch = request.runtimeEpoch,
                    engineSessionId = request.engineSessionId,
                    processSlot = request.processSlot,
                    proxyActivityClassName = request.proxySlot,
                    guestActivityClassName = request.guestActivityClassName
                )
            )
        ).getOrElse { error -> throw error }
        EngineActivityTaskControllers.fileBacked(hostContext).persist(request.instanceId)
        writeLaunchEvidence(
            request = request,
            status = "PROXY_LAUNCHED",
            detail = "engine foreground proxy launch dispatched"
        )
    }

    private fun writeLaunchEvidence(
        request: EngineForegroundLaunchRequest,
        status: String,
        detail: String
    ) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = hostContext,
                instanceId = request.instanceId,
                component = "launch",
                fields = linkedMapOf(
                    "status" to status,
                    "stage" to "ACTIVITY_PROXY",
                    "launchPath" to "ENGINE_FOREGROUND",
                    "instanceId" to request.instanceId,
                    "originPackageName" to request.originPackageName,
                    "guestActivityClassName" to request.guestActivityClassName,
                    "processSlot" to request.processSlot,
                    "proxySlot" to request.proxySlot,
                    "runtimeEpoch" to request.runtimeEpoch,
                    "engineSessionId" to request.engineSessionId,
                    "detail" to detail
                )
            )
        }.onFailure { error ->
            runCatching {
                Log.w(TAG, "Unable to write launch evidence for instanceId=${request.instanceId}", error)
            }
        }
    }

    private fun validateSpec(spec: EngineLaunchSpec) {
        check(spec.bootstrapState == EngineProcessBootstrapState.READY) {
            "foreground launch requires a READY process bootstrap"
        }
        check(spec.bootstrapVerdict != EngineResultStatus.FAIL) {
            "foreground launch requires a non-failing process bootstrap"
        }
        check(spec.processId > 0) { "foreground launch requires a target process id" }
        val expectedProcessSlot = EngineProxyActivitySlots.processSlotForClassName(
            hostPackageName = hostContext.packageName,
            className = spec.proxySlot
        )
        check(expectedProcessSlot == spec.processSlot) {
            "proxy slot process mismatch: expected=$expectedProcessSlot,actual=${spec.processSlot}"
        }
    }

    private companion object {
        const val TAG = "EngineReadyActivityLauncher"
    }
}

internal data class EngineForegroundLaunchRequest(
    val instanceId: String,
    val originPackageName: String,
    val guestActivityClassName: String,
    val launchMode: String?,
    val taskAffinity: String,
    val processSlot: String,
    val proxySlot: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val launchCapabilityToken: String,
    val launchAction: String? = null
) {
    companion object {
        private const val EXTRA_INSTANCE_ID = "multiapp.foregroundLaunch.instanceId"
        private const val EXTRA_ORIGIN_PACKAGE_NAME = "multiapp.foregroundLaunch.originPackageName"
        private const val EXTRA_GUEST_ACTIVITY_CLASS_NAME = "multiapp.foregroundLaunch.guestActivityClassName"
        private const val EXTRA_LAUNCH_MODE = "multiapp.foregroundLaunch.launchMode"
        private const val EXTRA_TASK_AFFINITY = "multiapp.foregroundLaunch.taskAffinity"
        private const val EXTRA_PROCESS_SLOT = "multiapp.foregroundLaunch.processSlot"
        private const val EXTRA_PROXY_SLOT = "multiapp.foregroundLaunch.proxySlot"
        private const val EXTRA_RUNTIME_EPOCH = "multiapp.foregroundLaunch.runtimeEpoch"
        private const val EXTRA_ENGINE_SESSION_ID = "multiapp.foregroundLaunch.engineSessionId"
        private const val EXTRA_LAUNCH_CAPABILITY = "multiapp.foregroundLaunch.launchCapability"
        private const val EXTRA_LAUNCH_ACTION = "multiapp.foregroundLaunch.launchAction"

        fun intent(context: Context, spec: EngineLaunchSpec): Intent = Intent().apply {
            component = ComponentName(context, EngineForegroundLaunchReceiver::class.java)
            putExtra(EXTRA_INSTANCE_ID, spec.instanceId)
            putExtra(EXTRA_ORIGIN_PACKAGE_NAME, spec.originPackageName)
            putExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME, spec.guestActivityClassName)
            putExtra(EXTRA_LAUNCH_MODE, spec.launchMode)
            putExtra(EXTRA_TASK_AFFINITY, spec.taskAffinity)
            putExtra(EXTRA_PROCESS_SLOT, spec.processSlot)
            putExtra(EXTRA_PROXY_SLOT, spec.proxySlot)
            putExtra(EXTRA_RUNTIME_EPOCH, spec.runtimeEpoch)
            putExtra(EXTRA_ENGINE_SESSION_ID, spec.engineSessionId)
            putExtra(EXTRA_LAUNCH_CAPABILITY, spec.launchCapabilityToken)
            putExtra(EXTRA_LAUNCH_ACTION, spec.launchAction)
        }

        fun fromIntent(intent: Intent): EngineForegroundLaunchRequest? {
            val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID).orEmpty()
            val originPackageName = intent.getStringExtra(EXTRA_ORIGIN_PACKAGE_NAME).orEmpty()
            val guestActivityClassName = intent.getStringExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME).orEmpty()
            val taskAffinity = intent.getStringExtra(EXTRA_TASK_AFFINITY).orEmpty()
            val processSlot = intent.getStringExtra(EXTRA_PROCESS_SLOT).orEmpty()
            val proxySlot = intent.getStringExtra(EXTRA_PROXY_SLOT).orEmpty()
            val runtimeEpoch = intent.getLongExtra(EXTRA_RUNTIME_EPOCH, 0L)
            val engineSessionId = intent.getStringExtra(EXTRA_ENGINE_SESSION_ID).orEmpty()
            val launchCapabilityToken = intent.getStringExtra(EXTRA_LAUNCH_CAPABILITY).orEmpty()
            if (
                instanceId.isBlank() || originPackageName.isBlank() || guestActivityClassName.isBlank() ||
                taskAffinity.isBlank() || processSlot.isBlank() || proxySlot.isBlank() || runtimeEpoch <= 0L ||
                engineSessionId.isBlank() || launchCapabilityToken.isBlank()
            ) {
                return null
            }
            return EngineForegroundLaunchRequest(
                instanceId = instanceId,
                originPackageName = originPackageName,
                guestActivityClassName = guestActivityClassName,
                launchMode = intent.getStringExtra(EXTRA_LAUNCH_MODE),
                taskAffinity = taskAffinity,
                processSlot = processSlot,
                proxySlot = proxySlot,
                runtimeEpoch = runtimeEpoch,
                engineSessionId = engineSessionId,
                launchCapabilityToken = launchCapabilityToken,
                launchAction = intent.getStringExtra(EXTRA_LAUNCH_ACTION)
            )
        }
    }
}
