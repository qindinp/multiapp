package com.multiapp.core.installer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log

/** Runs before the engine Provider so no package record is consumed before reconciliation. */
class PackageGenerationRecoveryProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val hostContext = checkNotNull(context) {
            "Package generation recovery Context is unavailable"
        }
        val layout = PackageGenerationLayout.fromFilesDir(hostContext.filesDir)
        val result = PackageGenerationReconciler(
            installRecordDir = layout.installRecordDir,
            artifactDir = layout.artifactDir,
            journalDir = layout.journalDir
        ).reconcile()
        if (!result.success) {
            Log.e(TAG, "Package generation reconcile failed: ${result.errors.joinToString()}")
        }
        requireSuccessfulPackageGenerationRecovery(result)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private companion object {
        const val TAG = "PackageGenRecovery"
    }
}

internal fun requireSuccessfulPackageGenerationRecovery(
    result: PackageGenerationReconcileResult
) {
    check(result.success) {
        "Package generation reconcile failed: ${result.errors.joinToString()}"
    }
}
