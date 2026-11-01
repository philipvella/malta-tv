package com.philipvella.maltatv.adblock

import java.util.Locale

/**
 * Immutable, thread-safe set of blocked domains with subdomain matching.
 *
 * An entry `example.com` matches `example.com` and every subdomain such as `a.b.example.com`,
 * but never unrelated hosts that merely share a suffix string (`badexample.com`) or a prefix
 * (`example.com.evil.net`). Matching walks the host's label boundaries, so a lookup costs at most
 * one hash lookup per label.
 */
class DomainMatcher private constructor(private val domains: Set<String>) {

    val size: Int get() = domains.size

    fun matches(host: String?): Boolean {
        if (domains.isEmpty()) return false
        val h = Hosts.normalize(host) ?: return false
        // IP literals only match exactly; walking "1.2.3.4" label-by-label would be meaningless.
        if (Hosts.isIpLiteral(h)) return h in domains
        var candidate = h
        while (true) {
            if (candidate in domains) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    /** Domains as a sorted list (for persisting a normalised cache). */
    fun toSortedList(): List<String> = domains.sorted()

    companion object {
        val EMPTY = DomainMatcher(emptySet())

        /** Builds a matcher; entries are normalised and invalid ones dropped. */
        fun of(entries: Collection<String>): DomainMatcher {
            val set = HashSet<String>(entries.size * 2)
            for (e in entries) Hosts.normalizeDomainEntry(e)?.let { set += it }
            return DomainMatcher(set)
        }
    }
}

/** Host-name helpers shared by the parser, matcher and exception lists. */
object Hosts {
    private val LABEL = Regex("^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9])?$")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** Lower-cases, trims, strips a trailing dot and IPv6 brackets. Null if empty. */
    fun normalize(host: String?): String? {
        if (host == null) return null
        var h = host.trim().lowercase(Locale.ROOT)
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
        h = h.trimEnd('.')
        return h.ifEmpty { null }
    }

    fun isIpLiteral(host: String): Boolean = ':' in host || IPV4.matches(host)

    /**
     * Normalises a user- or list-supplied domain entry. Returns null for anything that is not a
     * plain multi-label host name (so `com`, `localhost`, URLs, wildcards and adblock syntax are
     * all rejected rather than being misinterpreted).
     */
    fun normalizeDomainEntry(entry: String?): String? {
        val h = normalize(entry) ?: return null
        if (h.length > 253 || '.' !in h || Hosts.isIpLiteral(h)) return null
        val labels = h.split('.')
        if (labels.any { !LABEL.matches(it) }) return null
        // Reject all-numeric TLD (e.g. partial IPs like "1.2.3")
        if (labels.last().all { it.isDigit() }) return null
        return h
    }
}
