package com.philipvella.maltatv.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistParserTest {

    private fun parse(text: String) = BlocklistParser.parse(text.reader().buffered())

    @Test fun hostsFormat() {
        val r = parse(
            """
            # Title: test
            127.0.0.1 localhost
            ::1 ip6-localhost
            fe80::1%lo0 localhost
            255.255.255.255 broadcasthost
            0.0.0.0 0.0.0.0
            0.0.0.0 ads.example.com
            0.0.0.0 Tracker.Example.NET   # inline comment with a URL https://x.com/a?b=c
            127.0.0.1 one.com two.com
            ::  ipv6sink.com
            """.trimIndent()
        )
        assertEquals(listOf("ads.example.com", "tracker.example.net", "one.com", "two.com", "ipv6sink.com"), r.domains.toList())
    }

    @Test fun plainDomainsFormat() {
        val r = parse("ads.example.com\n\n# comment\nmetrics.example.org\n")
        assertEquals(listOf("ads.example.com", "metrics.example.org"), r.domains.toList())
    }

    @Test fun easyListRulesAreNotTreatedAsDomains() {
        val r = parse(
            """
            [Adblock Plus 2.0]
            ! Title: EasyList
            ||ads.example.com^
            ||tracker.com^${'$'}third-party
            @@||good.com^
            example.com##.ad-banner
            example.com#@#.sponsor
            /banner/*/ads.
            |https://ads.net/
            ads.example.com/path
            *.wildcard.com
            """.trimIndent()
        )
        assertTrue("got ${r.domains}", r.domains.isEmpty())
        assertTrue(r.skipped >= 9)
    }

    @Test fun hostsLinesPointingAtRealIpsAreSkipped() {
        val r = parse("93.184.216.34 example.com\n0.0.0.0 ads.com\n")
        assertEquals(listOf("ads.com"), r.domains.toList())
        assertEquals(1, r.skipped)
    }

    @Test fun invalidHostnamesAreSkipped() {
        val r = parse("0.0.0.0 -bad.com\n0.0.0.0 com\n0.0.0.0 ok.com\nbad_label-.com\n")
        assertEquals(listOf("ok.com"), r.domains.toList())
    }
}
