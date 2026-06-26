package com.multiapp.core.loader

/**
 * Describes the ordered sequence of [RuntimeStage]s the orchestrator will
 * execute, along with metadata for diagnostics and compatibility checks.
 */
data class RuntimeBootstrapPlan(
    val stages: List<RuntimeStage>,
    val requiredStages: List<RuntimeStage>,
    val optionalStages: List<RuntimeStage>,
    val profileName: String = "default",
    val diagnosticTags: List<String> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis()
) {
    init {
        val stageSet = stages.toSet()
        val duplicates = stages.groupBy { s: RuntimeStage -> s }
            .filter { entry: Map.Entry<RuntimeStage, List<RuntimeStage>> -> entry.value.size > 1 }
            .keys
        require(stages.size == stageSet.size) {
            "stages must not contain duplicates, found duplicates: $duplicates"
        }

        val requiredSet = requiredStages.toSet()
        val optionalSet = optionalStages.toSet()
        val overlap = requiredSet.intersect(optionalSet)
        require(overlap.isEmpty()) {
            "required and optional stages must not overlap, found: $overlap"
        }

        val declared = requiredSet + optionalSet
        val missing = declared - stageSet
        require(missing.isEmpty()) {
            "required/optional stages reference stages not in stages list: $missing"
        }
    }

    companion object {
        /**
         * Plan that matches the existing LoaderFactory initialization order:
         * CONFIG -> GUEST_CONTEXT -> PACKAGE_METADATA -> ORIGIN_APK ->
         * NATIVE_LIBS -> RESOURCES -> CLASS_LOADER -> APPLICATION
         *
         * First five stages (CONFIG through NATIVE_LIBS) are required;
         * remaining three (RESOURCES through APPLICATION) are optional
         * (they can degrade without killing the bootstrap).
         */
        fun loaderFactoryCompatible(createdAtMs: Long = System.currentTimeMillis()): RuntimeBootstrapPlan {
            val stages = listOf(
                RuntimeStage.CONFIG,
                RuntimeStage.GUEST_CONTEXT,
                RuntimeStage.PACKAGE_METADATA,
                RuntimeStage.ORIGIN_APK,
                RuntimeStage.NATIVE_LIBS,
                RuntimeStage.RESOURCES,
                RuntimeStage.CLASS_LOADER,
                RuntimeStage.APPLICATION
            )
            return RuntimeBootstrapPlan(
                stages = stages,
                requiredStages = stages.subList(0, 5),
                optionalStages = stages.subList(5, 8),
                profileName = "loader-factory-compatible",
                diagnosticTags = listOf("loader-factory", "v1"),
                createdAtMs = createdAtMs
            )
        }
    }
}
