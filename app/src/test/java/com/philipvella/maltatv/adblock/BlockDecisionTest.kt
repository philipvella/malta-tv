package com.philipvella.maltatv.adblock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockDecisionTest {

    private val list = DomainMatcher.of(listOf("doubleclick.net", "ads.example.com", "cdn.adnet.com"))

    private fun block(
        req: String?, page: String? = "tvmi.mt", main: Boolean = false, enabled: Boolean = true,
        ex: AdblockExceptions = AdblockExceptions.NONE,
    ) = BlockDecision.shouldBlock(req, page, main, enabled, list, ex)

    @Test fun blocksListedThirdPartyRequests() {
        assertTrue(block("pubads.g.doubleclick.net"))
    }

    @Test fun neverBlocksTheMainFrame() {
        assertFalse(block("ads.example.com", main = true))
    }

    @Test fun globalSwitchOff() {
        assertFalse(block("doubleclick.net", enabled = false))
    }

    @Test fun unlistedRequestsAreAllowed() {
        assertFalse(block("tvmi.mt"))
        assertFalse(block(null))
    }

    @Test fun siteExceptionCoversSubdomainsOfTheSite() {
        val ex = AdblockExceptions(disabledSites = listOf("tvmi.mt"))
        assertFalse(block("doubleclick.net", page = "tvmi.mt", ex = ex))
        assertFalse(block("doubleclick.net", page = "www.tvmi.mt", ex = ex))
        // other sites still blocked
        assertTrue(block("doubleclick.net", page = "netondemand.mt", ex = ex))
        // a lookalike site is NOT covered by the exception
        assertTrue(block("doubleclick.net", page = "nottvmi.mt", ex = ex))
    }

    @Test fun allowedDomainCoversItsSubdomainsOnly() {
        val ex = AdblockExceptions(allowedDomains = listOf("cdn.adnet.com"))
        assertFalse(block("cdn.adnet.com", ex = ex))
        assertFalse(block("eu.cdn.adnet.com", ex = ex))
        assertTrue(block("doubleclick.net", ex = ex))
    }

    @Test fun exceptionsAreImmutableAndRemovable() {
        val a = AdblockExceptions.NONE.withSiteDisabled("tvmi.mt", true).withAllowedDomain("cdn.adnet.com", true)
        assertTrue(a.isSiteDisabled("tvmi.mt"))
        assertFalse(AdblockExceptions.NONE.isSiteDisabled("tvmi.mt"))
        val b = a.withSiteDisabled("tvmi.mt", false).withAllowedDomain("cdn.adnet.com", false)
        assertFalse(b.isSiteDisabled("tvmi.mt"))
        assertFalse(b.isAllowed("cdn.adnet.com"))
    }

    @Test fun extraBlockedDomainsAreBlockedEvenWhenNotInTheDownloadedList() {
        val extra = DomainMatcher.of(listOf("ads.netnews.com.mt"))
        fun b(req: String, ex: AdblockExceptions = AdblockExceptions.NONE) =
            BlockDecision.shouldBlock(req, "netondemand.mt", false, true, DomainMatcher.EMPTY, ex, extra)
        assertTrue(b("ads.netnews.com.mt"))
        assertTrue(b("cdn.ads.netnews.com.mt"))
        assertFalse(b("netnews.com.mt"))           // the news site itself is not blocked
        assertFalse(b("www.netnews.com.mt"))
        // exceptions still win
        assertFalse(b("ads.netnews.com.mt", AdblockExceptions(disabledSites = listOf("netondemand.mt"))))
        assertFalse(b("ads.netnews.com.mt", AdblockExceptions(allowedDomains = listOf("ads.netnews.com.mt"))))
    }

    @Test fun invalidExceptionEntriesAreIgnored() {
        val ex = AdblockExceptions(disabledSites = listOf("", "com", "*.x.com"), allowedDomains = listOf("||a.com^"))
        assertTrue(ex.disabledSites.isEmpty())
        assertTrue(ex.allowedDomains.isEmpty())
    }
}
