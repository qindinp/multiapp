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
     * Skeleton implementation: may attempt `Class.forName(activityClassName).newInstance()`
     * or simply record the launch for later interception.
     *
     * @param hostActivity the host Activity used as a base for starting the guest
     * @param activityClassName fully qualified class name of the guest Activity
     * @param classLoader ClassLoader that can load the guest Activity class
     * @param config guest context configuration
     * @return launch result with success flag and optional error message
     */
    fun launchGuestActivity(
        hostActivity: android.app.Activity,
        activityClassName: String,
        classLoader: ClassLoader,
        config: VirtualContextConfig
    ): GuestActivityLaunchResult
}
