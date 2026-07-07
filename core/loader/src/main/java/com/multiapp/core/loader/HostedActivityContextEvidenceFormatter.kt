package com.multiapp.core.loader

internal object HostedActivityContextEvidenceFormatter {

    fun format(
        guestActivityClassName: String,
        injection: HostedActivityContextInjector.InjectionResult,
        taskDescriptionLabel: String = ""
    ): String = listOf(
        "status=GUEST_ACTIVITY_CONTEXT_INJECTED",
        "stage=ACTIVITY_CONTEXT",
        "injectionPhase=${injection.injectionPhase}",
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
        "loadedApkEvidenceVerdict=${loadedApkEvidenceVerdict(injection)}",
        "activityRecordPatchedFields=${injection.activityRecordPatchedFields.joinToString(",")}",
        "activityRecordSkippedReason=${injection.activityRecordSkippedReason.orEmpty()}",
        "appCompatThemeGuardApplied=${injection.appCompatThemeGuardApplied}",
        "appCompatThemeResourceId=${injection.appCompatThemeResourceId}",
        "themeVerdict=${injection.themeVerdict}",
        "themeAppliedSource=${injection.themeAppliedSource}",
        "appCompatAttrsVerdict=${injection.appCompatAttrsVerdict}",
        "themeEvidenceVerdict=${themeEvidenceVerdict(injection)}",
        "hostAppCompatBridgeApplied=${injection.hostAppCompatBridgeApplied}",
        "hostAppCompatFallbackApplied=${injection.hostAppCompatFallbackApplied}",
        "hostProxyThemePreApplied=${injection.hostProxyThemePreApplied}",
        "hostProxyThemePreAppliedResourceId=${injection.hostProxyThemePreAppliedResourceId}",
        "appCompatAttrsProbe=${injection.appCompatAttrsProbe}",
        "themeRuntimeOwner=${injection.themeRuntimeOwner}",
        "activityThemeProbe=${injection.activityThemeProbe}",
        "contextThemeProbe=${injection.contextThemeProbe}",
        "themeFieldPatched=${injection.themeFieldPatched}",
        "baseContextInjectedBeforeTheme=${injection.baseContextInjectedBeforeTheme}",
        "hiddenApiBypassApplied=${injection.hiddenApiBypassApplied}",
        "taskDescriptionLabel=$taskDescriptionLabel",
        "dataDir=${injection.dataDir}"
    ).joinToString("\n")

    private fun loadedApkEvidenceVerdict(injection: HostedActivityContextInjector.InjectionResult): String {
        val requiredActivityRecordFields = setOf("activityInfo", "intent", "packageInfo")
        val activityRecordComplete = injection.activityRecordPatchedFields.containsAll(requiredActivityRecordFields)
        val loadedApkComplete = injection.loadedApkSource == "GUEST_SANDBOX" &&
            injection.loadedApkInstalledAliasCount >= 2 &&
            injection.loadedApkPatchedFields.isNotEmpty() &&
            injection.loadedApkSkippedReason.isNullOrBlank()
        return if (loadedApkComplete && activityRecordComplete) "PASS" else "PARTIAL"
    }

    private fun themeEvidenceVerdict(injection: HostedActivityContextInjector.InjectionResult): String {
        if (injection.themeVerdict == "FAIL" || injection.appCompatAttrsVerdict == "FAIL") return "FAIL"
        val requiredFieldsPresent = injection.themeVerdict.isNotBlank() &&
            injection.themeVerdict != "UNKNOWN" &&
            injection.themeAppliedSource.isNotBlank() &&
            injection.themeAppliedSource != "NONE" &&
            injection.appCompatAttrsVerdict.isNotBlank() &&
            injection.appCompatAttrsVerdict != "UNKNOWN"
        val themeComplete = injection.themeVerdict == "PASS" &&
            injection.appCompatAttrsVerdict == "PASS" &&
            requiredFieldsPresent
        return if (themeComplete) "PASS" else "PARTIAL"
    }

    private fun formatStringListMap(values: Map<String, List<String>>): String =
        values.toSortedMap().entries.joinToString(";") { (field, entries) ->
            "$field:${entries.joinToString(",")}"
        }

    private fun formatStringMap(values: Map<String, String>): String =
        values.toSortedMap().entries.joinToString(";") { (field, reason) ->
            "$field:$reason"
        }
}
