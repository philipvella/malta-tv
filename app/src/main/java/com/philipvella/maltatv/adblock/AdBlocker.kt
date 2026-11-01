package com.philipvella.maltatv.adblock

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.philipvella.maltatv.BuildConfig
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Request-level ad/tracker blocking for the app's WebView (and its service workers).
 *
 * Threading: [shouldInterceptRequest] runs on WebView's IO threads. Everything it reads is an
 * immutable snapshot published through @Volatile fields; list loading/parsing/downloading happens
 * on a dedicated background executor, never inside a request callback.
 */
object AdBlocker {

    /** StevenBlack unified hosts (adware + malware), MIT licensed. Same list bundled in assets. */
    const val LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val BUNDLED_ASSET = "adblock/hosts.txt"
    private const val UPDATE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    private const val TAG = "AdBlocker"

    /**
     * Ad servers seen on the built-in stations that the downloaded list doesn't cover.
     * Kept separate so list updates never drop them; site/domain exceptions still apply.
     */
    val EXTRA_BLOCKED: DomainMatcher = DomainMatcher.of(listOf(
        "ads.netnews.com.mt", // NET (netondemand.mt) ad server
    ))

    private const val PREFS = "adblock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DISABLED_SITES = "disabled_sites"
    private const val KEY_ALLOWED_DOMAINS = "allowed_domains"

    @Volatile private var matcher: DomainMatcher = DomainMatcher.EMPTY
    @Volatile var source: BlocklistStore.Source = BlocklistStore.Source.NONE; private set
    @Volatile var updatedAtMillis: Long = 0L; private set
    /** How long the initial list load took (shown in Settings as a diagnostic). */
    @Volatile var loadMillis: Long = -1L; private set
    @Volatile var exceptions: AdblockExceptions = AdblockExceptions.NONE; private set
    @Volatile var enabled: Boolean = true; private set
    @Volatile private var pageHost: String? = null

    private val blockedOnPage = AtomicInteger()
    private val recentBlockedHosts = LinkedHashSet<String>() // guarded by itself; hosts only
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "adblock").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var store: BlocklistStore? = null
    private var appContext: Context? = null

    val domainCount: Int get() = matcher.size
    val blockedCount: Int get() = blockedOnPage.get()
    val currentSite: String? get() = pageHost

    /**
     * Loads exceptions immediately and the blocklist in the background; [onReady] runs on the main
     * thread once the list is usable (or loading failed and we continue without blocking).
     */
    fun init(context: Context, onReady: () -> Unit) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, true)
        exceptions = AdblockExceptions(
            prefs.getStringSet(KEY_DISABLED_SITES, emptySet()).orEmpty(),
            prefs.getStringSet(KEY_ALLOWED_DOMAINS, emptySet()).orEmpty(),
        )
        installServiceWorkerClient()

        if (store != null) { main.post(onReady); return }
        val s = BlocklistStore(
            cacheFile = File(ctx.filesDir, "adblock/blocklist.txt"),
            bundled = { ctx.assets.open(BUNDLED_ASSET) },
        )
        store = s
        executor.execute {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val loaded = s.loadInitial()
            publish(loaded)
            loadMillis = android.os.SystemClock.elapsedRealtime() - t0
            debug("Loaded ${loaded.matcher.size} domains from ${loaded.source}")
            main.post(onReady)
            val age = s.cacheAgeMillis(System.currentTimeMillis())
            if (age == null || age > UPDATE_INTERVAL_MS) updateNow(null)
        }
    }

    /** Downloads a fresh list in the background. [onDone] (main thread) gets success/failure. */
    fun updateNow(onDone: ((Boolean) -> Unit)?) {
        val s = store ?: return
        executor.execute {
            val result = s.update { download(LIST_URL) }
            if (result != null) publish(result)
            debug(if (result != null) "Updated: ${result.matcher.size} domains" else "Update failed; keeping current list")
            onDone?.let { cb -> main.post { cb(result != null) } }
        }
    }

    private fun publish(loaded: BlocklistStore.Loaded) {
        matcher = loaded.matcher
        source = loaded.source
        updatedAtMillis = loaded.updatedAtMillis
    }

    // ------------------------------------------------------------- request interception

    /** Call from WebViewClient.shouldInterceptRequest. Null = let WebView load it normally. */
    fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
        val host = request.url?.host
        if (request.isForMainFrame) {
            onPageStarting(host)
            return null
        }
        return decide(host, isMainFrame = false)
    }

    /** Main thread: keep the "current site" accurate for back/forward and cached navigations. */
    fun onPageStarting(host: String?) {
        val h = Hosts.normalize(host)
        if (h != pageHost) {
            pageHost = h
            blockedOnPage.set(0)
            synchronized(recentBlockedHosts) { recentBlockedHosts.clear() }
        }
    }

    private fun decide(host: String?, isMainFrame: Boolean): WebResourceResponse? {
        val block = BlockDecision.shouldBlock(host, pageHost, isMainFrame, enabled, matcher, exceptions, EXTRA_BLOCKED)
        if (!block) return null
        blockedOnPage.incrementAndGet()
        val h = Hosts.normalize(host)!!
        synchronized(recentBlockedHosts) {
            recentBlockedHosts.remove(h)
            recentBlockedHosts.add(h)
            while (recentBlockedHosts.size > 50) recentBlockedHosts.remove(recentBlockedHosts.first())
        }
        debug("Blocked host: $h") // host only: never paths, queries or credentials
        return emptyResponse()
    }

    private fun emptyResponse() = WebResourceResponse(
        "text/plain", "utf-8", 200, "OK",
        mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(ByteArray(0)),
    )

    /** Hosts blocked on the current page, most recent first (for the "allow a domain" picker). */
    fun recentBlocked(): List<String> = synchronized(recentBlockedHosts) { recentBlockedHosts.toList() }.asReversed()

    /**
     * Service workers fetch outside any WebView, and ServiceWorkerController is app-wide (one
     * client for all workers). This app has a single WebView, so the current page's site is used
     * for site exceptions — a worker from a previously visited site may be judged against the
     * current site. Worker requests are never treated as main-frame.
     */
    private fun installServiceWorkerClient() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    decide(request.url?.host, isMainFrame = false)
            })
        }.onFailure { debug("Service worker interception unavailable") }
    }

    // ------------------------------------------------------------- configuration

    fun setEnabled(on: Boolean) { enabled = on; save() }

    fun isSiteDisabled(site: String?) = exceptions.isSiteDisabled(site)

    fun setSiteDisabled(site: String, disabled: Boolean) {
        exceptions = exceptions.withSiteDisabled(site, disabled); save()
    }

    fun setDomainAllowed(domain: String, allowed: Boolean) {
        exceptions = exceptions.withAllowedDomain(domain, allowed); save()
    }

    private fun save() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putStringSet(KEY_DISABLED_SITES, HashSet(exceptions.disabledSites))
            .putStringSet(KEY_ALLOWED_DOMAINS, HashSet(exceptions.allowedDomains))
            .apply()
    }

    // ------------------------------------------------------------- download

    private fun download(url: String): java.io.InputStream {
        // Default HttpsURLConnection: system TLS validation is untouched.
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "MaltaTV-Android")
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw java.io.IOException("HTTP ${conn.responseCode}")
        }
        return conn.inputStream
    }

    private fun debug(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    fun hostOf(url: String?): String? = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
}
