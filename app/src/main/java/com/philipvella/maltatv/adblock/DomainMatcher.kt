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

        /** For sets already produced by [BlocklistParser] (entries are validated there). */
        internal fun ofValidated(set: Set<String>): DomainMatcher = DomainMatcher(set)
    }
}

/** Host-name helpers shared by the parser, matcher and exception lists. */
object Hosts {
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
        return if (isValidDomain(h)) h else null
    }

    /**
     * Single-pass check (no regex/allocation; called ~80k times while loading the list):
     * at least two labels of 1-63 chars from [a-z0-9_-], labels don't start with '-' or end with
     * '-'/'_', and the last label isn't all digits (rejects IPs and partial IPs).
     */
    fun isValidDomain(h: String): Boolean {
        val n = h.length
        if (n == 0 || n > 253) return false
        var labelStart = 0
        var dots = 0
        var allDigits = true
        for (i in 0..n) {
            if (i == n || h[i] == '.') {
                val len = i - labelStart
                if (len == 0 || len > 63) return false
                if (h[labelStart] == '-') return false
                val last = h[i - 1]
                if (last == '-' || last == '_') return false
                if (i == n) return dots >= 1 && !allDigits
                dots++
                labelStart = i + 1
                allDigits = true
                continue
            }
            val c = h[i]
            if (c in '0'..'9') continue
            if (c in 'a'..'z' || c == '-' || c == '_') allDigits = false else return false
        }
        return false
    }
}
