package com.multiapp.core.model.virtual

/**
 * Result of attempting to launch a guest Activity.
 */
data class GuestActivityLaunchResult(
    /** Whether the launch succeeded */
    val success: Boolean,
    /** The activity class name that was (or would have been) launched */
    val activityClassName: String?,
    /** Error description if [success] is false */
    val errorMessage: String? = null
)

/**
 * Pure model request for planning a guest Activity launch.
 *
 * Android-facing modules turn this plan into real ActivityThread /
 * Instrumentation operations. Keeping this DTO Android-free is important
 * because :core:model is the shared contract layer.
 */
data class GuestActivityLaunchRequest(
    val activityClassName: String,
    val classLoader: ClassLoader,
    val config: VirtualContextConfig,
    val launchFlags: Int = 0,
    val taskAffinity: String? = null
)

/**
 * Controls guest Activity lifecycle within the container.
 *
 * This skeleton records launch intent and resolves the launcher activity
 * from a [ResolvedPackage]. A full implementation would intercept AMS calls
 * and manage the guest Activity stack, but this MVP focuses on resolution
 * and basic launch tracking.
 */
interface VirtualActivityController {
    /**
     * Find the launcher activity class name from a resolved package.
     *
     * @param resolvedPackage the package to inspect
     * @return launcher activity class name, or null if none declared
     */
    fun resolveLauncherActivity(resolvedPackage: ResolvedPackage): String?

    /**
     * Launch (or record the intent to launch) a guest activity.
     *
     * Model-layer implementation should only validate or describe the launch.
     * Real Android Activity objects are created by the engine/loader adapter.
     *
     * @param request Android-free launch request
     * @return launch result with success flag and optional error message
     */
    fun planGuestActivityLaunch(request: GuestActivityLaunchRequest): GuestActivityLaunchResult
}
