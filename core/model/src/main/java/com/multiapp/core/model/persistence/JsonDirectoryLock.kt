package com.multiapp.core.model.persistence

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serializes JSON record-store operations across threads and Android processes.
 *
 * The JVM lock handles overlapping FileChannel locks in one VM, while the lock file
 * coordinates separate processes sharing the same application data directory.
 */
internal object JsonDirectoryLock {
    private const val LOCK_FILE_NAME = ".multiapp-record-store.lock"
    private val processLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> withExclusiveLock(baseDir: File, action: () -> T): T {
        val lockFile = File(baseDir, LOCK_FILE_NAME)
        val key = lockFile.toPath().toAbsolutePath().normalize().toString()
        val processLock = processLocks.computeIfAbsent(key) { ReentrantLock() }

        return processLock.withLock {
            if (!baseDir.exists() && !baseDir.mkdirs()) {
                error("Failed to create record-store directory: ${baseDir.absolutePath}")
            }
            FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            ).use { channel ->
                channel.lock().use {
                    action()
                }
            }
        }
    }
}
