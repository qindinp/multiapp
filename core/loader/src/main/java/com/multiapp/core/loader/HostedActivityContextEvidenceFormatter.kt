package com.multiapp.core.loader

internal object HostedActivityContextEvidenceFormatter {

    fun format(
        guestActivityClassName: String,
        injection: HostedActivityContextInjector.InjectionResult
    ): String = listOf(
        "status=GUEST_ACTIVITY_CONTEXT_INJECTED",
        "stage=ACTIVITY_CONTEXT",
        "guestActivityClassName=$guestActivityClassName",
        "contextInjected=${injection.contextInjected}",
        "applicationInjected=${injection.applicationInjected}",
        "packageName=${injection.packageName}",
        "originPackageName=${injection.originPackageName}",
        "virtualPackageName=${injection.virtualPackageName}",
        "activityInfo.packageName=${injection.activityInfoPackageName.orEmpty()}",
        "applicationInfo.packageName=${injection.applicationInfoPackageName.orEmpty()}",
        "applicationClassName=${injection.applicationClassName.orEmpty()}",
        "loadedApkTargetClassName=${injection.loadedApkTargetClassName.orEmpty()}",
        "loadedApkPatchedFields=${injection.loadedApkPatchedFields.joinToString(",")}",
        "loadedApkSkippedFieldReasons=${injection.loadedApkSkippedFieldReasons.joinToString(",")}",
        "loadedApkInstalledAliasCount=${injection.loadedApkInstalledAliasCount}",
        "loadedApkInstalledAliasesByField=${formatStringListMap(injection.loadedApkInstalledAliasesByField)}",
        "loadedApkAliasSkippedReasonsByField=${formatStringMap(injection.loadedApkAliasSkippedReasonsByField)}",
        "loadedApkSkippedReason=${injection.loadedApkSkippedReason.orEmpty()}",
        "loadedApkSource=${injection.loadedApkSource.orEmpty()}",
        "activityRecordPatchedFields=${injection.activityRecordPatchedFields.joinToString(",")}",
        "activityRecordSkippedReason=${injection.activityRecordSkippedReason.orEmpty()}",
        "appCompatThemeGuardApplied=${injection.appCompatThemeGuardApplied}",
        "appCompatThemeResourceId=${injection.appCompatThemeResourceId}",
        "dataDir=${injection.dataDir}"
    ).joinToString("\n")

    private fun formatStringListMap(values: Map<String, List<String>>): String =
        values.toSortedMap().entries.joinToString(";") { (field, entries) ->
            "$field:${entries.joinToString(",")}"
        }

    private fun formatStringMap(values: Map<String, String>): String =
        values.toSortedMap().entries.joinToString(";") { (field, reason) ->
            "$field:$reason"
        }
}
