package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualAmsApiEvidenceComponent
import com.multiapp.core.loader.VirtualAmsApiEvidenceRecord

enum class EngineAmsApiEvidenceComponent(val componentName: String) {
    START_ACTIVITY_OVERLOAD("ams-start-activity-overload"),
    START_ACTIVITIES_OVERLOAD("ams-start-activities-overload"),
    START_SERVICE("ams-start-service"),
    STOP_SERVICE("ams-stop-service"),
    REGISTER_RECEIVER("ams-register-receiver"),
    STICKY_ORDERED_BROADCAST("ams-sticky-ordered-broadcast"),
    START_FOREGROUND_SERVICE("ams-start-foreground-service"),
    BIND_SERVICE_OVERLOAD("ams-bind-service-overload"),
    UNBIND_SERVICE_OVERLOAD("ams-unbind-service-overload");

    companion object {
        fun fromLoader(component: VirtualAmsApiEvidenceComponent): EngineAmsApiEvidenceComponent =
            when (component) {
                VirtualAmsApiEvidenceComponent.START_ACTIVITY_OVERLOAD -> START_ACTIVITY_OVERLOAD
                VirtualAmsApiEvidenceComponent.START_ACTIVITIES_OVERLOAD -> START_ACTIVITIES_OVERLOAD
                VirtualAmsApiEvidenceComponent.START_SERVICE -> START_SERVICE
                VirtualAmsApiEvidenceComponent.STOP_SERVICE -> STOP_SERVICE
                VirtualAmsApiEvidenceComponent.REGISTER_RECEIVER -> REGISTER_RECEIVER
                VirtualAmsApiEvidenceComponent.STICKY_ORDERED_BROADCAST -> STICKY_ORDERED_BROADCAST
                VirtualAmsApiEvidenceComponent.START_FOREGROUND_SERVICE -> START_FOREGROUND_SERVICE
                VirtualAmsApiEvidenceComponent.BIND_SERVICE_OVERLOAD -> BIND_SERVICE_OVERLOAD
                VirtualAmsApiEvidenceComponent.UNBIND_SERVICE_OVERLOAD -> UNBIND_SERVICE_OVERLOAD
            }
    }
}

data class EngineAmsApiEvidenceRecord(
    val component: EngineAmsApiEvidenceComponent,
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val api: String,
    val status: String,
    val hostFallback: Boolean,
    val fields: Map<String, Any?> = emptyMap()
)

fun interface EngineAmsApiEvidenceRecorder {
    fun record(record: EngineAmsApiEvidenceRecord)
}

fun VirtualAmsApiEvidenceRecord.toEngineRecord(): EngineAmsApiEvidenceRecord =
    EngineAmsApiEvidenceRecord(
        component = EngineAmsApiEvidenceComponent.fromLoader(component),
        instanceId = instanceId,
        originPackageName = originPackageName,
        virtualPackageName = virtualPackageName,
        api = api,
        status = status,
        hostFallback = hostFallback,
        fields = fields
    )
