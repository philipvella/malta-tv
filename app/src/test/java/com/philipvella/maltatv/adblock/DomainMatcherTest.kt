package com.philipvella.maltatv.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {

    private val m = DomainMatcher.of(listOf("doubleclick.net", "ads.example.com", "tracker.co.uk", "10.0.0.1"))

    @Test fun exactDomainMatches() {
        assertTrue(m.matches("doubleclick.net"))
        assertTrue(m.matches("ads.example.com"))
    }

    @Test fun subdomainsMatch() {
        assertTrue(m.matches("pubads.g.doubleclick.net"))
        assertTrue(m.matches("x.ads.example.com"))
        assertTrue(m.matches("a.b.c.tracker.co.uk"))
    }

    @Test fun unrelatedDomainsSharingASuffixStringDoNotMatch() {
        assertFalse(m.matches("notdoubleclick.net"))
        assertFalse(m.matches("baddoubleclick.net"))
        assertFalse(m.matches("myads.example.com"))
    }

    @Test fun parentOfABlockedSubdomainIsNotBlocked() {
        assertFalse(m.matches("example.com"))
        assertFalse(m.matches("www.example.com"))
        assertFalse(m.matches("co.uk"))
    }

    @Test fun blockedNameAsPrefixOfAnotherDomainDoesNotMatch() {
        assertFalse(m.matches("doubleclick.net.evil.com"))
        assertFalse(m.matches("ads.example.com.mt"))
    }

    @Test fun matchingIsCaseInsensitiveAndIgnoresTrailingDot() {
        assertTrue(m.matches("PubAds.G.DoubleClick.NET"))
        assertTrue(m.matches("doubleclick.net."))
    }

    @Test fun ipLiteralsMatchExactlyOnly() {
        // IP entries are rejected by normalizeDomainEntry, so they never block anything
        assertFalse(m.matches("10.0.0.1"))
        assertFalse(m.matches("1.10.0.0.1"))
        assertFalse(m.matches("[::1]"))
    }

    @Test fun nullAndEmptyHostsNeverMatch() {
        assertFalse(m.matches(null))
        assertFalse(m.matches(""))
        assertFalse(DomainMatcher.EMPTY.matches("doubleclick.net"))
    }

    @Test fun invalidEntriesAreDropped() {
        val d = DomainMatcher.of(listOf("com", "localhost", "*.ads.com", "||ads.com^", "http://x.com/a", "good.com"))
        assertEquals(1, d.size)
        assertTrue(d.matches("good.com"))
        assertFalse(d.matches("anything.com"))
    }

    @Test fun normalizeDomainEntry() {
        assertEquals("cdn.example.com", Hosts.normalizeDomainEntry(" CDN.Example.com. "))
        assertEquals("xn--bcher-kva.example", Hosts.normalizeDomainEntry("xn--bcher-kva.example"))
        assertNull(Hosts.normalizeDomainEntry("example"))
        assertNull(Hosts.normalizeDomainEntry("-bad.com"))
        assertNull(Hosts.normalizeDomainEntry("1.2.3"))
        assertNull(Hosts.normalizeDomainEntry("a b.com"))
    }

    @Test fun isValidDomainEdgeCases() {
        assertTrue(Hosts.isValidDomain("a.co"))
        assertTrue(Hosts.isValidDomain("_dmarc.example.com"))
        assertTrue(Hosts.isValidDomain("123.example.com"))
        assertFalse(Hosts.isValidDomain("example.com-"))
        assertFalse(Hosts.isValidDomain("example_.com"))
        assertFalse(Hosts.isValidDomain("a..b.com"))
        assertFalse(Hosts.isValidDomain(".a.com"))
        assertFalse(Hosts.isValidDomain("1.2.3.4"))
        assertFalse(Hosts.isValidDomain("a".repeat(64) + ".com"))
        assertFalse(Hosts.isValidDomain("UPPER.com")) // callers normalise first
    }
}
