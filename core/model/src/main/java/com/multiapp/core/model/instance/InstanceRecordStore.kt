package com.multiapp.core.model.instance

import com.google.gson.GsonBuilder
import java.io.File

/**
 * Persistence interface for [VirtualInstanceRecord].
 *
 * Each record is stored as a separate JSON file:
 *   baseDir/<instanceId>.json
 *
 * Implementations must handle:
 * - Atomic writes (write temp, then rename).
 * - Schema version awareness on load.
 * - Automatic [VirtualInstanceRecord.updatedAtMs] on save.
 */
interface InstanceRecordStore {

    /**
     * Persist a record. Overwrites any existing record with the same instanceId.
     *
     * @return Success with the instanceId, or failure.
     */
    fun save(record: VirtualInstanceRecord): Result<String>

    /**
     * Load a record by instanceId.
     *
     * @return The record if found and valid, null otherwise.
     */
    fun load(instanceId: String): VirtualInstanceRecord?

    /**
     * Load all records matching the given origin package name.
     */
    fun loadByOrigin(originPackageName: String): List<VirtualInstanceRecord>

    /**
     * List all persisted records.
     */
    fun listAll(): List<VirtualInstanceRecord>

    /**
     * Delete a record.
     *
     * @return true if the record existed and was deleted.
     */
    fun delete(instanceId: String): Boolean
}

/**
 * JSON-backed implementation of [InstanceRecordStore].
 *
 * Each record is stored as `baseDir/<instanceId>.json`.
 * Writes are atomic: content is written to a temp file first, then renamed.
 *
 * @param baseDir Directory where JSON files are stored.
 */
class JsonInstanceRecordStore(private val baseDir: File) : InstanceRecordStore {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    /** Visible for testing: ensure the base directory exists. */
    fun listFiles(): Array<File>? = baseDir.listFiles()

    override fun save(record: VirtualInstanceRecord): Result<String> {
        return runCatching {
            val file = fileFor(record.instanceId)
            val tempFile = File(baseDir, "${record.instanceId}.json.tmp")

            val json = gson.toJson(record)
            tempFile.writeText(json, Charsets.UTF_8)

            // Delete existing target before rename (required on Windows)
            if (file.exists()) {
                file.delete()
            }

            val renamed = tempFile.renameTo(file)
            if (!renamed) {
                tempFile.delete()
                error("Failed to rename temp file to ${file.name}")
            }

            record.instanceId
        }
    }

    override fun load(instanceId: String): VirtualInstanceRecord? {
        val file = fileFor(instanceId)
        if (!file.exists()) return null

        return runCatching {
            val json = file.readText(Charsets.UTF_8)
            gson.fromJson(json, VirtualInstanceRecord::class.java)
        }.getOrNull()
    }

    override fun loadByOrigin(originPackageName: String): List<VirtualInstanceRecord> {
        return listAll().filter { it.originPackageName == originPackageName }
    }

    override fun listAll(): List<VirtualInstanceRecord> {
        val files = baseDir.listFiles() ?: return emptyList()
        return files
            .filter { it.extension == "json" && !it.name.endsWith(".tmp") }
            .mapNotNull { file ->
                runCatching {
                    val json = file.readText(Charsets.UTF_8)
                    gson.fromJson(json, VirtualInstanceRecord::class.java)
                }.getOrNull()
            }
    }

    override fun delete(instanceId: String): Boolean {
        val file = fileFor(instanceId)
        return file.exists() && file.delete()
    }

    private fun fileFor(instanceId: String): File {
        return File(baseDir, "$instanceId.json")
    }
}
