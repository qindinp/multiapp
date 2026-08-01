package com.multiapp.core.model.engine

/**
 * Single authority for guest process slot naming and range.
 *
 * The host manifest declares 24 guest process slots (`:v0`..`:v23`) used by the bootstrap
 * providers, proxy activities, and the engine slot allocator. Every consumer that parses or
 * validates a process slot must derive the range from this contract; a hard-coded count
 * anywhere else will drift (the terminator previously rejected slots >= v8, which made
 * delete/stop fail closed for any instance assigned to v8+).
 */
object EngineProcessSlotContract {
    const val PROCESS_SLOT_COUNT = 24

    private const val SLOT_INFIX = ":v"

    /**
     * Parses the slot index from a process slot name such as `com.multiapp.app:v9`.
     *
     * Lenient on purpose: a numeric but non-canonical suffix (`v03`) still resolves to its
     * index so callers can canonicalize. Returns null for foreign packages, missing or
     * non-numeric suffixes, and out-of-range indexes.
     */
    fun processSlotIndex(hostPackageName: String, processSlot: String?): Int? {
        if (hostPackageName.isBlank() || processSlot.isNullOrBlank()) return null
        val expectedPrefix = "$hostPackageName$SLOT_INFIX"
        if (!processSlot.startsWith(expectedPrefix)) return null
        return processSlot
            .removePrefix(expectedPrefix)
            .toIntOrNull()
            ?.takeIf { it in 0 until PROCESS_SLOT_COUNT }
    }

    /**
     * Strict form of [processSlotIndex]: the suffix must already be in canonical decimal
     * form (`v3` accepted, `v03` rejected). Use this for security-sensitive identity checks
     * where a non-canonical alias must not be treated as the same slot.
     */
    fun isCanonicalProcessSlot(hostPackageName: String, processSlot: String?): Boolean {
        val index = processSlotIndex(hostPackageName, processSlot) ?: return false
        return processSlot == "$hostPackageName$SLOT_INFIX$index"
    }
}
