package com.philipvella.maltatv.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class BlocklistStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun hosts(vararg d: String) = d.joinToString("\n") { "0.0.0.0 $it" }
    private fun stream(s: String): InputStream = ByteArrayInputStream(s.toByteArray())
    private val bundledText = hosts("bundled-ads.com", "bundled-tracker.net")

    private fun store(cache: File = File(tmp.root, "adblock/blocklist.txt"), bundled: () -> InputStream = { stream(bundledText) }) =
        BlocklistStore(cache, bundled, minDomains = 2, maxBytes = 10_000)

    @Test fun usesBundledListWhenNoCache() {
        val l = store().loadInitial()
        assertEquals(BlocklistStore.Source.BUNDLED, l.source)
        assertTrue(l.matcher.matches("x.bundled-ads.com"))
    }

    @Test fun successfulUpdateIsCachedAndPreferredNextTime() {
        val s = store()
        val updated = s.update { stream(hosts("new-ads.com", "new-tracker.net", "third.org")) }
        assertNotNull(updated)
        assertTrue(updated!!.matcher.matches("new-ads.com"))

        val reloaded = store().loadInitial()
        assertEquals(BlocklistStore.Source.DOWNLOADED, reloaded.source)
        assertTrue(reloaded.matcher.matches("third.org"))
        assertFalse(reloaded.matcher.matches("bundled-ads.com"))
    }

    @Test fun networkFailureKeepsPreviousCache() {
        val s = store()
        s.update { stream(hosts("good-a.com", "good-b.com")) }
        assertNull(s.update { throw IOException("offline") })
        val l = store().loadInitial()
        assertEquals(BlocklistStore.Source.DOWNLOADED, l.source)
        assertTrue(l.matcher.matches("good-a.com"))
    }

    @Test fun suspiciouslySmallDownloadIsRejected() {
        val s = store()
        s.update { stream(hosts("good-a.com", "good-b.com")) }
        assertNull(s.update { stream("<html>Rate limited</html>") })
        assertTrue(store().loadInitial().matcher.matches("good-b.com"))
    }

    @Test fun oversizedDownloadIsRejected() {
        val huge = (0 until 2000).joinToString("\n") { "0.0.0.0 host$it.example.com" }
        assertNull(store().update { stream(huge) })
        assertEquals(BlocklistStore.Source.BUNDLED, store().loadInitial().source)
    }

    @Test fun downloadThatFailsMidStreamIsRejected() {
        val failing = object : InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 30) "0.0.0.0 a.com\n"[n % 14].code else throw IOException("reset")
        }
        assertNull(store().update { failing })
        assertFalse(File(tmp.root, "adblock/blocklist.txt").exists())
    }

    @Test fun corruptCacheFallsBackToBundled() {
        val cache = File(tmp.root, "adblock/blocklist.txt").apply { parentFile!!.mkdirs(); writeText("garbage\n") }
        val l = store(cache).loadInitial()
        assertEquals(BlocklistStore.Source.BUNDLED, l.source)
        assertFalse(cache.exists())
    }

    @Test fun missingBundledAndNoCacheGivesEmptyListWithoutThrowing() {
        val l = store(bundled = { throw IOException("asset missing") }).loadInitial()
        assertEquals(BlocklistStore.Source.NONE, l.source)
        assertEquals(0, l.matcher.size)
        assertFalse(l.matcher.matches("anything.com"))
    }

    @Test fun cacheAge() {
        val s = store()
        assertNull(s.cacheAgeMillis(System.currentTimeMillis()))
        s.update { stream(hosts("a1.com", "a2.com")) }
        assertNotNull(s.cacheAgeMillis(System.currentTimeMillis()))
    }
}
