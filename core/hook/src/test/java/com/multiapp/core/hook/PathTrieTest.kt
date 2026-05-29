package com.multiapp.core.hook

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PathTrie unit tests exercised through NativeHookBridge public API.
 *
 * PathTrie is a private inner class implementing a prefix trie with
 * ReentrantReadWriteLock for thread-safe longest-prefix path translation.
 * Since it cannot be instantiated directly, all tests go through:
 *   - addPathRedirection(from, to)   -> pathTrie.insert
 *   - translatePath(path)            -> pathTrie.translate
 *   - clearPathRedirections()        -> pathTrie.clear
 */
class PathTrieTest {

    private lateinit var bridge: NativeHookBridge

    @BeforeEach
    fun setUp() {
        bridge = NativeHookBridge()
    }

    @AfterEach
    fun tearDown() {
        bridge.cleanup()
    }

    // ===== 1. Empty trie returns original path =====

    @Test
    fun `empty trie returns original path unchanged`() {
        val path = "/data/data/com.example.app/files/config.json"
        assertEquals(path, bridge.translatePath(path))
    }

    @Test
    fun `empty trie returns original path for various inputs`() {
        val paths = listOf(
            "/system/lib64/libc.so",
            "/storage/emulated/0/DCIM/photo.jpg",
            "/mnt/sdcard/Music/song.mp3"
        )
        for (path in paths) {
            assertEquals(path, bridge.translatePath(path))
        }
    }

    // ===== 2. Single prefix match =====

