package com.multiapp.core.loader

import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.ComponentInfo
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedPackage
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.virtual.VirtualPackageResolver

class LauncherActivityStage(
    private val packageResolver: VirtualPackageResolver?,
    private val launcherActivityResolver: (InstallRecord) -> String?,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val installRecord = input.installRecord ?: return failed(
            input = input,
            startMs = startMs,
            message = "Install record is required before launcher Activity resolution"
        )
        val originApkPath = input.originApkPath ?: return failed(
            input = input,
            startMs = startMs,
            message = "Origin APK path is required before launcher Activity resolution"
        )
        val guestClassLoader = input.guestClassLoader ?: return failed(
            input = input,
            startMs = startMs,
            message = "Guest ClassLoader is required before launcher Activity resolution"
        )

        val launcherCandidates = resolveLauncherActivityCandidates(installRecord, originApkPath, input)
        if (launcherCandidates.resolutions.isEmpty()) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.skipped(
                    stage = RuntimeStage.LAUNCHER_ACTIVITY,
                    message = "No launcher Activity resolved from manifest or InstallRecord",
                    evidence = noLauncherEvidence(input, installRecord, launcherCandidates)
                ).copy(durationMs = clock() - startMs),
                terminalFailure = false
            )
        }

        val loadableLauncher = resolveLoadableLauncher(
            launcherCandidates = launcherCandidates,
            input = input,
            guestClassLoader = guestClassLoader
        )

        if (!loadableLauncher.loadable) {
            return BootstrapStageOutput(
                context = input.copy(launcherActivityClassName = null),
                result = BootstrapResult.failed(
                    stage = RuntimeStage.LAUNCHER_ACTIVITY,
                    message = "Launcher Activity preflight not loadable; no loadable fallback for ${loadableLauncher.className}",
                    evidence = launcherEvidence(loadableLauncher, launcherCandidates),
                    durationMs = clock() - startMs
                ),
                terminalFailure = false
            )
        }

        return BootstrapStageOutput(
            context = input.copy(launcherActivityClassName = loadableLauncher.className),
            result = BootstrapResult.success(
                stage = RuntimeStage.LAUNCHER_ACTIVITY,
                message = "Launcher Activity resolved: ${loadableLauncher.className}",
                evidence = launcherEvidence(loadableLauncher, launcherCandidates),
                durationMs = clock() - startMs
            ),
            terminalFailure = false
        )
    }

    private fun resolveLauncherActivityCandidates(
        installRecord: InstallRecord,
        originApkPath: String,
        input: BootstrapStageInput
    ): LauncherCandidates {
        val packageResolverResult = runCatching {
            packageResolver?.resolve(originApkPath)
        }.getOrNull()
        val resolvedPackageLauncherActivityName = input.resolvedPackage
            ?.resolveLauncherActivityNameStrict()
            ?: packageResolverResult?.resolveLauncherActivityNameStrict()
        val packageSnapshotLauncherActivityName = input.packageSnapshot
            ?.launcherActivityName
            ?.takeIf { it.isNotBlank() }
            ?: input.packageSnapshot?.activities?.resolveLauncherIntentActivityName()
        val hasHighFidelityPackageMetadata = input.resolvedPackage != null ||
            packageResolverResult != null ||
            input.packageSnapshot != null

        val resolutions = buildList {
            input.resolvedPackage?.let { resolvedPackage ->
                addResolvedPackageCandidates(resolvedPackage)
            }

            packageResolverResult?.let { resolvedPackage ->
                addResolvedPackageCandidates(resolvedPackage)
            }

            input.packageSnapshot?.let { snapshot ->
                addPackageSnapshotCandidates(snapshot)
            }

            if (!hasHighFidelityPackageMetadata) {
                val resolvedFromInstallRecord = runCatching {
                    launcherActivityResolver(installRecord)
                }.getOrNull()
                addCandidate(resolvedFromInstallRecord, INSTALL_RECORD)

                addInstallRecordCandidates(installRecord.activities)
            }
        }.preferAliasTargets()

        return LauncherCandidates(
            resolutions = resolutions,
            resolvedPackageLauncherActivityName = resolvedPackageLauncherActivityName,
            packageSnapshotLauncherActivityName = packageSnapshotLauncherActivityName,
            candidateLauncherActivities = resolutions.toCandidateEvidence()
        )
    }

    private fun List<LauncherResolution>.preferAliasTargets(): List<LauncherResolution> {
        val merged = LinkedHashMap<String, LauncherResolution>()
        forEach { resolution ->
            val existing = merged[resolution.className]
            if (existing == null || existing.aliasTargetClassName == null && resolution.aliasTargetClassName != null) {
                merged[resolution.className] = resolution
            }
        }
        return merged.values.toList()
    }

    private fun MutableList<LauncherResolution>.addResolvedPackageCandidates(resolvedPackage: ResolvedPackage) {
        val declaredLauncherActivityName = resolvedPackage.launcherActivityName?.takeIf { it.isNotBlank() }
        addCandidate(
            declaredLauncherActivityName,
            VIRTUAL_PACKAGE_RESOLVER,
            resolvedPackage.activities.aliasTargetActivityClassNameFor(declaredLauncherActivityName)
        )
        addLauncherClassNameHeuristicCandidates(
            requestedClassName = declaredLauncherActivityName,
            source = VIRTUAL_PACKAGE_RESOLVER_CLASS_NAME_FALLBACK,
            components = resolvedPackage.activities
        )
        if (declaredLauncherActivityName == null) {
            val fallbackActivityName = resolvedPackage.activities.resolveLauncherIntentActivityName()
            addCandidate(
                fallbackActivityName,
                VIRTUAL_PACKAGE_RESOLVER_FALLBACK,
                resolvedPackage.activities.aliasTargetActivityClassNameFor(fallbackActivityName)
            )
            if (resolvedPackage.activities.isLauncherIntentActivity(fallbackActivityName)) {
                addLauncherClassNameHeuristicCandidates(
                    requestedClassName = fallbackActivityName,
                    source = VIRTUAL_PACKAGE_RESOLVER_CLASS_NAME_FALLBACK,
                    components = resolvedPackage.activities
                )
            }
        }
        resolvedPackage.activities.filter { it.hasLauncherIntentFilter() }.forEach { component ->
            addCandidate(
                component.name,
                VIRTUAL_PACKAGE_RESOLVER_FALLBACK,
                component.aliasTargetActivityClassName()
            )
            addLauncherClassNameHeuristicCandidates(
                requestedClassName = component.name,
                source = VIRTUAL_PACKAGE_RESOLVER_CLASS_NAME_FALLBACK,
                components = resolvedPackage.activities
            )
        }
    }

    private fun MutableList<LauncherResolution>.addPackageSnapshotCandidates(snapshot: VirtualPackageSnapshot) {
        val declaredLauncherActivityName = snapshot.launcherActivityName?.takeIf { it.isNotBlank() }
        addCandidate(
            declaredLauncherActivityName,
            PACKAGE_SNAPSHOT,
            snapshot.activities.aliasTargetActivityClassNameFor(declaredLauncherActivityName)
        )
        addLauncherClassNameHeuristicCandidates(
            requestedClassName = declaredLauncherActivityName,
            source = PACKAGE_SNAPSHOT_CLASS_NAME_FALLBACK,
            components = snapshot.activities
        )
        if (declaredLauncherActivityName == null) {
            val fallbackActivityName = snapshot.activities.resolveLauncherIntentActivityName()
            addCandidate(
                fallbackActivityName,
                PACKAGE_SNAPSHOT_FALLBACK,
                snapshot.activities.aliasTargetActivityClassNameFor(fallbackActivityName)
            )
            if (snapshot.activities.isLauncherIntentActivity(fallbackActivityName)) {
                addLauncherClassNameHeuristicCandidates(
                    requestedClassName = fallbackActivityName,
                    source = PACKAGE_SNAPSHOT_CLASS_NAME_FALLBACK,
                    components = snapshot.activities
                )
            }
        }
        snapshot.activities.filter { it.hasLauncherIntentFilter() }.forEach { component ->
            addCandidate(
                component.name,
                PACKAGE_SNAPSHOT_FALLBACK,
                component.aliasTargetActivityClassName()
            )
            addLauncherClassNameHeuristicCandidates(
                requestedClassName = component.name,
                source = PACKAGE_SNAPSHOT_CLASS_NAME_FALLBACK,
                components = snapshot.activities
            )
        }
    }

    private fun MutableList<LauncherResolution>.addInstallRecordCandidates(
        activities: List<ComponentInfo>
    ) {
        val exported = activities.filter { it.exported }
        val nonExported = activities.filterNot { it.exported }
        (exported + nonExported).forEach { component ->
            addCandidate(component.name, INSTALL_RECORD_FALLBACK)
        }
    }

    private fun MutableList<LauncherResolution>.addCandidate(
        className: String?,
        source: String,
        aliasTargetClassName: String? = null,
        requestedClassName: String? = null
    ) {
        className?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            add(
                LauncherResolution(
                    className = name,
                    source = source,
                    requestedClassName = requestedClassName
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: name,
                    aliasTargetClassName = aliasTargetClassName?.trim()?.takeIf {
                        it.isNotEmpty() && it != name
                    }
                )
            )
        }
    }

    private fun MutableList<LauncherResolution>.addClassNameHeuristicCandidates(
        requestedClassName: String?,
        source: String
    ) {
        val requested = requestedClassName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        requested.heuristicSiblingActivityClassNames().forEach { candidate ->
            addCandidate(
                className = candidate,
                source = source,
                requestedClassName = requested
            )
        }
    }

    private fun MutableList<LauncherResolution>.addLauncherClassNameHeuristicCandidates(
        requestedClassName: String?,
        source: String,
        components: List<ResolvedComponent>
    ) {
        val requested = requestedClassName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (!components.isLauncherIntentActivity(requested)) return
        if (components.aliasTargetActivityClassNameFor(requested) != null) return
        addClassNameHeuristicCandidates(
            requestedClassName = requested,
            source = source
        )
    }

    private fun failed(
        input: BootstrapStageInput,
        startMs: Long,
        message: String
    ): BootstrapStageOutput = BootstrapStageOutput(
        context = input,
        result = BootstrapResult.failed(
            stage = RuntimeStage.LAUNCHER_ACTIVITY,
            message = message,
            durationMs = clock() - startMs
        ),
        terminalFailure = false
    )

    private fun resolveLoadableLauncher(
        launcherCandidates: LauncherCandidates,
        input: BootstrapStageInput,
        guestClassLoader: ClassLoader
    ): LoadableLauncher {
        val launcherResolutions = launcherCandidates.resolutions
        val attempted = mutableListOf<String>()
        val firstResolution = launcherResolutions.first()
        var firstAliasTargetClassName: String? = null

        for (launcherResolution in launcherResolutions) {
            val launcherClassName = launcherResolution.className
            attempted += "${launcherResolution.source}:$launcherClassName"
            val targetClassName = launcherResolution.aliasTargetClassName
                ?: input.aliasTargetFor(launcherClassName)
            if (firstAliasTargetClassName == null) {
                firstAliasTargetClassName = targetClassName
            }

            if (targetClassName != null) {
                attempted += "${launcherResolution.source}:$targetClassName"
                if (guestClassLoader.canLoad(targetClassName)) {
                    return LoadableLauncher(
                        className = targetClassName,
                        requestedClassName = launcherResolution.requestedClassName,
                        source = launcherResolution.source,
                        loadable = true,
                        aliasTargetClassName = targetClassName,
                        candidateCount = launcherResolutions.size,
                        attemptedClassNames = attempted
                    )
                }
            }

            if (guestClassLoader.canLoad(launcherClassName)) {
                return LoadableLauncher(
                    className = launcherClassName,
                    requestedClassName = launcherResolution.requestedClassName,
                    source = launcherResolution.source,
                    loadable = true,
                    candidateCount = launcherResolutions.size,
                    attemptedClassNames = attempted
                )
            }
        }

        val fallbackClassName = firstAliasTargetClassName ?: firstResolution.className
        return LoadableLauncher(
            className = fallbackClassName,
            requestedClassName = firstResolution.requestedClassName,
            source = firstResolution.source,
            loadable = false,
            aliasTargetClassName = firstAliasTargetClassName,
            candidateCount = launcherResolutions.size,
            attemptedClassNames = attempted
        )
    }

    private fun ClassLoader.canLoad(className: String): Boolean =
        runCatching { loadClass(className) }.isSuccess

    private fun BootstrapStageInput.aliasTargetFor(className: String): String? =
        (resolvedPackage?.activities.orEmpty() + packageSnapshot?.activities.orEmpty())
            .aliasTargetActivityClassNameFor(className)

    private fun launcherEvidence(
        resolution: LoadableLauncher,
        launcherCandidates: LauncherCandidates
    ): List<BootstrapEvidence> = buildList {
        add(BootstrapEvidence("launcherActivityClass", resolution.className))
        add(BootstrapEvidence("requestedLauncherActivityClass", resolution.requestedClassName))
        add(BootstrapEvidence("aliasTargetActivityClass", resolution.aliasTargetClassName.orEmpty()))
        add(
            BootstrapEvidence(
                "resolvedPackageLauncherActivityName",
                launcherCandidates.resolvedPackageLauncherActivityName.orEmpty()
            )
        )
        add(
            BootstrapEvidence(
                "packageSnapshotLauncherActivityName",
                launcherCandidates.packageSnapshotLauncherActivityName.orEmpty()
            )
        )
        add(BootstrapEvidence("resolver", resolution.source))
        add(BootstrapEvidence("loadable", resolution.loadable.toString()))
        add(BootstrapEvidence("preflightBypassed", (!resolution.loadable).toString()))
        add(BootstrapEvidence("candidateCount", resolution.candidateCount.toString()))
        add(
            BootstrapEvidence(
                "candidateLauncherActivities",
                launcherCandidates.candidateLauncherActivities.take(MAX_EVIDENCE_CANDIDATES).joinToString(",")
            )
        )
        add(
            BootstrapEvidence(
                "attemptedLauncherActivities",
                resolution.attemptedClassNames.take(MAX_EVIDENCE_CANDIDATES).joinToString(",")
            )
        )
    }

    private fun noLauncherEvidence(
        input: BootstrapStageInput,
        installRecord: InstallRecord,
        launcherCandidates: LauncherCandidates
    ): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("resolver", NONE),
        BootstrapEvidence("resolvedPackageLauncherActivityName", launcherCandidates.resolvedPackageLauncherActivityName.orEmpty()),
        BootstrapEvidence("packageSnapshotLauncherActivityName", launcherCandidates.packageSnapshotLauncherActivityName.orEmpty()),
        BootstrapEvidence("aliasTargetActivityClass", ""),
        BootstrapEvidence("candidateLauncherActivities", launcherCandidates.candidateLauncherActivities.joinToString(",")),
        BootstrapEvidence("resolvedPackageActivityCount", (input.resolvedPackage?.activities?.size ?: 0).toString()),
        BootstrapEvidence("packageSnapshotActivityCount", (input.packageSnapshot?.activities?.size ?: 0).toString()),
        BootstrapEvidence("installRecordActivityCount", installRecord.activities.size.toString())
    )

    private fun List<LauncherResolution>.toCandidateEvidence(): List<String> =
        flatMap { resolution ->
            listOfNotNull(
                "${resolution.source}:${resolution.className}",
                resolution.aliasTargetClassName?.let { "${resolution.source}:$it" }
            )
        }.distinct()

    private data class LauncherCandidates(
        val resolutions: List<LauncherResolution>,
        val resolvedPackageLauncherActivityName: String?,
        val packageSnapshotLauncherActivityName: String?,
        val candidateLauncherActivities: List<String>
    )

    private data class LauncherResolution(
        val className: String,
        val source: String,
        val requestedClassName: String = className,
        val aliasTargetClassName: String? = null
    )

    private data class LoadableLauncher(
        val className: String,
        val requestedClassName: String,
        val source: String,
        val loadable: Boolean,
        val aliasTargetClassName: String? = null,
        val candidateCount: Int,
        val attemptedClassNames: List<String>
    )

    private companion object {
        private const val VIRTUAL_PACKAGE_RESOLVER = "VirtualPackageResolver"
        private const val VIRTUAL_PACKAGE_RESOLVER_FALLBACK = "VirtualPackageResolverFallback"
        private const val VIRTUAL_PACKAGE_RESOLVER_CLASS_NAME_FALLBACK = "VirtualPackageResolverClassNameFallback"
        private const val PACKAGE_SNAPSHOT = "PackageSnapshot"
        private const val PACKAGE_SNAPSHOT_FALLBACK = "PackageSnapshotFallback"
        private const val PACKAGE_SNAPSHOT_CLASS_NAME_FALLBACK = "PackageSnapshotClassNameFallback"
        private const val INSTALL_RECORD = "InstallRecord"
        private const val INSTALL_RECORD_FALLBACK = "InstallRecordFallback"
        private const val NONE = "NONE"
        private const val MAX_EVIDENCE_CANDIDATES = 16
    }
}

