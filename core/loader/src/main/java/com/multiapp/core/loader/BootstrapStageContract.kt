package com.multiapp.core.loader

import android.app.Application
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.virtual.ResolvedPackage
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

/**
 * Explicit state passed between hosted runtime bootstrap stages.
 *
 * PR-4 starts with this small contract so later slices can extract stages
 * without hiding facts in local variables inside HostedRuntimeBootstrap.run().
 */
data class BootstrapStageInput(
    val instanceId: String,
    val instance: VirtualInstanceRecord? = null,
    val installRecord: InstallRecord? = null,
    val originApkPath: String? = null,
    val nativeLibraryDir: String? = null,
    val resolvedPackage: ResolvedPackage? = null,
    val packageSnapshot: VirtualPackageSnapshot? = null,
    val providerRoutingPlan: VirtualProviderRoutingPlan? = null,
    val guestClassLoader: ClassLoader? = null,
    val guestApplication: Application? = null,
    val launcherActivityClassName: String? = null
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
    }
}

/**
 * Result of a single hosted runtime bootstrap stage.
 */
data class BootstrapStageOutput(
    val context: BootstrapStageInput,
    val result: BootstrapResult,
    val terminalFailure: Boolean = result.isTerminalFailure
) {
    val isTerminalFailure: Boolean
        get() = terminalFailure
}
