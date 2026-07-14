package com.multiapp.core.loader

import android.os.Process

internal object RuntimeUidCompat {
    fun resolve(preferredUid: Int? = null): Int {
        preferredUid?.takeIf { it > 0 }?.let { return it }
        val processUid = runCatching { Process.myUid() }
            .getOrElse { Process.FIRST_APPLICATION_UID }
        require(processUid > 0) { "runtimeUid must be a positive Android application UID" }
        return processUid
    }
}
