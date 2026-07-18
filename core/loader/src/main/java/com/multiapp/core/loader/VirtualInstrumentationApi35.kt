package com.multiapp.core.loader

import android.annotation.TargetApi
import android.app.Activity
import android.app.ComponentCaller
import android.app.Instrumentation
import android.content.Context
import android.content.Intent

@TargetApi(35)
internal class VirtualInstrumentationApi35(
    base: Instrumentation,
    processRuntime: VirtualProcessRuntime = VirtualProcessRuntime.global,
    activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager.global,
    activityOperations: VirtualActivityOperations = ManagerBackedVirtualActivityOperations(activityRecordManager),
    processHostContext: Context? = null
) : VirtualInstrumentation(
    base,
    processRuntime,
    activityRecordManager,
    activityOperations,
    processHostContext
) {

    override fun callActivityOnNewIntent(activity: Activity, intent: Intent, caller: ComponentCaller) {
        dispatchHostedNewIntent(activity, intent) { guestIntent ->
            base.callActivityOnNewIntent(activity, guestIntent, caller)
        }
    }
}
