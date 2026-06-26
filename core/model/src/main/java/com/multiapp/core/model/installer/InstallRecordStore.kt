package com.multiapp.core.model.installer

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

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

            tempFile.writeText(gson.toJson(updatedRecord))

            if (targetFile.exists()) {
                targetFile.delete()
            }

            val success = tempFile.renameTo(targetFile)
            if (success) {
                Result.success(targetFile.absolutePath)
            } else {
                tempFile.delete()
                Result.failure(RuntimeException("Failed to rename temp file to target"))
            }
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
        } catch (e: Exception) {
            null
        }
    }

    override fun listAll(): List<InstallRecord> {
        return baseDir.listFiles()
            ?.filter { it.name.endsWith(".json") && !it.name.endsWith(".tmp") }
            ?.mapNotNull { file ->
                try {
                    val json = file.readText()
                    val record = gson.fromJson(json, InstallRecord::class.java)
                    if (record.schemaVersion == 1) record else null
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
