package com.multiapp.core.designsystem.components

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private object AppIconBitmapCache {
    private const val MAX_CACHE_KIB = 12 * 1024

    private val cache = object : LruCache<String, ImageBitmap>(MAX_CACHE_KIB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            val sizeBytes = value.width.toLong() * value.height.toLong() * 4L
            return (sizeBytes / 1024L)
                .coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
    }
    private val loadLocks = ConcurrentHashMap<String, Mutex>()

    fun get(key: String): ImageBitmap? = cache.get(key)

    suspend fun getOrLoad(key: String, loader: () -> ImageBitmap?): ImageBitmap? {
        get(key)?.let { return it }
        val lock = loadLocks.getOrPut(key) { Mutex() }
        return try {
            lock.withLock {
                get(key) ?: loader()?.also { cache.put(key, it) }
            }
        } finally {
            loadLocks.remove(key, lock)
        }
    }
}

@Composable
fun rememberAppIconBitmap(
    cacheKey: String,
    sizePx: Int,
    loadDrawable: (Context) -> Drawable?
): ImageBitmap? {
    require(sizePx > 0) { "sizePx must be positive" }
    val appContext = LocalContext.current.applicationContext
    val bitmapKey = remember(cacheKey, sizePx) { "$cacheKey@$sizePx" }
    val initialBitmap = remember(bitmapKey) { AppIconBitmapCache.get(bitmapKey) }
    val currentLoader by rememberUpdatedState(loadDrawable)
    val bitmap by produceState<ImageBitmap?>(initialBitmap, bitmapKey) {
        if (value != null) return@produceState
        val loader = currentLoader
        value = withContext(Dispatchers.IO) {
            AppIconBitmapCache.getOrLoad(bitmapKey) {
                runCatching {
                    loader(appContext)
                        ?.toBitmap(width = sizePx, height = sizePx)
                        ?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    return bitmap
}
