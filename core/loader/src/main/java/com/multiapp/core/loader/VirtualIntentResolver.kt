package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Intent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

data class VirtualActivityLaunchRequest(
    val instanceId: String,
    val originPackageName: String,
    val guestActivityClassName: String,
    val sourceIntent: Intent,
    val reason: String,
    val launchMode: String? = null
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
            return request(matched.name, intent, if (isLauncherIntent(intent)) "launcher" else "implicit", matched.launchMode)
        }

        if (isLauncherIntent(intent)) {
            val launcher = snapshot.launcherActivityName ?: return null
            return request(launcher, intent, "launcher")
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
        val component = snapshot.activities.firstOrNull { it.name == normalizedClassName }
        if (component == null && snapshot.launcherActivityName != normalizedClassName) {
            return null
        }
        return request(normalizedClassName, sourceIntent, "explicit", component?.launchMode)
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
        launchMode: String? = snapshot.activities.firstOrNull { it.name == guestActivityClassName }?.launchMode
    ): VirtualActivityLaunchRequest = VirtualActivityLaunchRequest(
        instanceId = snapshot.instanceId,
        originPackageName = snapshot.originPackageName,
        guestActivityClassName = guestActivityClassName,
        sourceIntent = sourceIntent,
        reason = reason,
        launchMode = launchMode
    )

    private fun isLauncherIntent(intent: Intent): Boolean =
        intent.action == Intent.ACTION_MAIN && intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true
}
