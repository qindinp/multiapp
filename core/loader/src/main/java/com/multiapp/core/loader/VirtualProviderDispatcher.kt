package com.multiapp.core.loader

import android.content.Context
import android.net.Uri
import com.multiapp.core.model.virtual.VirtualContextConfig

/** Dispatches host stub provider calls to the virtual provider registry. */
class VirtualProviderDispatcher(
    private val hostPackageName: String,
    private val packageRegistry: VirtualPackageRegistry = VirtualPackageRegistry.global,
    private val providerManager: VirtualProviderManager = VirtualProviderManager(hostPackageName),
    private val processRuntime: VirtualProcessRuntime = VirtualProcessRuntime.global,
    private val providerRuntime: VirtualProviderRuntime = VirtualProviderRuntime.global,
    private val hostContext: Context? = null
) {
    fun dispatch(uri: Uri): VirtualProviderDispatchResult {
        val instanceId = uri.getQueryParameter(VirtualProviderManager.PROXY_INSTANCE_ID)
            ?: return VirtualProviderDispatchResult.InvalidProxyUri("missing instanceId")
        val guestAuthority = uri.getQueryParameter(VirtualProviderManager.PROXY_GUEST_AUTHORITY)
            ?: return VirtualProviderDispatchResult.InvalidProxyUri("missing guestAuthority")
        return dispatch(instanceId, guestAuthority)
    }

    fun dispatch(instanceId: String, guestAuthority: String): VirtualProviderDispatchResult {
        val snapshot = packageRegistry.getByInstanceId(instanceId)
            ?: return VirtualProviderDispatchResult.InstanceNotFound(instanceId)

        val resolution = providerManager.resolve(snapshot, guestAuthority)
            ?: return VirtualProviderDispatchResult.ProviderNotFound(
                instanceId = instanceId,
                guestAuthority = guestAuthority,
                evidence = VirtualProviderEvidence.acquisitionNotFound(instanceId, guestAuthority)
            )

        val runtimeRecord = processRuntime.get(instanceId)
            ?: return VirtualProviderDispatchResult.RuntimeNotBound(
                resolution = resolution,
                evidence = VirtualProviderEvidence.acquisition(
                    resolution = resolution,
                    success = false,
                    reason = "RUNTIME_NOT_BOUND"
                )
            )
        val bootstrapResult = runtimeRecord.result
        val guestClassLoader = bootstrapResult.guestClassLoader
            ?: return VirtualProviderDispatchResult.RuntimeIncomplete(
                resolution = resolution,
                reason = "missing guestClassLoader",
                evidence = VirtualProviderEvidence.acquisition(
                    resolution = resolution,
                    success = false,
                    reason = "missing guestClassLoader"
                )
            )
        val context = hostContext
            ?: return VirtualProviderDispatchResult.RuntimeIncomplete(
                resolution = resolution,
                reason = "missing hostContext",
                evidence = VirtualProviderEvidence.acquisition(
                    resolution = resolution,
                    success = false,
                    reason = "missing hostContext"
                )
            )

        val config = VirtualContextConfig(
            instanceId = snapshot.instanceId,
            originPackageName = snapshot.originPackageName,
            virtualPackageName = snapshot.virtualPackageName,
            dataDir = snapshot.dataDir,
            sourceDir = snapshot.sourceDir,
            nativeLibraryDir = snapshot.nativeLibraryDir,
            classLoader = guestClassLoader,
            applicationLabel = snapshot.applicationLabel,
            packageSnapshot = snapshot
        )
        val guestContext = VirtualContextWrapper(
            base = context,
            config = config,
            guestClassLoader = guestClassLoader
        )

        return when (val provider = providerRuntime.getOrCreate(
            VirtualProviderCreateRequest(
                resolution = resolution,
                guestContext = guestContext,
                guestClassLoader = guestClassLoader,
                config = config
            )
        )) {
            is VirtualProviderRuntimeResult.Created -> VirtualProviderDispatchResult.ProviderReady(
                resolution = provider.resolution,
                provider = provider.provider,
                cached = false,
                evidence = VirtualProviderEvidence.acquisition(provider.resolution, success = true)
            )
            is VirtualProviderRuntimeResult.Cached -> VirtualProviderDispatchResult.ProviderReady(
                resolution = provider.resolution,
                provider = provider.provider,
                cached = true,
                evidence = VirtualProviderEvidence.acquisition(provider.resolution, success = true)
            )
            is VirtualProviderRuntimeResult.CreateFailed -> VirtualProviderDispatchResult.ProviderCreateFailed(
                resolution = provider.resolution,
                error = provider.error,
                evidence = VirtualProviderEvidence.acquisition(
                    resolution = provider.resolution,
                    success = false,
                    reason = provider.error.message ?: provider.error.javaClass.name
                )
            )
            is VirtualProviderRuntimeResult.AttachFailed -> VirtualProviderDispatchResult.ProviderAttachFailed(
                resolution = provider.resolution,
                error = provider.error,
                evidence = VirtualProviderEvidence.acquisition(
                    resolution = provider.resolution,
                    success = false,
                    reason = provider.error.message ?: provider.error.javaClass.name
                )
            )
        }
    }
}

sealed class VirtualProviderDispatchResult {
    data class ProviderReady(
        val resolution: VirtualProviderResolution,
        val provider: android.content.ContentProvider,
        val cached: Boolean,
        val evidence: VirtualProviderEvidence
    ) : VirtualProviderDispatchResult()

    data class RuntimeNotBound(
        val resolution: VirtualProviderResolution,
        val evidence: VirtualProviderEvidence
    ) : VirtualProviderDispatchResult()

    data class RuntimeIncomplete(
        val resolution: VirtualProviderResolution,
        val reason: String,
        val evidence: VirtualProviderEvidence
    ) : VirtualProviderDispatchResult()

    data class ProviderCreateFailed(
        val resolution: VirtualProviderResolution,
        val error: Throwable,
        val evidence: VirtualProviderEvidence
    ) : VirtualProviderDispatchResult()

    data class ProviderAttachFailed(
        val resolution: VirtualProviderResolution,
        val error: Throwable,
        val evidence: VirtualProviderEvidence
    ) : VirtualProviderDispatchResult()

    data class InvalidProxyUri(val reason: String) : VirtualProviderDispatchResult()

    data class InstanceNotFound(val instanceId: String) : VirtualProviderDispatchResult()

    data class ProviderNotFound(
        val instanceId: String,
        val guestAuthority: String,
        val evidence: VirtualProviderEvidence
    ) : VirtualProviderDispatchResult()
}
