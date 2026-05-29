package com.multiapp.core.manifest

import javax.inject.Inject

/**
 * 从解析后的 Manifest 中提取所有组件声明
 */
class ComponentExtractor @Inject constructor() {

    fun extractLauncherActivity(manifest: ManifestParser.ParsedManifest): ManifestParser.ComponentInfo? {
        return manifest.activities.find { activity ->
            activity.intentFilters.any { filter ->
                filter.actions.contains("android.intent.action.MAIN") &&
                    filter.categories.contains("android.intent.category.LAUNCHER")
            }
        }
    }

    fun extractAllComponents(manifest: ManifestParser.ParsedManifest): List<ManifestParser.ComponentInfo> {
        return manifest.activities + manifest.services + manifest.receivers + manifest.providers.map {
            ManifestParser.ComponentInfo(name = it.name, exported = it.exported)
        }
    }

    /**
     * 提取 manifest 中声明的所有权限 (uses-permission)
     */
    fun extractPermissions(manifest: ManifestParser.ParsedManifest): List<String> {
        return manifest.permissions
    }

    /**
     * 提取所有组件中出现的自定义进程名 (android:process 属性)
     * 保留 ":" 开头的相对进程名 (如 ":remote")
     */
    fun extractProcesses(manifest: ManifestParser.ParsedManifest): Set<String> {
        val allComponents: List<ManifestParser.ComponentInfo> =
            manifest.activities + manifest.services + manifest.receivers
        return allComponents
            .mapNotNull { it.process }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
