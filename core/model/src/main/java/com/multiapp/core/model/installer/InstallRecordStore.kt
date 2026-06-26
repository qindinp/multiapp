package com.multiapp.core.model.installer

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.util.concurrent.CancellationException

interface InstallRecordStore {
    fun save(record: InstallRecord): Result<String>
    fun load(packageName: String): InstallRecord?
    fun listAll(): List<InstallRecord>
    fun delete(packageName: String): Boolean
}

class JsonInstallRecordStore(private val baseDir: File) : InstallRecordStore {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    override fun save(record: InstallRecord): Result<String> {
        return try {
            val updatedRecord = record.copy(updatedAtMs = System.currentTimeMillis())
            val fileName = "${record.packageName}.json"
            val targetFile = File(baseDir, fileName)
            val tempFile = File(baseDir, "$fileName.tmp")
            val backupFile = File(baseDir, "$fileName.bak")

            tempFile.writeText(gson.toJson(updatedRecord))

            // Two-phase rename for crash safety:
            // 1. If target exists, rename to .bak (preserves old data on crash)
            // 2. Rename temp to target
            // 3. Delete .bak
            if (targetFile.exists()) {
                if (!targetFile.renameTo(backupFile)) {
                    tempFile.delete()
                    return Result.failure(RuntimeException("Failed to backup existing file"))
                }
            }

            val success = tempFile.renameTo(targetFile)
            if (success) {
                backupFile.delete() // Clean up backup
                Result.success(targetFile.absolutePath)
            } else {
                // Attempt to restore from backup
                if (backupFile.exists()) {
                    backupFile.renameTo(targetFile)
                }
                tempFile.delete()
                Result.failure(RuntimeException("Failed to rename temp file to target"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun load(packageName: String): InstallRecord? {
        return try {
            val file = File(baseDir, "$packageName.json")
            if (!file.exists()) return null

            val json = file.readText()
            val record = gson.fromJson(json, InstallRecord::class.java)

            if (record.schemaVersion != 1) {
                return null
            }

            record
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override fun listAll(): List<InstallRecord> {
        return baseDir.listFiles()
            ?.filter { it.name.endsWith(".json") && !it.name.endsWith(".tmp") && !it.name.endsWith(".bak") }
            ?.mapNotNull { file ->
                try {
                    val json = file.readText()
                    val record = gson.fromJson(json, InstallRecord::class.java)
                    if (record.schemaVersion == 1) record else null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            ?: emptyList()
    }

    override fun delete(packageName: String): Boolean {
        val file = File(baseDir, "$packageName.json")
        return file.exists() && file.delete()
    }
}
