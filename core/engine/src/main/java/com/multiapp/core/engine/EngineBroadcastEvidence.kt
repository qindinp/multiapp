package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualBroadcastRecord
import com.multiapp.core.loader.VirtualBroadcastResultCode

data class EngineBroadcastRecord(
    val instanceId: String?,
    val receiverClassName: String?,
    val action: String?,
    val result: EngineBroadcastResultCode
)

enum class EngineBroadcastResultCode {
    Delivered,
    NoPackageSnapshot,
    UnsupportedImplicit,
    ReceiverNotFound,
    ReceiverClassNotFound,
    ReceiverCreateFailed,
    OnReceiveFailed;

    companion object {
        fun fromLoader(result: VirtualBroadcastResultCode): EngineBroadcastResultCode =
            when (result) {
                VirtualBroadcastResultCode.Delivered -> Delivered
                VirtualBroadcastResultCode.NoPackageSnapshot -> NoPackageSnapshot
                VirtualBroadcastResultCode.UnsupportedImplicit -> UnsupportedImplicit
                VirtualBroadcastResultCode.ReceiverNotFound -> ReceiverNotFound
                VirtualBroadcastResultCode.ReceiverClassNotFound -> ReceiverClassNotFound
                VirtualBroadcastResultCode.ReceiverCreateFailed -> ReceiverCreateFailed
                VirtualBroadcastResultCode.OnReceiveFailed -> OnReceiveFailed
            }
    }
}

fun interface EngineBroadcastRecorder {
    fun record(record: EngineBroadcastRecord)
}

fun VirtualBroadcastRecord.toEngineRecord(): EngineBroadcastRecord =
    EngineBroadcastRecord(
        instanceId = instanceId,
        receiverClassName = receiverClassName,
        action = action,
        result = EngineBroadcastResultCode.fromLoader(result)
    )
