package com.multiapp.core.loader

import android.content.Context
import com.multiapp.core.model.virtual.VirtualContextConfig

data class VirtualAmsComponentDispatcherFactoryRequest(
    val hostContext: Context,
    val config: VirtualContextConfig,
    val fallback: VirtualAmsComponentDispatcher
)

fun interface VirtualAmsComponentDispatcherFactory {
    fun create(request: VirtualAmsComponentDispatcherFactoryRequest): VirtualAmsComponentDispatcher?
}

object VirtualAmsComponentDispatchers {
    @Volatile
    private var factory: VirtualAmsComponentDispatcherFactory? = null

    fun install(factory: VirtualAmsComponentDispatcherFactory) {
        this.factory = factory
    }

    fun reset() {
        factory = null
    }

    fun createOrNull(request: VirtualAmsComponentDispatcherFactoryRequest): VirtualAmsComponentDispatcher? =
        factory?.create(request)
}
