package com.multiapp.core.installer

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PackageGenerationRecoveryProviderTest {
    @Test
    fun `successful reconcile allows provider initialization`() {
        assertDoesNotThrow {
            requireSuccessfulPackageGenerationRecovery(result(success = true))
        }
    }

    @Test
    fun `failed reconcile aborts provider initialization`() {
        val error = assertThrows(IllegalStateException::class.java) {
            requireSuccessfulPackageGenerationRecovery(
                result(success = false, errors = listOf("record enumeration failed"))
            )
        }

        assertTrue(error.message.orEmpty().contains("record enumeration failed"))
    }

    private fun result(
        success: Boolean,
        errors: List<String> = emptyList()
    ) = PackageGenerationReconcileResult(
        success = success,
        recordsEnumerated = 0,
        recordFilesRecovered = 0,
        stagingFilesDeleted = 0,
        tombstonesRestored = 0,
        tombstonesDeleted = 0,
        orphanArtifactsDeleted = 0,
        abandonedJournalsDeleted = 0,
        orphanGcSkipped = !success,
        errors = errors
    )
}
