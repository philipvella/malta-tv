package com.philipvella.maltatv.adblock

import java.io.BufferedReader

/**
 * Parses domain blocklists in either of the two common plain formats:
 *
 *  - hosts format:   `0.0.0.0 ads.example.com`  (also 127.0.0.1, ::, ::1 sinks; inline `#` comments)
 *  - domains format: `ads.example.com`
 *
 * AdBlock/EasyList filter syntax (`||ads.com^`, `@@...`, `example.com##.ad`, `$third-party`,
 * regexes, paths, wildcards) is NOT domain data and is skipped instead of being misread as a host.
 */
object BlocklistParser {

    data class Result(val domains: Set<String>, val skipped: Int)

    private val SINKS = setOf("0.0.0.0", "127.0.0.1", "::", "::0", "::1", "0")
    private val LOCAL_NAMES = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0",
    )
    // Markers that only appear in adblock-style filter rules.
    private val COSMETIC_MARKERS = listOf("##", "#@#", "#?#", "#$#", "#%#")
    private val NETWORK_MARKERS = listOf("|", "@@", "\$", "^", "/", "*", "=", "?")

    /** Returns validated, normalised domains (insertion-ordered, de-duplicated). */
    fun parse(reader: BufferedReader): Result {
        val out = LinkedHashSet<String>(200_000)
        var skipped = 0
        while (true) {
            val raw = reader.readLine() ?: break
            when (val r = parseLine(raw)) {
                null -> {}
                LineResult.Skipped -> skipped++
                is LineResult.Domains -> out.addAll(r.domains)
            }
        }
        return Result(out, skipped)
    }

    sealed class LineResult {
        object Skipped : LineResult()
        data class Domains(val domains: List<String>) : LineResult()
    }

    /** Whitespace split without regex (this runs for every line of an ~80k-line file). */
    private fun tokenize(line: String): List<String> {
        val out = ArrayList<String>(2)
        var start = -1
        for (i in 0..line.length) {
            val ws = i == line.length || line[i] == ' ' || line[i] == '\t'
            if (ws) {
                if (start >= 0) { out += line.substring(start, i); start = -1 }
            } else if (start < 0) start = i
        }
        return out
    }

    /** Returns null for blank/comment lines, Skipped for unsupported or invalid lines. */
    fun parseLine(raw: String): LineResult? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null
        if (trimmed.startsWith("[")) return LineResult.Skipped // "[Adblock Plus 2.0]" headers
        // Check cosmetic-filter markers BEFORE stripping "#" comments, so "example.com##.ad" isn't
        // truncated into the plain domain "example.com".
        if (COSMETIC_MARKERS.any { it in trimmed }) return LineResult.Skipped

        val line = trimmed.substringBefore('#').trim()
        if (line.isEmpty()) return null
        // Network-filter syntax, checked after the comment is removed (comments may hold URLs).
        if (NETWORK_MARKERS.any { it in line }) return LineResult.Skipped
        val tokens = tokenize(line)

        val candidates = when {
            tokens.size >= 2 && tokens[0] in SINKS -> tokens.drop(1)
            tokens.size == 1 -> tokens
            else -> return LineResult.Skipped // e.g. a hosts line pointing at a real IP
        }
        val domains = candidates
            .filter { it.lowercase() !in LOCAL_NAMES }
            .mapNotNull { Hosts.normalizeDomainEntry(it) }
        return if (domains.isEmpty()) LineResult.Skipped else LineResult.Domains(domains)
    }
}
