package com.multiapp.core.loader

import android.content.ContentResolver
import android.content.Context
import com.multiapp.core.model.virtual.VirtualContextConfig

data class VirtualContentResolverFactoryRequest(
    val hostContext: Context,
    val config: VirtualContextConfig
)

fun interface VirtualContentResolverFactory {
    fun create(request: VirtualContentResolverFactoryRequest): ContentResolver?
}

/** Engine-owned extension point for the guest Context's resolver. */
object VirtualContentResolverFactories {
    @Volatile
    private var factory: VirtualContentResolverFactory? = null

    fun install(factory: VirtualContentResolverFactory) {
        this.factory = factory
    }

    fun reset() {
        factory = null
    }

    fun createOrNull(request: VirtualContentResolverFactoryRequest): ContentResolver? =
        factory?.create(request)
}
