package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Intent
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.virtual.resolveActivityRuntimeComponent

data class VirtualActivityLaunchRequest(
    val instanceId: String,
    val originPackageName: String,
    val guestActivityClassName: String,
    val sourceIntent: Intent,
    val reason: String,
    val launchMode: String? = null,
    val taskAffinity: String? = null,
    val resultToToken: String? = null,
    val resultRequestCode: Int = -1
)

/** Resolves guest Activity intents against a virtual package snapshot. */
class VirtualIntentResolver(
    private val snapshot: VirtualPackageSnapshot
) {
    fun resolveActivity(intent: Intent): VirtualActivityLaunchRequest? {
        val explicit = intent.component?.let { component -> resolveExplicit(component) }
        if (explicit != null) return explicit.copy(sourceIntent = intent)

        val packageName = intent.safePackageName()
        if (packageName != null && !snapshot.matchesPackageName(packageName)) return null

        val matched = snapshot.activities.firstOrNull { component ->
            VirtualIntentFilterMatcher.matches(intent, component)
        }
        if (matched != null) {
            val runtimeComponent = snapshot.resolveActivityRuntimeComponent(matched) ?: return null
            return request(
                guestActivityClassName = runtimeComponent.effectiveActivityClassName(),
                sourceIntent = intent,
                reason = if (isLauncherIntent(intent)) "launcher" else "implicit",
                launchMode = runtimeComponent.launchMode,
                taskAffinity = taskAffinityFor(runtimeComponent)
            )
        }

        if (isLauncherIntent(intent)) {
            val launcherComponent = snapshot.launcherActivityName
                ?.let(::findActivityByNameOrTarget)
                ?: snapshot.activities.firstOrNull { it.hasLauncherIntentFilter() }
            if (launcherComponent != null) {
                val runtimeComponent = snapshot.resolveActivityRuntimeComponent(launcherComponent) ?: return null
                return request(
                    guestActivityClassName = runtimeComponent.effectiveActivityClassName(),
                    sourceIntent = intent,
                    reason = "launcher",
                    launchMode = runtimeComponent.launchMode,
                    taskAffinity = taskAffinityFor(runtimeComponent)
                )
            }
            val launcher = snapshot.launcherActivityName
                ?: return null
            return request(launcher, intent, "launcher", taskAffinity = taskAffinityFor(null))
        }

        return null
    }

    internal fun resolveExplicitActivity(
        packageName: String,
        className: String,
        sourceIntent: Intent
    ): VirtualActivityLaunchRequest? {
        if (!snapshot.matchesPackageName(packageName)) return null
        val normalizedClassName = normalizeActivityClassName(className)
        val component = snapshot.activities.firstOrNull {
            it.name == normalizedClassName || it.targetActivityName == normalizedClassName
        }
        if (component == null && snapshot.launcherActivityName != normalizedClassName) {
            return null
        }
        val runtimeComponent = component?.let(snapshot::resolveActivityRuntimeComponent)
        if (component != null && runtimeComponent == null) return null
        return request(
            guestActivityClassName = runtimeComponent?.effectiveActivityClassName() ?: normalizedClassName,
            sourceIntent = sourceIntent,
            reason = "explicit",
            launchMode = runtimeComponent?.launchMode,
            taskAffinity = taskAffinityFor(runtimeComponent)
        )
    }

    private fun resolveExplicit(component: ComponentName): VirtualActivityLaunchRequest? {
        return resolveExplicitActivity(component.packageName, component.className, Intent())
    }

    private fun normalizeActivityClassName(className: String): String {
        return when {
            className.startsWith(".") -> snapshot.originPackageName + className
            '.' !in className -> "${snapshot.originPackageName}.$className"
            else -> className
        }
    }

    private fun request(
        guestActivityClassName: String,
        sourceIntent: Intent,
        reason: String,
        launchMode: String? = snapshot.activities.firstOrNull { it.name == guestActivityClassName }?.launchMode,
        taskAffinity: String? = taskAffinityFor(findActivityByNameOrTarget(guestActivityClassName))
    ): VirtualActivityLaunchRequest = VirtualActivityLaunchRequest(
        instanceId = snapshot.instanceId,
        originPackageName = snapshot.originPackageName,
        guestActivityClassName = guestActivityClassName,
        sourceIntent = sourceIntent,
        reason = reason,
        launchMode = launchMode,
        taskAffinity = taskAffinity
    )

    private fun isLauncherIntent(intent: Intent): Boolean =
        intent.action == Intent.ACTION_MAIN && intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true

    private fun findActivityByNameOrTarget(className: String): ResolvedComponent? =
        snapshot.activities.firstOrNull { it.name == className || it.targetActivityName == className }

    private fun taskAffinityFor(component: ResolvedComponent?): String {
        val guestAffinity = component?.taskAffinity?.takeIf { it.isNotBlank() }
            ?: snapshot.taskAffinity?.takeIf { it.isNotBlank() }
            ?: snapshot.originPackageName
        return "$guestAffinity:${snapshot.instanceId}"
    }
}

private fun com.multiapp.core.model.virtual.ResolvedComponent.effectiveActivityClassName(): String =
    targetActivityName ?: name
