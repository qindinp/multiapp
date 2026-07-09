package com.multiapp.app.container

import android.content.Context
import android.util.Log
import com.multiapp.core.engine.EngineProviderOperationEvidenceBatch
import com.multiapp.core.engine.EngineProviderOperationEvidenceFacade

/** Writes explicit hosted Provider operation evidence that is not a ContentProvider entry point. */
object ContainerProviderOperationEvidence {
    private const val TAG = "ProviderOperationEvidence"

    fun writeUnsupportedOperations(context: Context, result: Any) {
        writeCapabilityOperations(context, result)
    }

    fun writeCapabilityOperations(context: Context, result: Any) {
        writeCapabilityOperations(
            context = context,
            evidence = EngineProviderOperationEvidenceFacade.capabilityEvidenceFromBootstrapResult(result)
        )
    }

    internal fun writeCapabilityOperations(context: Context, evidence: EngineProviderOperationEvidenceBatch) {
        evidence.entries.forEach { entry ->
            runCatching {
                ContainerRuntimeEvidenceWriter.write(
                    context = context,
                    instanceId = evidence.instanceId,
                    component = entry.component,
                    fields = entry.fields
                )
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "Unable to write provider ${entry.operationName} capability evidence for instanceId=${evidence.instanceId}",
                    error
                )
            }
        }
    }
}
