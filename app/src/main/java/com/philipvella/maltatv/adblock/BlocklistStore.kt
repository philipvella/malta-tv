package com.philipvella.maltatv.adblock

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Loads, caches and updates the blocklist. Plain java.io only (unit-testable on the JVM).
 *
 * Load order: a previously downloaded cache (if valid) -> the list bundled in the APK.
 * Updates are parsed and validated fully before the cache is atomically replaced, so a failed or
 * truncated download never replaces a working list.
 */
class BlocklistStore(
    private val cacheFile: File,
    private val bundled: () -> InputStream,
    private val minDomains: Int = DEFAULT_MIN_DOMAINS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    enum class Source { DOWNLOADED, BUNDLED, NONE }

    data class Loaded(val matcher: DomainMatcher, val source: Source, val updatedAtMillis: Long)

    /** Never throws. Falls back cache -> bundled -> empty. */
    fun loadInitial(): Loaded {
        if (cacheFile.isFile) {
            val m = runCatching { parse(cacheFile.inputStream()) }.getOrNull()
            if (m != null && m.size >= minDomains) {
                return Loaded(m, Source.DOWNLOADED, cacheFile.lastModified())
            }
            cacheFile.delete() // corrupt or too small: discard, use the bundled list
        }
        val b = runCatching { parse(bundled()) }.getOrNull()
        if (b != null && b.size >= minDomains) return Loaded(b, Source.BUNDLED, 0L)
        return Loaded(DomainMatcher.EMPTY, Source.NONE, 0L)
    }

    /** Age of the cached download, or null if there is none. */
    fun cacheAgeMillis(now: Long): Long? = if (cacheFile.isFile) now - cacheFile.lastModified() else null

    /**
     * Downloads via [fetch], validates and caches. Returns the new list, or null on any failure
     * (the caller keeps using its current list).
     */
    fun update(fetch: () -> InputStream): Loaded? {
        val matcher = try {
            parse(LimitedInputStream(fetch(), maxBytes))
        } catch (e: Exception) {
            return null
        }
        if (matcher.size < minDomains) return null

        val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        return try {
            cacheFile.parentFile?.mkdirs()
            tmp.bufferedWriter().use { w -> matcher.toSortedList().forEach { w.write(it); w.write("\n") } }
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.delete()
                if (!tmp.renameTo(cacheFile)) throw IOException("rename failed")
            }
            Loaded(matcher, Source.DOWNLOADED, cacheFile.lastModified())
        } catch (e: Exception) {
            tmp.delete()
            // The list itself is valid; use it for this session even if caching failed.
            Loaded(matcher, Source.DOWNLOADED, System.currentTimeMillis())
        }
    }

    private fun parse(input: InputStream): DomainMatcher =
        input.bufferedReader(Charsets.UTF_8).use { DomainMatcher.ofValidated(BlocklistParser.parse(it).domains) }

    /** Fails the read once more than [limit] bytes arrive, so a bad response can't exhaust memory. */
    class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) bump(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) bump(it.toLong()) }
        private fun bump(n: Long) {
            count += n
            if (count > limit) throw IOException("Blocklist larger than $limit bytes")
        }
    }

    companion object {
        const val DEFAULT_MIN_DOMAINS = 1_000
        const val DEFAULT_MAX_BYTES = 20L * 1024 * 1024
    }
}
