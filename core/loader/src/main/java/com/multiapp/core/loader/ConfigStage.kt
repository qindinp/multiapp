package com.multiapp.core.loader

import com.multiapp.core.model.instance.InstanceManager

class ConfigStage(
    private val instanceManager: InstanceManager,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val instance = runCatching {
            instanceManager.getInstance(input.instanceId)
        }.getOrNull()
        val durationMs = clock() - startMs

        if (instance == null) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.failed(
                    stage = RuntimeStage.CONFIG,
                    message = "Instance not found: ${input.instanceId}",
                    durationMs = durationMs
                )
            )
        }

        return BootstrapStageOutput(
            context = input.copy(instance = instance),
            result = BootstrapResult.success(
                stage = RuntimeStage.CONFIG,
                message = "Instance loaded: ${instance.originPackageName}",
                evidence = listOf(
                    BootstrapEvidence("instanceId", input.instanceId),
                    BootstrapEvidence("originPackageName", instance.originPackageName)
                ),
                durationMs = durationMs
            )
        )
    }
}
