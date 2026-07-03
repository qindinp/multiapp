package com.multiapp.core.loader

enum class VirtualAmsApiEvidenceComponent(val componentName: String) {
    REGISTER_RECEIVER("ams-register-receiver"),
    STICKY_ORDERED_BROADCAST("ams-sticky-ordered-broadcast"),
    BIND_SERVICE_OVERLOAD("ams-bind-service-overload")
}

data class VirtualAmsApiEvidenceRecord(
    val component: VirtualAmsApiEvidenceComponent,
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val api: String,
    val status: String,
    val hostFallback: Boolean,
    val fields: Map<String, Any?> = emptyMap()
)

fun interface VirtualAmsApiEvidenceRecorder {
    fun record(record: VirtualAmsApiEvidenceRecord)
}

object VirtualAmsApiEvidenceRecorders {
    private val noOp = VirtualAmsApiEvidenceRecorder { }

    @Volatile
    private var delegate: VirtualAmsApiEvidenceRecorder = noOp

    fun install(recorder: VirtualAmsApiEvidenceRecorder) {
        delegate = recorder
    }

    fun reset() {
        delegate = noOp
    }

    internal fun current(): VirtualAmsApiEvidenceRecorder = delegate
}

object GlobalVirtualAmsApiEvidenceRecorder : VirtualAmsApiEvidenceRecorder {
    override fun record(record: VirtualAmsApiEvidenceRecord) {
        VirtualAmsApiEvidenceRecorders.current().record(record)
    }
}
