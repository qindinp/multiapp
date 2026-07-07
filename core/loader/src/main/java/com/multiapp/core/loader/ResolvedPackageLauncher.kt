package com.multiapp.core.loader

import android.content.Intent
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedPackage

internal fun ResolvedPackage.resolveLauncherActivityNameStrict(): String? =
    launcherActivityName?.takeIf { it.isNotBlank() }
        ?: activities.resolveLauncherIntentActivityName()

internal fun ResolvedComponent.resolvedActivityClassName(): String =
    aliasTargetActivityClassName() ?: name

internal fun List<ResolvedComponent>.resolveLauncherIntentActivityName(): String? =
    firstOrNull { it.hasLauncherIntentFilter() }?.name

internal fun List<ResolvedComponent>.isLauncherIntentActivity(className: String?): Boolean {
    val normalizedClassName = className?.takeIf { it.isNotBlank() } ?: return false
    return firstOrNull { it.name == normalizedClassName }
        ?.hasLauncherIntentFilter()
        ?: false
}

internal fun List<ResolvedComponent>.aliasTargetActivityClassNameFor(className: String?): String? {
    val normalizedClassName = className?.takeIf { it.isNotBlank() } ?: return null
    return firstOrNull { it.name == normalizedClassName }
        ?.aliasTargetActivityClassName()
}

internal fun ResolvedComponent.aliasTargetActivityClassName(): String? =
    targetActivityName?.trim()?.takeIf { it.isNotEmpty() && it != name }

internal fun ResolvedComponent.hasLauncherIntentFilter(): Boolean {
    val legacyMatch = intentFilters.contains(Intent.ACTION_MAIN) &&
        intentFilters.contains(Intent.CATEGORY_LAUNCHER)
    if (legacyMatch) return true

    return resolvedIntentFilters.any { filter ->
        Intent.ACTION_MAIN in filter.actions &&
            Intent.CATEGORY_LAUNCHER in filter.categories
    }
}