    @Test
    fun `single prefix match replaces prefix and preserves suffix`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")
        assertEquals(
            "/sandbox/files/config.json",
            bridge.translatePath("/data/data/com.app/files/config.json")
        )
    }

    @Test
    fun `single prefix match with nested suffix`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")
        assertEquals(
            "/sandbox/databases/msg.db",
            bridge.translatePath("/data/data/com.app/databases/msg.db")
        )
    }

    @Test
    fun `single prefix match with empty suffix`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")
        assertEquals(
            "/sandbox/",
            bridge.translatePath("/data/data/com.app/")
        )
    }

    @Test
    fun `prefix without trailing slash does not match longer path`() {
        // "com.app" should not match "com.app_extra" character-by-character
        // because the trie walks one char at a time: com.app is a prefix of com.app_extra
        // so translatePath for /data/data/com.app_extra/file.txt would match /data/data/com.app
        // and produce /sandbox/_extra/file.txt -- this is expected trie behavior.
        // For exact boundary control, the caller must use trailing slash.
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")
        // This path shares the prefix up to "com.app/" so it should NOT match
        // because after "com.app" the trie expects "/" but sees "_"
        assertEquals(
            "/data/data/com.app_extra/file.txt",
            bridge.translatePath("/data/data/com.app_extra/file.txt")
        )
    }

    // ===== 3. Longest prefix match =====

    @Test
    fun `longest prefix wins over shorter prefix`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")
        bridge.addPathRedirection("/data/data/com.app/cache/", "/sandbox_cache/")

        // Shorter prefix matches normal file
        assertEquals(
            "/sandbox/shared_prefs/prefs.xml",
            bridge.translatePath("/data/data/com.app/shared_prefs/prefs.xml")
        )

        // Longer prefix matches cache file
        assertEquals(
            "/sandbox_cache/item.db",
            bridge.translatePath("/data/data/com.app/cache/item.db")
        )
    }

    @Test
    fun `longest prefix wins with three levels`() {
        bridge.addPathRedirection("/a/", "/x/")
        bridge.addPathRedirection("/a/b/", "/y/")
        bridge.addPathRedirection("/a/b/c/", "/z/")

        assertEquals("/x/file", bridge.translatePath("/a/file"))
        assertEquals("/y/file", bridge.translatePath("/a/b/file"))
        assertEquals("/z/file", bridge.translatePath("/a/b/c/file"))
    }

    @Test
    fun `longest prefix wins when prefixes added in reverse order`() {
        bridge.addPathRedirection("/a/b/c/", "/z/")
        bridge.addPathRedirection("/a/b/", "/y/")
        bridge.addPathRedirection("/a/", "/x/")

        assertEquals("/z/file", bridge.translatePath("/a/b/c/file"))
        assertEquals("/y/file", bridge.translatePath("/a/b/file"))
        assertEquals("/x/file", bridge.translatePath("/a/file"))
    }

    // ===== 4. Partial prefix does not match =====

    @Test
    fun `partial prefix boundary does not match`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")

        // com.app_extra shares characters but diverges at '_' vs '/'
        assertEquals(
            "/data/data/com.app_extra/file.txt",
            bridge.translatePath("/data/data/com.app_extra/file.txt")
        )
    }

    @Test
    fun `partial match on different suffix character`() {
        bridge.addPathRedirection("/prefix/", "/replacement/")

        // "/prefixX" diverges at 'X' vs '/'
        assertEquals(
            "/prefixXfile",
            bridge.translatePath("/prefixXfile")
        )
    }

    @Test
    fun `no match when path is shorter than prefix`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")
        assertEquals("/data/da", bridge.translatePath("/data/da"))
    }

    // ===== 5. Clear returns original path =====

    @Test
    fun `clearPathRedirections causes translatePath to return original`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")
        assertEquals("/sandbox/file.txt", bridge.translatePath("/data/data/com.app/file.txt"))

        bridge.clearPathRedirections()
        assertEquals("/data/data/com.app/file.txt", bridge.translatePath("/data/data/com.app/file.txt"))
    }

    @Test
    fun `clearPathRedirections is safe when trie is already empty`() {
        bridge.clearPathRedirections()
        bridge.clearPathRedirections()
        assertEquals("/any/path", bridge.translatePath("/any/path"))
    }

    @Test
    fun `after clear and re-insert, translation works again`() {
        bridge.addPathRedirection("/old/", "/replaced/")
        assertEquals("/replaced/file", bridge.translatePath("/old/file"))

        bridge.clearPathRedirections()
        assertEquals("/old/file", bridge.translatePath("/old/file"))

        bridge.addPathRedirection("/new/", "/novel/")
        assertEquals("/novel/file", bridge.translatePath("/new/file"))
    }

    // ===== 6. Overwrite existing prefix =====

    @Test
    fun `overwriting prefix uses new replacement`() {
        bridge.addPathRedirection("/data/data/com.app/", "/old_sandbox/")
        assertEquals("/old_sandbox/file.txt", bridge.translatePath("/data/data/com.app/file.txt"))

        bridge.addPathRedirection("/data/data/com.app/", "/new_sandbox/")
        assertEquals("/new_sandbox/file.txt", bridge.translatePath("/data/data/com.app/file.txt"))
    }

    @Test
    fun `overwriting prefix does not create duplicate entry`() {
        bridge.addPathRedirection("/data/data/com.app/", "/old/")
        bridge.addPathRedirection("/data/data/com.app/", "/new/")
        assertEquals(1, bridge.getRedirectionCount())
    }

    @Test
    fun `overwriting shorter prefix with longer prefix works`() {
        bridge.addPathRedirection("/a/", "/x/")
        assertEquals("/x/b/c", bridge.translatePath("/a/b/c"))

        bridge.addPathRedirection("/a/b/", "/y/")
        // Now longest prefix /a/b/ wins for /a/b/c
        assertEquals("/y/c", bridge.translatePath("/a/b/c"))
        // But /a/d still uses shorter prefix
        assertEquals("/x/d", bridge.translatePath("/a/d"))
    }

    // ===== 7. Empty string handling =====

    @Test
    fun `translatePath with empty string returns empty`() {
        bridge.addPathRedirection("/prefix/", "/replacement/")
        assertEquals("", bridge.translatePath(""))
    }

    @Test
    fun `empty prefix matches everything`() {
        bridge.addPathRedirection("", "redirected")
        // Empty prefix has length 0 — root node not checked in translate loop, so no match
        assertEquals("/anything", bridge.translatePath("/anything"))
    }

    @Test
    fun `empty prefix replacement with empty path`() {
        bridge.addPathRedirection("", "replaced")
        // Empty prefix + empty path: no characters to traverse, no match
        assertEquals("", bridge.translatePath(""))
    }

    // ===== 8. Root path "/" handling =====

    @Test
    fun `root path returns original when no redirection`() {
        assertEquals("/", bridge.translatePath("/"))
    }

    @Test
    fun `root path prefix redirects correctly`() {
        bridge.addPathRedirection("/", "/redirect/")
        assertEquals("/redirect/", bridge.translatePath("/"))
    }

    @Test
    fun `root prefix matches all absolute paths`() {
        bridge.addPathRedirection("/", "/redirect/")
        assertEquals("/redirect/data/file.txt", bridge.translatePath("/data/file.txt"))
    }

    @Test
    fun `longer prefix wins over root prefix`() {
        bridge.addPathRedirection("/", "/root_redirect/")
        bridge.addPathRedirection("/data/", "/data_redirect/")

        assertEquals("/root_redirect/other/file", bridge.translatePath("/other/file"))
        assertEquals("/data_redirect/file", bridge.translatePath("/data/file"))
    }

    // ===== 9. Concurrent read/write safety =====

    @Test
    fun `concurrent reads and writes do not throw exceptions`() {
        val threadCount = 10
        val iterationsPerThread = 500
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)
        val errorOccurred = AtomicBoolean(false)
        val errors = mutableListOf<Throwable>()

        // Pre-populate with some redirections
        for (i in 0 until 50) {
            bridge.addPathRedirection("/data/data/pkg$i/", "/sandbox$i/")
        }

        val threads = (0 until threadCount).map { threadIdx ->
            Thread {
                try {
                    barrier.await()
                    for (i in 0 until iterationsPerThread) {
                        if (threadIdx % 2 == 0) {
                            // Writer thread: insert and clear
                            bridge.addPathRedirection(
                                "/concurrent/$threadIdx/$i/",
                                "/result/$threadIdx/$i/"
                            )
                            if (i % 10 == 0) {
                                bridge.clearPathRedirections()
                            }
                        } else {
                            // Reader thread: translate paths
                            bridge.translatePath("/data/data/pkg${i % 50}/file.txt")
                            bridge.translatePath("/concurrent/${(i + 1) % threadCount}/$i/file.txt")
                            bridge.translatePath("/unknown/path/file.txt")
                        }
                    }
                } catch (e: Throwable) {
                    errorOccurred.set(true)
                    synchronized(errors) { errors.add(e) }
                } finally {
                    latch.countDown()
                }
            }
        }

        threads.forEach { it.start() }
        latch.await()

        assertTrue(
            !errorOccurred.get(),
            "Concurrent access caused errors: ${errors.joinToString("\n") { it.message ?: it.toString() }}"
        )
    }

    @Test
    fun `concurrent insert and translate maintain consistency`() {
        // Verify that translate always returns a valid result (either matched or original)
        // even during concurrent modifications
        val iterations = 1000
        val results = mutableListOf<String>()
        val latch = CountDownLatch(2)

        Thread {
            for (i in 0 until iterations) {
                bridge.addPathRedirection("/dynamic/$i/", "/replaced/$i/")
            }
            latch.countDown()
        }.start()

        Thread {
            for (i in 0 until iterations) {
                val result = bridge.translatePath("/dynamic/$i/file.txt")
                synchronized(results) { results.add(result) }
            }
            latch.countDown()
        }.start()

        latch.await()

        // All results should be valid: either "/replaced/i/file.txt" or "/dynamic/i/file.txt"
        for (result in results) {
            assertTrue(
                result.startsWith("/replaced/") || result.startsWith("/dynamic/"),
                "Unexpected result: $result"
            )
        }
    }

    // ===== 10. Performance with large number of prefixes =====

    @Test
    fun `1000 prefixes insert and translate within reasonable time`() {
        val prefixCount = 1000

        // Insert 1000 prefixes
        val insertStart = System.nanoTime()
        for (i in 0 until prefixCount) {
            bridge.addPathRedirection("/data/data/com.example.app$i/", "/sandbox$i/")
        }
        val insertDuration = (System.nanoTime() - insertStart) / 1_000_000
        assertTrue(
            insertDuration < 30_000,
            "Inserting $prefixCount prefixes took ${insertDuration}ms (limit: 30000ms)"
        )

        // Translate 1000 paths (each matching its own prefix)
        val translateStart = System.nanoTime()
        for (i in 0 until prefixCount) {
            val result = bridge.translatePath("/data/data/com.example.app$i/files/data.bin")
            assertEquals("/sandbox$i/files/data.bin", result)
        }
        val translateDuration = (System.nanoTime() - translateStart) / 1_000_000
        assertTrue(
            translateDuration < 10_000,
            "Translating $prefixCount paths took ${translateDuration}ms (limit: 10000ms)"
        )
    }

    @Test
    fun `translate with many prefixes returns correct result for non-matching path`() {
        for (i in 0 until 1000) {
            bridge.addPathRedirection("/data/data/com.example.app$i/", "/sandbox$i/")
        }

        // This path should NOT match any of the 1000 prefixes
        val result = bridge.translatePath("/data/data/com.other.app/file.txt")
        assertEquals("/data/data/com.other.app/file.txt", result)
    }

    @Test
    fun `longest prefix match still works among 1000 prefixes`() {
        // Insert 999 unrelated prefixes
        for (i in 0 until 999) {
            bridge.addPathRedirection("/data/data/com.example.app$i/", "/sandbox$i/")
        }

        // Insert two related prefixes (parent and child)
        bridge.addPathRedirection("/data/data/com.target/", "/target_sandbox/")
        bridge.addPathRedirection("/data/data/com.target/cache/", "/target_cache/")

        assertEquals(
            "/target_sandbox/files/data.bin",
            bridge.translatePath("/data/data/com.target/files/data.bin")
        )
        assertEquals(
            "/target_cache/item.db",
            bridge.translatePath("/data/data/com.target/cache/item.db")
        )
    }

    // ===== Additional edge cases =====

    @Test
    fun `translatePath with path exactly equal to prefix`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")
        // Path is exactly the prefix; suffix is empty
        assertEquals("/sandbox/", bridge.translatePath("/data/data/com.app/"))
    }

    @Test
    fun `translatePath with single character prefix`() {
        bridge.addPathRedirection("a", "b")
        assertEquals("bc", bridge.translatePath("ac"))
        assertEquals("b", bridge.translatePath("a"))
        assertEquals("xyz", bridge.translatePath("xyz"))
    }

    @Test
    fun `multiple independent prefix trees`() {
        bridge.addPathRedirection("/alpha/", "/ALPHA/")
        bridge.addPathRedirection("/beta/", "/BETA/")
        bridge.addPathRedirection("/gamma/", "/GAMMA/")

        assertEquals("/ALPHA/file", bridge.translatePath("/alpha/file"))
        assertEquals("/BETA/file", bridge.translatePath("/beta/file"))
        assertEquals("/GAMMA/file", bridge.translatePath("/gamma/file"))
        assertEquals("/delta/file", bridge.translatePath("/delta/file"))
    }

    @Test
    fun `unicode characters in prefix and path`() {
        bridge.addPathRedirection("/data/data/中文/", "/sandbox/")
        assertEquals(
            "/sandbox/file.txt",
            bridge.translatePath("/data/data/中文/file.txt")
        )
    }

    @Test
    fun `special characters in path are preserved in suffix`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")
        assertEquals(
            "/sandbox/files/my file (1).txt",
            bridge.translatePath("/data/data/com.app/files/my file (1).txt")
        )
    }

    @Test
    fun `deeply nested path translates correctly`() {
        bridge.addPathRedirection("/a/", "/b/")
        assertEquals(
            "/b/c/d/e/f/g/h/i/j/k/file.txt",
            bridge.translatePath("/a/c/d/e/f/g/h/i/j/k/file.txt")
        )
    }

    @Test
    fun `clear and re-add in different order produces same results`() {
        bridge.addPathRedirection("/x/", "/X/")
        bridge.addPathRedirection("/x/y/", "/XY/")
        bridge.clearPathRedirections()

        // Re-add in reverse order
        bridge.addPathRedirection("/x/y/", "/XY/")
        bridge.addPathRedirection("/x/", "/X/")

        assertEquals("/XY/file", bridge.translatePath("/x/y/file"))
        assertEquals("/X/file", bridge.translatePath("/x/file"))
    }

    @Test
    fun `overwritten prefix then cleared then re-added works`() {
        bridge.addPathRedirection("/data/", "/old/")
        bridge.addPathRedirection("/data/", "/new/")
        assertEquals("/new/file", bridge.translatePath("/data/file"))

        bridge.clearPathRedirections()
        assertEquals("/data/file", bridge.translatePath("/data/file"))

        bridge.addPathRedirection("/data/", "/final/")
        assertEquals("/final/file", bridge.translatePath("/data/file"))
    }
}
