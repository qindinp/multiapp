package com.multiapp.core.loader

import android.content.ContentProvider
import android.content.Context
import android.content.pm.ProviderInfo
import com.multiapp.core.model.virtual.VirtualContextConfig
import java.util.concurrent.CompletableFuture
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
    private val providers = ConcurrentHashMap<ProviderRuntimeKey, CompletableFuture<ProviderCreation>>()

    fun getOrCreate(request: VirtualProviderCreateRequest): VirtualProviderRuntimeResult {
        val key = key(request)
        val pending = CompletableFuture<ProviderCreation>()
        val active = providers.putIfAbsent(key, pending)
        if (active != null) {
            return active.join().toRuntimeResult(request.resolution, created = false)
        }

        val creation = createProvider(request)
        pending.complete(creation)
        if (creation !is ProviderCreation.Ready) {
            providers.remove(key, pending)
        }
        return creation.toRuntimeResult(request.resolution, created = true)
    }

    private fun createProvider(request: VirtualProviderCreateRequest): ProviderCreation {
        val provider = try {
            providerFactory.create(
                classLoader = request.guestClassLoader,
                className = request.resolution.providerClassName
            )
        } catch (error: Throwable) {
            return ProviderCreation.CreateFailure(error)
        }

        val attachResult = runCatching {
            providerAttacher.attach(provider, request.guestContext, request.resolution.providerInfo)
        }
        if (attachResult.isFailure) {
            return ProviderCreation.AttachFailure(
                provider = provider,
                error = attachResult.exceptionOrNull()
                    ?: IllegalStateException("attach failed without throwable")
            )
        }

        return ProviderCreation.Ready(provider)
    }

    fun get(request: VirtualProviderCreateRequest): ContentProvider? =
        providers[key(request)]?.readyProvider()

    fun get(resolution: VirtualProviderResolution): ContentProvider? {
        val matchingProviders = providers.entries.asSequence()
            .filter { (key, _) -> key.matches(resolution) }
            .mapNotNull { (_, pending) -> pending.readyProvider() }
            .distinct()
            .toList()
        return matchingProviders.singleOrNull()
    }

    private fun CompletableFuture<ProviderCreation>.readyProvider(): ContentProvider? {
        if (!isDone || isCompletedExceptionally) return null
        return (join() as? ProviderCreation.Ready)?.provider
    }

    fun clear() {
        providers.clear()
    }

    private fun key(request: VirtualProviderCreateRequest): ProviderRuntimeKey =
        ProviderRuntimeKey(
            instanceId = request.resolution.instanceId,
            originPackageName = request.resolution.originPackageName,
            virtualPackageName = request.resolution.virtualPackageName,
            providerClassName = request.resolution.providerClassName,
            generationKey = request.generationKey
        )

    private fun ProviderCreation.toRuntimeResult(
        resolution: VirtualProviderResolution,
        created: Boolean
    ): VirtualProviderRuntimeResult = when (this) {
        is ProviderCreation.Ready -> if (created) {
            VirtualProviderRuntimeResult.Created(resolution, provider)
        } else {
            VirtualProviderRuntimeResult.Cached(resolution, provider)
        }
        is ProviderCreation.CreateFailure -> VirtualProviderRuntimeResult.CreateFailed(resolution, error)
        is ProviderCreation.AttachFailure -> VirtualProviderRuntimeResult.AttachFailed(resolution, provider, error)
    }

    private data class ProviderRuntimeKey(
        val instanceId: String,
        val originPackageName: String,
        val virtualPackageName: String,
        val providerClassName: String,
        val generationKey: String
    ) {
        fun matches(resolution: VirtualProviderResolution): Boolean =
            instanceId == resolution.instanceId &&
                originPackageName == resolution.originPackageName &&
                virtualPackageName == resolution.virtualPackageName &&
                providerClassName == resolution.providerClassName
    }

    private sealed class ProviderCreation {
        data class Ready(val provider: ContentProvider) : ProviderCreation()
        data class CreateFailure(val error: Throwable) : ProviderCreation()
        data class AttachFailure(
            val provider: ContentProvider,
            val error: Throwable
        ) : ProviderCreation()
    }

    companion object {
        val global: VirtualProviderRuntime = VirtualProviderRuntime()
    }
}

data class VirtualProviderCreateRequest(
    val resolution: VirtualProviderResolution,
    val guestContext: Context,
    val guestClassLoader: ClassLoader,
    val config: VirtualContextConfig,
    val generationKey: String = providerRuntimeGenerationKey(config)
)

internal fun providerRuntimeGenerationKey(config: VirtualContextConfig): String {
    val snapshot = config.packageSnapshot
    val parts = buildList {
        add(snapshot?.sourceSha256.orEmpty())
        add(snapshot?.sourceDir ?: config.sourceDir)
        add(snapshot?.publicSourceDir.orEmpty())
        add(snapshot?.versionCode?.toString().orEmpty())
        val splitSourceDirs = snapshot?.splitSourceDirs.orEmpty()
        add(splitSourceDirs.size.toString())
        addAll(splitSourceDirs)
        val splitPublicSourceDirs = snapshot?.splitPublicSourceDirs.orEmpty()
        add(splitPublicSourceDirs.size.toString())
        addAll(splitPublicSourceDirs)
        val splitNames = snapshot?.splitNames.orEmpty()
        add(splitNames.size.toString())
        addAll(splitNames)
        val splitSha256s = snapshot?.splitSha256s.orEmpty()
        add(splitSha256s.size.toString())
        addAll(splitSha256s)
        add(snapshot?.isolatedSplits?.toString().orEmpty())
    }
    return parts.joinToString("|") { part -> "${part.length}:$part" }
}

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
