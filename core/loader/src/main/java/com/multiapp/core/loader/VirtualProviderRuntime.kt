package com.multiapp.core.loader

import android.content.ContentProvider
import android.content.Context
import android.content.pm.ProviderInfo
import com.multiapp.core.model.virtual.VirtualContextConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime cache and lifecycle entry for guest ContentProviders.
 *
 * This mirrors the provider runtime layer used by VirtualApp/DroidPlugin-style
 * containers, but keeps attach failures explicit instead of silently pretending
 * provider virtualization is complete.
 */
class VirtualProviderRuntime(
    private val providerFactory: ProviderFactory = ReflectionProviderFactory,
    private val providerAttacher: ProviderAttacher = DefaultProviderAttacher
) {
    private val providers = ConcurrentHashMap<String, ContentProvider>()

    fun getOrCreate(request: VirtualProviderCreateRequest): VirtualProviderRuntimeResult {
        val key = key(request.resolution)
        providers[key]?.let { provider ->
            return VirtualProviderRuntimeResult.Cached(
                resolution = request.resolution,
                provider = provider
            )
        }

        val provider = try {
            providerFactory.create(
                classLoader = request.guestClassLoader,
                className = request.resolution.providerClassName
            )
        } catch (error: Throwable) {
            return VirtualProviderRuntimeResult.CreateFailed(request.resolution, error)
        }

        val attachResult = runCatching {
            providerAttacher.attach(provider, request.guestContext, request.resolution.providerInfo)
        }
        if (attachResult.isFailure) {
            return VirtualProviderRuntimeResult.AttachFailed(
                resolution = request.resolution,
                provider = provider,
                error = attachResult.exceptionOrNull()
                    ?: IllegalStateException("attach failed without throwable")
            )
        }

        providers[key] = provider
        return VirtualProviderRuntimeResult.Created(
            resolution = request.resolution,
            provider = provider
        )
    }

    fun get(resolution: VirtualProviderResolution): ContentProvider? = providers[key(resolution)]

    fun clear() {
        providers.clear()
    }

    private fun key(resolution: VirtualProviderResolution): String =
        "${resolution.instanceId}:${resolution.guestAuthority}:${resolution.providerClassName}"

    companion object {
        val global: VirtualProviderRuntime = VirtualProviderRuntime()
    }
}

data class VirtualProviderCreateRequest(
    val resolution: VirtualProviderResolution,
    val guestContext: Context,
    val guestClassLoader: ClassLoader,
    val config: VirtualContextConfig
)

sealed class VirtualProviderRuntimeResult {
    abstract val resolution: VirtualProviderResolution

    data class Created(
        override val resolution: VirtualProviderResolution,
        val provider: ContentProvider
    ) : VirtualProviderRuntimeResult()

    data class Cached(
        override val resolution: VirtualProviderResolution,
        val provider: ContentProvider
    ) : VirtualProviderRuntimeResult()

    data class CreateFailed(
        override val resolution: VirtualProviderResolution,
        val error: Throwable
    ) : VirtualProviderRuntimeResult()

    data class AttachFailed(
        override val resolution: VirtualProviderResolution,
        val provider: ContentProvider,
        val error: Throwable
    ) : VirtualProviderRuntimeResult()
}

fun interface ProviderFactory {
    fun create(classLoader: ClassLoader, className: String): ContentProvider
}

fun interface ProviderAttacher {
    fun attach(provider: ContentProvider, context: Context, info: ProviderInfo)
}

object ReflectionProviderFactory : ProviderFactory {
    override fun create(classLoader: ClassLoader, className: String): ContentProvider {
        val clazz = classLoader.loadClass(className)
        return clazz.getDeclaredConstructor().newInstance() as ContentProvider
    }
}

object DefaultProviderAttacher : ProviderAttacher {
    override fun attach(provider: ContentProvider, context: Context, info: ProviderInfo) {
        provider.attachInfo(context, info)
    }
}
