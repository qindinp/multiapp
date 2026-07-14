package com.multiapp.core.installer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PackageGenerationJournalTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `begin publishes durable journal and complete removes it`() {
        val journalDir = File(tempDir, "journal")
        val journal = journal(journalDir)

        val transaction = journal.begin(PACKAGE_NAME, REQUEST_ID, FINGERPRINT)

        val published = journalDir.listFiles().orEmpty().single { it.name.endsWith(".journal") }
        assertTrue(published.readText().contains("packageName=$PACKAGE_NAME"))
        assertTrue(transaction.complete())
        assertFalse(published.exists())
    }

    @Test
    fun `abandon leaves journal for startup reconciliation`() {
        val layout = layout("abandon")
        val transaction = journal(layout.journalDir).begin(PACKAGE_NAME, REQUEST_ID, FINGERPRINT)

        transaction.abandon()
        assertEquals(1, layout.journalDir.listFiles().orEmpty().count { it.name.endsWith(".journal") })

        val result = reconciler(layout).reconcile()

        assertTrue(result.success, result.errors.toString())
        assertEquals(1, result.abandonedJournalsDeleted)
        assertFalse(layout.journalDir.listFiles().orEmpty().any { it.name.endsWith(".journal") })
    }

    @Test
    fun `startup recovers journal write crashes before and after publish`() {
        val points = listOf(
            PackageGenerationFaultPoint.AFTER_JOURNAL_TEMP_SYNCED,
            PackageGenerationFaultPoint.AFTER_JOURNAL_PUBLISHED
        )

        points.forEachIndexed { index, point ->
            val layout = layout("publish-$index")
            val crashing = journal(layout.journalDir, point)

            assertThrows(SimulatedCrash::class.java) {
                crashing.begin(PACKAGE_NAME, REQUEST_ID, FINGERPRINT)
            }
            assertTrue(layout.journalDir.listFiles().orEmpty().any { it.name.contains(".journal") })

            val recovered = reconciler(layout).reconcile()
            assertTrue(recovered.success, "$point: ${recovered.errors}")
            assertFalse(layout.journalDir.listFiles().orEmpty().any { it.name.contains(".journal") })
        }
    }

    @Test
    fun `crash after journal delete is already complete on retry`() {
        val layout = layout("delete")
        val transaction = journal(
            journalDir = layout.journalDir,
            crashAt = PackageGenerationFaultPoint.AFTER_JOURNAL_DELETED
        ).begin(PACKAGE_NAME, REQUEST_ID, FINGERPRINT)

        assertThrows(SimulatedCrash::class.java) { transaction.complete() }

        val recovered = reconciler(layout).reconcile()
        assertTrue(recovered.success, recovered.errors.toString())
        assertEquals(0, recovered.abandonedJournalsDeleted)
    }

    private fun journal(
        journalDir: File,
        crashAt: PackageGenerationFaultPoint? = null
    ): PackageGenerationJournal = PackageGenerationJournal(
        journalDir = journalDir,
        clock = { 1_000L },
        transactionIdFactory = { "transaction-1" },
        faultInjector = PackageGenerationFaultInjector { point ->
            if (point == crashAt) throw SimulatedCrash()
        }
    )

    private fun layout(name: String): PackageGenerationLayout {
        val root = File(tempDir, name)
        return PackageGenerationLayout(
            installRecordDir = File(root, "installs"),
            artifactDir = File(root, "artifacts"),
            journalDir = File(root, "journal")
        )
    }

    private fun reconciler(layout: PackageGenerationLayout) = PackageGenerationReconciler(
        layout = layout,
        directoryLister = PackageGenerationDirectoryLister.DEFAULT,
        faultInjector = PackageGenerationFaultInjector.NONE
    )

    private class SimulatedCrash : Error()

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
        const val REQUEST_ID = "create-request-1"
        val FINGERPRINT = "a".repeat(64)
    }
}
