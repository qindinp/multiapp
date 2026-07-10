package com.multiapp.core.model.virtual

/**
 * Diagnostic implementation of [VirtualActivityController] for single-Activity
 * hosted container evidence collection.
 *
 * Resolves the launcher activity from a [ResolvedPackage] by checking:
 * 1. [ResolvedPackage.launcherActivityName] (declared in manifest metadata)
 * 2. First activity with MAIN+LAUNCHER intent filters
 * 3. First activity in the list as fallback
 *
 * This class intentionally produces an Android-free launch plan. Real Android
 * Activity creation belongs in the engine/loader adapter where LoadedApk,
 * ActivityThread records, tokens, windows, resources, and proxy components are
 * available.
 */
class DefaultVirtualActivityController : VirtualActivityController {

    companion object {
        private const val ACTION_MAIN = "android.intent.action.MAIN"
        private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    }

    override fun resolveLauncherActivity(resolvedPackage: ResolvedPackage): String? {
        // Priority 1: Explicit launcherActivityName from manifest metadata
        resolvedPackage.launcherActivityName?.let { return it }

        // Priority 2: Activity with MAIN+LAUNCHER intent filters
        val launcherByFilter = resolvedPackage.activities.firstOrNull { component ->
            component.intentFilters.contains(ACTION_MAIN) &&
                component.intentFilters.contains(CATEGORY_LAUNCHER)
        }
        launcherByFilter?.let { return it.effectiveActivityClassName() }

        // Priority 3: First declared activity as fallback
        return resolvedPackage.activities.firstOrNull()?.effectiveActivityClassName()
    }

    override fun planGuestActivityLaunch(request: GuestActivityLaunchRequest): GuestActivityLaunchResult {
        return try {
            request.classLoader.loadClass(request.activityClassName)
            GuestActivityLaunchResult(
                success = true,
                activityClassName = request.activityClassName
            )
        } catch (error: Throwable) {
            GuestActivityLaunchResult(
                success = false,
                activityClassName = request.activityClassName,
                errorMessage = error.message ?: error.javaClass.name
            )
        }
    }
}

private fun ResolvedComponent.effectiveActivityClassName(): String =
    targetActivityName ?: name
