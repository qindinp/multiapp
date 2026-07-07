package com.multiapp.core.loader

import android.content.Intent

/** Process-local store for original guest Service intents carried by proxy tokens. */
object VirtualServiceIntentStore {
    private const val MAX_ENTRIES = 256
    private val intents = linkedMapOf<String, Intent>()
    private var intentCopier: (Intent) -> Intent = { intent -> Intent(intent) }

    @Synchronized
    fun remember(token: String?, intent: Intent?) {
        if (token.isNullOrBlank() || intent == null) return
        intents[token] = intentCopier(intent)
        trimToMaxEntries()
    }

    @Synchronized
    fun find(token: String?): Intent? {
        if (token.isNullOrBlank()) return null
        return intents[token]?.let { intentCopier(it) }
    }

    @Synchronized
    fun clear(token: String?) {
        if (!token.isNullOrBlank()) {
            intents.remove(token)
        }
    }

    @Synchronized
    fun clearAll() {
        intents.clear()
    }

    @Synchronized
    fun size(): Int = intents.size

    @Synchronized
    internal fun setIntentCopierForTest(copier: (Intent) -> Intent) {
        intentCopier = copier
    }

    @Synchronized
    internal fun resetIntentCopierForTest() {
        intentCopier = { intent -> Intent(intent) }
    }

    private fun trimToMaxEntries() {
        while (intents.size > MAX_ENTRIES) {
            val eldest = intents.keys.firstOrNull() ?: return
            intents.remove(eldest)
        }
    }
}
