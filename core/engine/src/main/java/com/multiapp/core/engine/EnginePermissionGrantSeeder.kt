package com.multiapp.core.engine

import android.content.pm.PackageManager
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime

data class EnginePermissionSeedResult(
    val verdict: EngineResultStatus,
    val mirroredGrantCount: Int,
    val mirroredDenyCount: Int,
    val preservedDecisionCount: Int,
    val unresolvedCount: Int,
    val message: String
)

fun interface EnginePermissionGrantSeeder {
    fun seed(
        runtime: VirtualInstanceRuntime,
        permissionService: VirtualPermissionService
    ): EnginePermissionSeedResult

    companion object {
        val NO_OP = EnginePermissionGrantSeeder { _, _ ->
            EnginePermissionSeedResult(
                verdict = EngineResultStatus.PASS,
                mirroredGrantCount = 0,
                mirroredDenyCount = 0,
                preservedDecisionCount = 0,
                unresolvedCount = 0,
                message = "permission_seed_not_configured"
            )
        }
    }
}

class SourcePackagePermissionGrantSeeder(
    private val permissionChecker: (permissionName: String, packageName: String) -> Int
) : EnginePermissionGrantSeeder {
    constructor(packageManager: PackageManager) : this(packageManager::checkPermission)

    override fun seed(
        runtime: VirtualInstanceRuntime,
        permissionService: VirtualPermissionService
    ): EnginePermissionSeedResult {
        val existing = permissionService.queryRuntimeState(runtime.instanceId)
            .records
            .associateBy { it.permissionName }
        var granted = 0
        var denied = 0
        var preserved = 0
        var unresolved = 0
        runtime.packageSnapshot.permissions.distinct().forEach { permissionName ->
            if (permissionName in existing) {
                preserved += 1
                return@forEach
            }
            val sourceResult = runCatching {
                permissionChecker(permissionName, runtime.originPackageName)
            }.getOrElse {
                unresolved += 1
                return@forEach
            }
            val sourceGranted = sourceResult == PackageManager.PERMISSION_GRANTED
            val persisted = permissionService.setPermissionGrant(
                runtime.instanceId,
                permissionName,
                sourceGranted,
                EnginePermissionGrantSource.SOURCE_APP_MIRROR
            )
            if (!persisted.explicit) {
                unresolved += 1
            } else if (sourceGranted) {
                granted += 1
            } else {
                denied += 1
            }
        }
        return EnginePermissionSeedResult(
            verdict = if (unresolved == 0) EngineResultStatus.PASS else EngineResultStatus.PARTIAL,
            mirroredGrantCount = granted,
            mirroredDenyCount = denied,
            preservedDecisionCount = preserved,
            unresolvedCount = unresolved,
            message = "permission_seed_complete:granted=$granted:denied=$denied:" +
                "preserved=$preserved:unresolved=$unresolved"
        )
    }
}
