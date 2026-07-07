package com.multiapp.app.container

import android.content.Context
import com.multiapp.core.engine.HostedRuntimeEngine
import com.multiapp.core.loader.HostedBootstrapResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface HostedRuntimeEngineEntryPoint {
    fun hostedRuntimeEngine(): HostedRuntimeEngine
}

internal fun hostedRuntimeEngineFrom(context: Context): HostedRuntimeEngine {
    val appContext = context.applicationContext ?: context
    return EntryPointAccessors.fromApplication(
        appContext,
        HostedRuntimeEngineEntryPoint::class.java
    ).hostedRuntimeEngine()
}

internal fun runHostedRuntimeBootstrap(
    hostContext: Context,
    instanceId: String,
    providerHookEnabled: Boolean = true
): HostedBootstrapResult =
    hostedRuntimeEngineFrom(hostContext).runBootstrap(instanceId, providerHookEnabled)
