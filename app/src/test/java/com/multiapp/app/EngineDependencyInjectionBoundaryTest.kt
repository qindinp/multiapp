package com.multiapp.app

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineDependencyInjectionBoundaryTest {

    @Test
    fun `production VirtualizationEngine binding is IPC only`() {
        val source = productionSource("com/multiapp/app/AppModule.kt").readText()
        val bindings = virtualizationEngineBindingRegex.findAll(source).toList()

        assertEquals(
            1,
            bindings.size,
            "AppModule must expose exactly one production VirtualizationEngine binding"
        )
        val binding = bindings.single()
        assertEquals(
            "IpcVirtualizationEngine",
            binding.groups["implementation"]?.value,
            "Production VirtualizationEngine must be supplied by the IPC facade"
        )
        assertEquals(
            binding.groups["parameter"]?.value,
            binding.groups["expression"]?.value,
            "The binding must return the injected IPC facade unchanged"
        )
        assertTrue(
            ipcVirtualizationEngineImportRegex.containsMatchIn(source),
            "AppModule must import the production IPC facade"
        )
        assertFalse(
            Regex("""\bDefaultVirtualizationEngine\b""").containsMatchIn(source),
            "AppModule must not bind or construct the in-process DefaultVirtualizationEngine"
        )
    }

    @Test
    fun `EngineBinderProvider obtains its only server owner from Hilt`() {
        val source = productionSource("com/multiapp/app/container/EngineBinderProvider.kt").readText()

        assertTrue(
            hiltOwnerLookupRegex.containsMatchIn(source),
            "EngineBinderProvider must obtain EngineServerRuntime through its Hilt entry point"
        )
        assertTrue(
            engineServerRuntimeEntryPointRegex.containsMatchIn(source),
            "EngineServerRuntimeEntryPoint must expose the singleton EngineServerRuntime owner"
        )
        assertEquals(
            1,
            Regex("""\bEngineRuntimeBinderEndpoint\s*\(""").findAll(source).count(),
            "EngineBinderProvider must create exactly one Binder endpoint"
        )
        ownerBackedEndpointArguments.forEach { argument ->
            assertTrue(
                Regex("""\b${Regex.escape(argument)}\b""").containsMatchIn(source),
                "EngineRuntimeBinderEndpoint must use the shared owner dependency: $argument"
            )
        }
        forbiddenLocalOwnerTypes.forEach { type ->
            assertFalse(
                Regex("""\b${Regex.escape(type)}\b""").containsMatchIn(source),
                "EngineBinderProvider must not reference or construct a local $type graph"
            )
        }
    }

    @Test
    fun `host clone coordinator does not reference owner file dependencies`() {
        val source = coreSource(
            "com/multiapp/core/instance/CloneCreateUseCase.kt"
        ).readText()

        hostCloneForbiddenOwnerTypes.forEach { type ->
            assertFalse(
                Regex("""\b${Regex.escape(type)}\b""").containsMatchIn(source),
                "CloneCreateUseCase (host-facing coordinator) must not reference $type; " +
                    "instance operations must go through VirtualizationEngine only"
            )
        }
        assertTrue(
            Regex("""\bVirtualizationEngine\b""").containsMatchIn(source),
            "CloneCreateUseCase must depend on VirtualizationEngine"
        )
    }

    @Test
    fun `InstanceBoundaryModule binds coordinator to use case without owner store exposure`() {
        val source = coreSource(
            "com/multiapp/core/instance/InstanceBoundaryModule.kt"
        ).readText()

        assertTrue(
            bindCoordinatorRegex.containsMatchIn(source),
            "InstanceBoundaryModule must bind CloneCreateUseCase as CloneCreationCoordinator"
        )
        hostCloneForbiddenOwnerTypes.forEach { type ->
            assertFalse(
                Regex("""\b${Regex.escape(type)}\b""").containsMatchIn(source),
                "InstanceBoundaryModule must not reference $type in its bindings"
            )
        }
    }

    private fun productionSource(relativePath: String): File {
        val source = File(repoRoot(), "app/src/main/java/$relativePath")
        check(source.isFile) { "Unable to locate production source: $source" }
        return source
    }

    private fun coreSource(relativePath: String): File {
        val source = File(repoRoot(), "core/instance/src/main/java/$relativePath")
        check(source.isFile) { "Unable to locate core source: $source" }
        return source
    }

    private fun repoRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir is unavailable")
        return generateSequence(File(userDir).absoluteFile) { file -> file.parentFile?.absoluteFile }
            .firstOrNull { candidate -> File(candidate, "settings.gradle.kts").isFile }
            ?: error("Unable to locate repository root from $userDir")
    }

    private companion object {
        val virtualizationEngineBindingRegex = Regex(
            """fun\s+\w+\s*\(\s*(?<parameter>\w+)\s*:\s*(?<implementation>[A-Za-z0-9_.]+)\s*\)\s*:\s*VirtualizationEngine\s*=\s*(?<expression>\w+)"""
        )

        val ipcVirtualizationEngineImportRegex =
            Regex("""import\s+com\.multiapp\.core\.engine\.IpcVirtualizationEngine\s*""")

        val hiltOwnerLookupRegex = Regex(
            """(?s)val\s+owner\s*=\s*EntryPointAccessors\.fromApplication\s*\(.*?EngineServerRuntimeEntryPoint::class\.java\s*\)\s*\.engineServerRuntime\s*\(\s*\)"""
        )

        val engineServerRuntimeEntryPointRegex = Regex(
            """(?s)@EntryPoint\s*@InstallIn\s*\(\s*SingletonComponent::class\s*\)\s*internal\s+interface\s+EngineServerRuntimeEntryPoint\s*\{\s*fun\s+engineServerRuntime\s*\(\s*\)\s*:\s*EngineServerRuntime\s*}"""
        )

        val ownerBackedEndpointArguments = listOf(
            "registry = owner.runtimeRegistry",
            "serverGenerationId = owner.serverGenerationId",
            "activityLaunchCapabilities = owner.activityLaunchCapabilities",
            "activityService = owner.systemServer.activityService",
            "providerService = owner.systemServer.providerService",
            "permissionService = owner.systemServer.permissionService",
            "appOpsService = owner.systemServer.appOpsService",
            "serviceService = owner.systemServer.serviceService",
            "broadcastService = owner.systemServer.broadcastService",
            "packageService = owner.systemServer.packageService",
            "virtualizationEngine = owner.virtualizationEngine",
            "processControlPlane = owner.processControlPlane"
        )

        val forbiddenLocalOwnerTypes = listOf(
            "EngineRuntimeRegistry",
            "DefaultVirtualSystemServer",
            "DefaultVirtualizationEngine"
        )

        /** Owner types that the host clone coordinator must NOT reference. */
        val hostCloneForbiddenOwnerTypes = listOf(
            "InstanceManager",
            "PackageGenerationJournal",
            "PackageGenerationTransaction",
            "PackageGenerationTransactionJournal"
        )

        val bindCoordinatorRegex = Regex(
            """fun\s+\w+\s*\(\s*\w+\s*:\s*CloneCreateUseCase\s*\)\s*:\s*CloneCreationCoordinator"""
        )
    }


    @Test
    fun `AppModule owner store providers are process-guarded`() {
        val source = productionSource("com/multiapp/app/AppModule.kt").readText()

        // Verify the process guard function exists
        assertTrue(
            source.contains("private fun requireEngineProcess(context: Context)"),
            "AppModule must define requireEngineProcess() for owner store defense-in-depth"
        )
        assertTrue(
            source.contains("EngineRuntimeIpcContract.engineProcessName(context.packageName)"),
            "requireEngineProcess must use EngineRuntimeIpcContract to resolve expected process name"
        )

        // Verify all 5 owner store providers call requireEngineProcess
        val guardedProviders = listOf(
            "provideEngineRuntimeSlotStore",
            "provideInstanceRecordStore",
            "provideInstallRecordStore",
            "provideVirtualInstallService",
            "provideInstanceManager"
        )
        for (provider in guardedProviders) {
            val bodyStart = source.indexOf("{", source.indexOf(provider))
            val bodyEnd = findMatchingBrace(source, bodyStart)
            val body = source.substring(bodyStart, bodyEnd + 1)
            assertTrue(
                body.contains("requireEngineProcess(context)"),
                "$provider must call requireEngineProcess(context) before constructing owner stores"
            )
        }
    }

    private fun findMatchingBrace(source: String, openIndex: Int): Int {
        var depth = 0
        for (i in openIndex until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        error("Unmatched brace at index $openIndex")
    }

}
