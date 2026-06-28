package com.multiapp.core.loader

import android.content.SharedPreferences
import java.io.File
import java.util.Properties
import java.util.concurrent.CopyOnWriteArraySet

internal class FileBackedSharedPreferences(private val file: File) : SharedPreferences {
    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private var values: MutableMap<String, Any> = readValues()

    override fun getAll(): MutableMap<String, *> = synchronized(lock) { values.toMutableMap() }

    override fun getString(key: String, defValue: String?): String? =
        synchronized(lock) { values[key] as? String ?: defValue }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(lock) { (values[key] as? Set<String>)?.toMutableSet() ?: defValues }

    override fun getInt(key: String, defValue: Int): Int =
        synchronized(lock) { values[key] as? Int ?: defValue }

    override fun getLong(key: String, defValue: Long): Long =
        synchronized(lock) { values[key] as? Long ?: defValue }

    override fun getFloat(key: String, defValue: Float): Float =
        synchronized(lock) { values[key] as? Float ?: defValue }

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        synchronized(lock) { values[key] as? Boolean ?: defValue }

    override fun contains(key: String): Boolean = synchronized(lock) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        listener?.let { listeners.add(it) }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        listener?.let { listeners.remove(it) }
    }

    private fun readValues(): MutableMap<String, Any> {
        if (!file.isFile) return mutableMapOf()
        val properties = Properties()
        file.inputStream().use { properties.loadFromXML(it) }
        return properties.stringPropertyNames().associateWith { key ->
            decodeValue(properties.getProperty(key))
        }.toMutableMap()
    }

    private fun writeValues(snapshot: Map<String, Any>) {
        file.parentFile?.mkdirs()
        val properties = Properties()
        snapshot.forEach { (key, value) -> properties.setProperty(key, encodeValue(value)) }
        file.outputStream().use { properties.storeToXML(it, null, Charsets.UTF_8.name()) }
    }

    private fun notifyChanged(changedKeys: Set<String>) {
        if (changedKeys.isEmpty() || listeners.isEmpty()) return
        changedKeys.forEach { key ->
            listeners.forEach { listener -> listener.onSharedPreferenceChanged(this, key) }
        }
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            put(key, value)

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            put(key, values?.toSet())

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = put(key, value)

        override fun remove(key: String): SharedPreferences.Editor = put(key, null)

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            val changedKeys: Set<String>
            synchronized(lock) {
                val next = if (clearRequested) mutableMapOf() else values.toMutableMap()
                pending.forEach { (key, value) ->
                    if (value == null) next.remove(key) else next[key] = value
                }
                changedKeys = changedKeys(values, next)
                writeValues(next)
                values = next
            }
            notifyChanged(changedKeys)
            return true
        }

        override fun apply() {
            commit()
        }

        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
    }

    private companion object {
        private const val TYPE_STRING = "s"
        private const val TYPE_STRING_SET = "ss"
        private const val TYPE_INT = "i"
        private const val TYPE_LONG = "l"
        private const val TYPE_FLOAT = "f"
        private const val TYPE_BOOLEAN = "b"
        private const val SEPARATOR = ":"
        private const val SET_SEPARATOR = "\u001f"

        private fun encodeValue(value: Any): String = when (value) {
            is String -> TYPE_STRING + SEPARATOR + value
            is Set<*> -> TYPE_STRING_SET + SEPARATOR + value.filterIsInstance<String>().joinToString(SET_SEPARATOR)
            is Int -> TYPE_INT + SEPARATOR + value
            is Long -> TYPE_LONG + SEPARATOR + value
            is Float -> TYPE_FLOAT + SEPARATOR + value
            is Boolean -> TYPE_BOOLEAN + SEPARATOR + value
            else -> TYPE_STRING + SEPARATOR + value.toString()
        }

        private fun decodeValue(encoded: String): Any {
            val type = encoded.substringBefore(SEPARATOR, TYPE_STRING)
            val raw = encoded.substringAfter(SEPARATOR, encoded)
            return when (type) {
                TYPE_STRING_SET -> if (raw.isEmpty()) emptySet<String>() else raw.split(SET_SEPARATOR).toSet()
                TYPE_INT -> raw.toIntOrNull() ?: 0
                TYPE_LONG -> raw.toLongOrNull() ?: 0L
                TYPE_FLOAT -> raw.toFloatOrNull() ?: 0f
                TYPE_BOOLEAN -> raw.toBooleanStrictOrNull() ?: false
                else -> raw
            }
        }

        private fun changedKeys(old: Map<String, Any>, new: Map<String, Any>): Set<String> {
            val keys = old.keys + new.keys
            return keys.filterTo(linkedSetOf()) { old[it] != new[it] }
        }
    }
}