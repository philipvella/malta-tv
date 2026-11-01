package com.philipvella.maltatv.adblock

/**
 * User-configurable exceptions. Immutable: every change produces a new instance, so request
 * threads always see a consistent snapshot without locking.
 *
 * - [disabledSites]: pages (the site in the address bar) on which blocking is turned off
 *   completely. `tvmi.mt` also covers `www.tvmi.mt`.
 * - [allowedDomains]: request hosts that are never blocked, on any site (e.g. a video CDN a
 *   player needs). `cdn.example.com` also covers `x.cdn.example.com`.
 */
class AdblockExceptions(
    disabledSites: Collection<String> = emptyList(),
    allowedDomains: Collection<String> = emptyList(),
) {
    val disabledSites: Set<String> = disabledSites.mapNotNull { Hosts.normalizeDomainEntry(it) }.toSortedSet()
    val allowedDomains: Set<String> = allowedDomains.mapNotNull { Hosts.normalizeDomainEntry(it) }.toSortedSet()

    private val disabledMatcher = DomainMatcher.of(this.disabledSites)
    private val allowedMatcher = DomainMatcher.of(this.allowedDomains)

    fun isSiteDisabled(pageHost: String?): Boolean = disabledMatcher.matches(pageHost)
    fun isAllowed(requestHost: String?): Boolean = allowedMatcher.matches(requestHost)

    fun withSiteDisabled(site: String, disabled: Boolean) =
        AdblockExceptions(if (disabled) disabledSites + site else disabledSites - site, allowedDomains)

    fun withAllowedDomain(domain: String, allowed: Boolean) =
        AdblockExceptions(disabledSites, if (allowed) allowedDomains + domain else allowedDomains - domain)

    companion object {
        val NONE = AdblockExceptions()
    }
}

/** The single decision used by both the WebView and the service-worker interceptors. */
object BlockDecision {
    fun shouldBlock(
        requestHost: String?,
        pageHost: String?,
        isMainFrame: Boolean,
        enabled: Boolean,
        blocklist: DomainMatcher,
        exceptions: AdblockExceptions,
    ): Boolean {
        if (!enabled || isMainFrame) return false          // never block the page the user opened
        if (requestHost == null) return false
        if (exceptions.isSiteDisabled(pageHost)) return false
        if (exceptions.isAllowed(requestHost)) return false
        return blocklist.matches(requestHost)
    }
}