private fun String.heuristicSiblingActivityClassNames(): List<String> {
    val normalized = trim()
    val lastDot = normalized.lastIndexOf('.')
    if (lastDot <= 0 || lastDot == normalized.lastIndex) return emptyList()

    val packagePrefix = normalized.substring(0, lastDot + 1)
    val simpleName = normalized.substring(lastDot + 1)
    if (!simpleName.isLauncherLikeActivityName()) return emptyList()

    val candidates = linkedSetOf<String>()
    fun addSimple(candidate: String) {
        val clean = candidate.trim()
        if (clean.isNotEmpty() && clean != simpleName && clean.endsWith("Activity")) {
            candidates += packagePrefix + clean
        }
    }

    val aliasTokens = listOf("DefaultAlias", "Alias", "StubAlias", "Stub", "Proxy")
    aliasTokens.forEach { token ->
        if (simpleName.startsWith(token) && simpleName.length > token.length) {
            addSimple(simpleName.removePrefix(token))
        }
    }

    val removedAliasTokens = aliasTokens.fold(simpleName) { current, token ->
        current.replace(token, "")
    }
    addSimple(removedAliasTokens)

    if (simpleName.contains("Splash", ignoreCase = true)) addSimple("SplashActivity")
    if (simpleName.contains("Launch", ignoreCase = true)) addSimple("LaunchActivity")
    if (simpleName.contains("Launcher", ignoreCase = true)) addSimple("LauncherActivity")
    if (simpleName.contains("Main", ignoreCase = true)) addSimple("MainActivity")

    return candidates.toList()
}

private fun String.isLauncherLikeActivityName(): Boolean {
    return contains("Activity") &&
        listOf("Alias", "Stub", "Proxy", "Splash", "Launch", "Launcher", "Main", "Welcome")
            .any { contains(it, ignoreCase = true) }
}
