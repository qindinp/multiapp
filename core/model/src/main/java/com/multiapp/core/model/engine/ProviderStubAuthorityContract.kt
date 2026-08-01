package com.multiapp.core.model.engine

object ProviderStubAuthorityContract {
    private const val PROCESS_SLOT_COUNT = EngineProcessSlotContract.PROCESS_SLOT_COUNT
    private const val STUB_AUTHORITY_SUFFIX = ".multiapp.provider.stub"
    private const val SLOT_AUTHORITY_PREFIX = ".v"

    fun stubAuthority(hostPackageName: String, processSlot: String?): String {
        val baseAuthority = "$hostPackageName$STUB_AUTHORITY_SUFFIX"
        val slotIndex = processSlotIndex(hostPackageName, processSlot) ?: return baseAuthority
        return "$baseAuthority$SLOT_AUTHORITY_PREFIX$slotIndex"
    }

    fun hostPackageNameOrNull(authority: String?): String? {
        if (authority.isNullOrBlank()) return null
        val baseAuthority = when {
            authority.endsWith(STUB_AUTHORITY_SUFFIX) -> authority
            else -> authority.baseAuthorityFromCanonicalSlotOrNull() ?: return null
        }
        return baseAuthority
            .removeSuffix(STUB_AUTHORITY_SUFFIX)
            .takeIf { it.isNotBlank() }
    }

    fun reselectProcessSlot(authority: String?, processSlot: String?): String? =
        hostPackageNameOrNull(authority)?.let { hostPackageName ->
            stubAuthority(hostPackageName, processSlot)
        }

    private fun processSlotIndex(hostPackageName: String, processSlot: String?): Int? =
        EngineProcessSlotContract.processSlotIndex(hostPackageName, processSlot)

    private fun String.baseAuthorityFromCanonicalSlotOrNull(): String? {
        val slotPrefixIndex = lastIndexOf(SLOT_AUTHORITY_PREFIX)
        if (slotPrefixIndex < 0) return null
        val encodedSlotIndex = substring(slotPrefixIndex + SLOT_AUTHORITY_PREFIX.length)
        val slotIndex = encodedSlotIndex
            .toIntOrNull()
            ?.takeIf { it in 0 until PROCESS_SLOT_COUNT }
            ?: return null
        if (encodedSlotIndex != slotIndex.toString()) return null
        return substring(0, slotPrefixIndex)
            .takeIf { it.endsWith(STUB_AUTHORITY_SUFFIX) }
    }
}
