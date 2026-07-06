package com.multiapp.core.loader

import kotlin.test.Test
import kotlin.test.assertTrue

class HostedActivityContextEvidenceFormatterTest {

    @Test
    fun `format emits PR5 LoadedApk and ActivityThread gate fields`() {
        val injection = HostedActivityContextInjector.InjectionResult(
            contextInjected = true,
            applicationInjected = true,
            dataDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
            packageName = "com.multiapp.instance.abc",
            applicationClassName = "com.test.minimal.MinimalApp",
            originPackageName = "com.test.minimal",
            virtualPackageName = "com.multiapp.instance.abc",
            activityInfoPackageName = "com.multiapp.instance.abc",
            applicationInfoPackageName = "com.multiapp.instance.abc",
            loadedApkTargetClassName = "android.app.LoadedApk",
            loadedApkPatchedFields = listOf(
                "mApplicationInfo",
                "mResources",
                "mClassLoader",
                "mPackageName",
                "mDataDir"
            ),
            loadedApkSkippedFieldReasons = listOf("mDeviceProtectedDataDirFile:FIELD_NOT_FOUND"),
            loadedApkInstalledAliasCount = 4,
            loadedApkInstalledAliasesByField = linkedMapOf(
                "mPackages" to listOf("com.test.minimal", "com.multiapp.instance.abc"),
                "mResourcePackages" to listOf("com.test.minimal", "com.multiapp.instance.abc")
            ),
            loadedApkAliasSkippedReasonsByField = emptyMap(),
            loadedApkSkippedReason = null,
            loadedApkSource = "GUEST_SANDBOX",
            activityRecordPatchedFields = listOf("activityInfo", "intent", "packageInfo"),
            activityRecordSkippedReason = null,
            appCompatThemeGuardApplied = false,
            appCompatThemeResourceId = 0,
            themeVerdict = "PASS",
            themeAppliedSource = "HOST_PROXY_APPCOMPAT_BASELINE",
            appCompatAttrsVerdict = "PASS",
            hostAppCompatBridgeApplied = true,
            hostAppCompatFallbackApplied = false,
            appCompatAttrsProbe = "androidx.appcompat.R\$styleable:windowActionBar=0x7f030001:hasValue=true",
            themeRuntimeOwner = "GUEST_RUNTIME",
            activityThemeProbe = "activity-probe-pass",
            contextThemeProbe = "context-probe-pass",
            themeFieldPatched = true,
            baseContextInjectedBeforeTheme = true,
            hiddenApiBypassApplied = true,
            injectionPhase = "preOnPostCreate"
        )

        val text = HostedActivityContextEvidenceFormatter.format(
            guestActivityClassName = "com.test.minimal.MainActivity",
            injection = injection
        )

        assertTrue("status=GUEST_ACTIVITY_CONTEXT_INJECTED" in text)
        assertTrue("stage=ACTIVITY_CONTEXT" in text)
        assertTrue("injectionPhase=preOnPostCreate" in text)
        assertTrue("guestActivityClassName=com.test.minimal.MainActivity" in text)
        assertTrue("packageName=com.multiapp.instance.abc" in text)
        assertTrue("originPackageName=com.test.minimal" in text)
        assertTrue("virtualPackageName=com.multiapp.instance.abc" in text)
        assertTrue("activityInfo.packageName=com.multiapp.instance.abc" in text)
        assertTrue("applicationInfo.packageName=com.multiapp.instance.abc" in text)
        assertTrue("loadedApkTargetClassName=android.app.LoadedApk" in text)
        assertTrue("loadedApkSource=GUEST_SANDBOX" in text)
        assertTrue("loadedApkEvidenceVerdict=PASS" in text)
        assertTrue("loadedApkInstalledAliasCount=4" in text)
        assertTrue(
            "loadedApkInstalledAliasesByField=mPackages:com.test.minimal,com.multiapp.instance.abc;" +
                "mResourcePackages:com.test.minimal,com.multiapp.instance.abc" in text
        )
        assertTrue("loadedApkSkippedFieldReasons=mDeviceProtectedDataDirFile:FIELD_NOT_FOUND" in text)
        assertTrue("activityRecordPatchedFields=activityInfo,intent,packageInfo" in text)
        assertTrue("themeVerdict=PASS" in text)
        assertTrue("themeAppliedSource=HOST_PROXY_APPCOMPAT_BASELINE" in text)
        assertTrue("appCompatAttrsVerdict=PASS" in text)
        assertTrue("hostAppCompatBridgeApplied=true" in text)
        assertTrue("hostAppCompatFallbackApplied=false" in text)
        assertTrue("appCompatAttrsProbe=androidx.appcompat.R\$styleable:windowActionBar=0x7f030001:hasValue=true" in text)
        assertTrue("themeRuntimeOwner=GUEST_RUNTIME" in text)
        assertTrue("activityThemeProbe=activity-probe-pass" in text)
        assertTrue("contextThemeProbe=context-probe-pass" in text)
        assertTrue("themeFieldPatched=true" in text)
        assertTrue("baseContextInjectedBeforeTheme=true" in text)
        assertTrue("hiddenApiBypassApplied=true" in text)
        assertTrue("dataDir=/data/user/0/com.multiapp.app/files/instance_data/inst-001" in text)
    }

    @Test
    fun `format marks LoadedApk gate partial when required ActivityThread evidence is missing`() {
        val injection = HostedActivityContextInjector.InjectionResult(
            contextInjected = true,
            applicationInjected = false,
            dataDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
            packageName = "com.multiapp.instance.abc",
            applicationClassName = null,
            originPackageName = "com.test.minimal",
            virtualPackageName = "com.multiapp.instance.abc",
            activityInfoPackageName = "com.multiapp.instance.abc",
            applicationInfoPackageName = "com.multiapp.instance.abc",
            loadedApkSource = "GUEST_SANDBOX",
            loadedApkInstalledAliasCount = 2,
            loadedApkPatchedFields = listOf("mApplicationInfo"),
            activityRecordPatchedFields = listOf("activityInfo")
        )

        val text = HostedActivityContextEvidenceFormatter.format(
            guestActivityClassName = "com.test.minimal.MainActivity",
            injection = injection
        )

        assertTrue("loadedApkEvidenceVerdict=PARTIAL" in text)
    }
}
