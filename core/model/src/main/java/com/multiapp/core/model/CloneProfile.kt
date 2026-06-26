package com.multiapp.core.model

enum class CloneProfile {
    NORMAL,
    PROTECTED_EXPERIMENTAL,
    QQ_READER_SPECIAL;

    companion object {
        fun forPackage(packageName: String): CloneProfile = NORMAL
    }
}
