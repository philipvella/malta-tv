package com.philipvella.maltatv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.philipvella.maltatv.adblock.AdBlocker
import com.philipvella.maltatv.adblock.BlocklistStore
import com.philipvella.maltatv.adblock.Hosts
import org.json.JSONArray
import org.json.JSONObject

/**
 * Malta TV — a web-based Android TV app.
 *
 * There is no native video player here: each "station" is simply a public web page,
 * opened in an embedded browser (WebView), exactly as you would open it in a browser.
 */
class MainActivity : Activity() {

    data class Station(val name: String, val url: String, val builtIn: Boolean)

    private val defaultStations = listOf(
        Station("TVM", "https://tvmi.mt/live/2", true),
        Station("NET", "https://netondemand.mt/", true),
        Station("ONE", "https://play.one.com.mt/tv", true),
        Station("SMASH", "https://smashmalta.com/", true),
    )

    private lateinit var webView: WebView
    private lateinit var controlBar: View
    private lateinit var urlBar: View
    private lateinit var urlText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var stationList: LinearLayout
    private lateinit var videoFullscreen: ViewGroup
    private lateinit var pointer: PointerOverlay

    private val prefs by lazy { getSharedPreferences("malta_tv", MODE_PRIVATE) }
    private var stations = mutableListOf<Station>()
    private var currentUrl: String? = null
    private var isFullscreen = false
    private val handler = Handler(Looper.getMainLooper())

    // "Auto full screen on channel change": retries until the page's player appears
    private var autoFsToken = 0
    private var autoFsPending = false
    private var autoFsAttempts = 0

    // HTML5 <video> fullscreen support
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        controlBar = findViewById(R.id.controlBar)
        urlBar = findViewById(R.id.urlBar)
        urlText = findViewById(R.id.urlText)
        progress = findViewById(R.id.progress)
        stationList = findViewById(R.id.stationList)
        videoFullscreen = findViewById(R.id.videoFullscreen)
        pointer = findViewById(R.id.pointer)

        setupWebView()
        setupPointer()

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { webView.reload() }
        findViewById<Button>(R.id.btnFullscreen).setOnClickListener { setFullscreen(true) }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { showAddStationDialog() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<View>(R.id.btnExit).setOnClickListener { confirmExit() }

        stations = loadStations()
        renderStations()

        val startUrl = savedInstanceState?.getString(KEY_URL)
            ?: prefs.getString(KEY_LAST_URL, null)?.takeIf { last -> stations.any { it.url == last } }
            ?: stations.first().url
        // Load the first page once the ad blocklist is ready (parsed off the main thread),
        // so its first requests are filtered too. Don't wait more than 3 s.
        var started = false
        val start = Runnable { if (!started && !isDestroyed) { started = true; openUrl(startUrl) } }
        AdBlocker.init(this) { start.run() }
        handler.postDelayed(start, 3000)

        if (savedInstanceState == null) {
            stationList.post { stationList.getChildAt(0)?.requestFocus() }
        }
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Chrome DevTools inspection (chrome://inspect) for debug builds only.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            // Runs on a WebView IO thread. Returns an empty response for blocked ad/tracker
            // requests and null (load normally) for everything else.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                AdBlocker.shouldInterceptRequest(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                // Stay inside the app for normal web links; ignore intent:, market:, etc.
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                AdBlocker.onPageStarting(AdBlocker.hostOf(url))
                urlText.text = "🌐 $url"
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                urlText.text = "🌐 $url"
                progress.visibility = View.INVISIBLE
                if (autoFsPending && autoFsAttempts == 0) tryAutoFullscreen(autoFsToken)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden(); return
                }
                customView = view
                customViewCallback = callback
                videoFullscreen.addView(view, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                videoFullscreen.visibility = View.VISIBLE
                hideSystemBars(true)
                pointer.show()
            }

            override fun onHideCustomView() {
                exitVideoFullscreen()
            }
        }
    }

    // ---------------------------------------------------------------- Pointer

    /** The pointer is active whenever the web page (or its fullscreen video) has the remote's focus. */
    private fun pointerActive() = customView != null || webView.hasFocus()

    private fun setupPointer() {
        pointer.host = object : PointerOverlay.Host {
            override fun pointerTarget(): View? = when {
                customView != null -> videoFullscreen
                webView.hasFocus() -> webView
                else -> null
            }

            @Suppress("DEPRECATION")
            override fun scrollAt(x: Float, y: Float, dy: Int, callback: (Boolean) -> Unit) {
                if (customView != null) { callback(true); return }
                val loc = IntArray(2).also { webView.getLocationOnScreen(it) }
                val ploc = IntArray(2).also { pointer.getLocationOnScreen(it) }
                val scale = webView.scale.takeIf { it > 0f } ?: resources.displayMetrics.density
                val cx = (x + ploc[0] - loc[0]) / scale
                val cy = (y + ploc[1] - loc[1]) / scale
                val cdy = dy / scale
                // Scroll the nearest scrollable element under the pointer, else the page itself.
                val js = """
                    (function(x,y,dy){
                      var e=document.elementFromPoint(x,y);
                      while(e&&e!==document.body&&e!==document.documentElement){
                        var s=getComputedStyle(e);
                        if((s.overflowY=='auto'||s.overflowY=='scroll')&&e.scrollHeight>e.clientHeight){
                          var b=e.scrollTop;e.scrollTop+=dy;if(e.scrollTop!==b)return true;
                        }
                        e=e.parentElement;
                      }
                      var w=window.scrollY;window.scrollBy(0,dy);return window.scrollY!==w;
                    })($cx,$cy,$cdy)
                """.trimIndent()
                webView.evaluateJavascript(js) { result -> callback(result == "true") }
            }

            override fun onPointerExitTop() {
                if (isFullscreen || customView != null) return
                pointer.hide()
                val active = (0 until stationList.childCount)
                    .map { stationList.getChildAt(it) }
                    .firstOrNull { it.isSelected } ?: stationList.getChildAt(0)
                active?.requestFocus()
            }
        }

        webView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) pointer.show() else if (customView == null) pointer.hide()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Remote colour buttons: RED = refresh, GREEN = toggle full screen
        when (event.keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    webView.reload()
                    toast("Refreshing…")
                }
                return true
            }
            // Remote next / previous (media keys and channel P+ / P-) step through the stations
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) switchStation(+1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) switchStation(-1)
                return true
            }
            KeyEvent.KEYCODE_PROG_GREEN -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (customView != null) exitVideoFullscreen() else setFullscreen(!isFullscreen)
                }
                return true
            }
        }
        if (pointerActive() && pointer.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** [channelChange] = the user picked a station (button or next/previous key). */
    private fun openUrl(url: String, channelChange: Boolean = false) {
        currentUrl = url
        prefs.edit().putString(KEY_LAST_URL, url).apply()
        autoFsToken++
        autoFsPending = channelChange && prefs.getBoolean(KEY_AUTO_FS, false)
        autoFsAttempts = 0
        webView.loadUrl(url)
        highlightActiveStation()
    }

    private fun switchStation(delta: Int) {
        if (customView != null) exitVideoFullscreen()
        val i = stations.indexOfFirst { it.url == currentUrl }
        val next = if (i < 0) 0 else Math.floorMod(i + delta, stations.size)
        val station = stations[next]
        openUrl(station.url, channelChange = true)
        toast(station.name)
        if (!isFullscreen) stationList.getChildAt(next)?.requestFocus()
    }

    // ---------------------------------------------------------------- Auto full screen

    /**
     * Best effort: finds the page's main video (or, if the player lives in another site's iframe,
     * the largest player-sized iframe), asks for real full screen, and — since browsers refuse
     * requestFullscreen() without a user gesture — also stretches it over the whole page.
     * Retries for ~15 s because players are usually inserted after the page has loaded.
     */
    private fun tryAutoFullscreen(token: Int) {
        if (token != autoFsToken || !autoFsPending) return
        autoFsAttempts++
        webView.evaluateJavascript(AUTO_FS_JS) { result ->
            if (token != autoFsToken) return@evaluateJavascript
            val found = result?.contains("VIDEO") == true || result?.contains("IFRAME") == true
            when {
                found -> {
                    autoFsPending = false
                    if (!isFullscreen && customView == null) setFullscreen(true, announce = false)
                }
                autoFsAttempts < 15 -> handler.postDelayed({ tryAutoFullscreen(token) }, 1000)
                else -> autoFsPending = false
            }
        }
    }

    // ---------------------------------------------------------------- Stations

    private fun loadStations(): MutableList<Station> {
        val list = defaultStations.toMutableList()
        val json = prefs.getString(KEY_CUSTOM, null) ?: return list
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += Station(o.getString("name"), o.getString("url"), false)
            }
        }
        return list
    }

    private fun saveCustomStations() {
        val arr = JSONArray()
        stations.filterNot { it.builtIn }.forEach {
            arr.put(JSONObject().put("name", it.name).put("url", it.url))
        }
        prefs.edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    private fun renderStations() {
        stationList.removeAllViews()
        stations.forEach { station ->
            val btn = Button(this, null, 0, R.style.BarButton).apply {
                text = if (station.builtIn) station.name else "★ ${station.name}"
                tag = station.url
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener { openUrl(station.url, channelChange = true) }
                if (!station.builtIn) {
                    setOnLongClickListener { confirmRemove(station); true }
                }
            }
            stationList.addView(btn)
        }
        highlightActiveStation()
    }

    private fun highlightActiveStation() {
        for (i in 0 until stationList.childCount) {
            val child = stationList.getChildAt(i)
            child.isSelected = child.tag == currentUrl
        }
    }

    private fun showAddStationDialog() {
        val pad = dp(20)
        val nameInput = EditText(this).apply {
            hint = "Name (e.g. My Channel)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        val urlInput = EditText(this).apply {
            hint = "Web address (e.g. https://example.com/live)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply {
                text = "Any web page can be a station. Enter its name and address."
            })
            addView(nameInput)
            addView(urlInput)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add a web station")
            .setView(form)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                var url = urlInput.text.toString().trim()
                if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                when {
                    name.isEmpty() -> nameInput.error = "Enter a name"
                    url.isEmpty() || android.util.Patterns.WEB_URL.matcher(url).matches().not() ->
                        urlInput.error = "Enter a valid web address"
                    else -> {
                        stations += Station(name, url, false)
                        saveCustomStations()
                        renderStations()
                        openUrl(url)
                        toast("Added \"$name\". Long-press it to remove.")
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
        nameInput.requestFocus()
    }

    private fun confirmRemove(station: Station) {
        AlertDialog.Builder(this)
            .setTitle("Remove station?")
            .setMessage("Remove \"${station.name}\" from your stations?")
            .setPositiveButton("Remove") { _, _ ->
                stations.remove(station)
                saveCustomStations()
                renderStations()
                stationList.getChildAt(0)?.requestFocus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- Settings

    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"

    private fun showSettings() {
        val site = AdBlocker.currentSite ?: AdBlocker.hostOf(currentUrl)
        val autoFs = prefs.getBoolean(KEY_AUTO_FS, false)
        val siteOff = site != null && AdBlocker.isSiteDisabled(site)
        val ex = AdBlocker.exceptions
        val sourceLabel = when (AdBlocker.source) {
            BlocklistStore.Source.DOWNLOADED -> "downloaded " +
                android.text.format.DateFormat.getDateFormat(this).format(java.util.Date(AdBlocker.updatedAtMillis))
            BlocklistStore.Source.BUNDLED -> "built-in copy"
            BlocklistStore.Source.NONE -> "not loaded yet"
        } + if (AdBlocker.loadMillis >= 0) ", loaded in %.1f s".format(AdBlocker.loadMillis / 1000.0) else ""

        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Auto full screen on channel change: ${onOff(autoFs)}" to {
            prefs.edit().putBoolean(KEY_AUTO_FS, !autoFs).apply()
            toast("Auto full screen ${onOff(!autoFs)}")
        }
        items += "Ad blocking (all sites): ${onOff(AdBlocker.enabled)}" to {
            AdBlocker.setEnabled(!AdBlocker.enabled); webView.reload()
        }
        if (site != null) {
            items += "Ad blocking on $site: ${onOff(!siteOff)}" to {
                AdBlocker.setSiteDisabled(site, !siteOff)
                toast(if (siteOff) "Ad blocking back ON for $site" else "Ad blocking OFF for $site")
                webView.reload()
            }
        }
        items += "Allow a blocked domain… (${AdBlocker.blockedCount} requests blocked on this page)" to {
            showAllowDomain()
        }
        items += "Manage ad-block exceptions (${ex.disabledSites.size + ex.allowedDomains.size})" to {
            showManageExceptions()
        }
        items += "Update blocklist now (${"%,d".format(AdBlocker.domainCount)} domains, $sourceLabel)" to {
            toast("Updating blocklist…")
            AdBlocker.updateNow { ok ->
                toast(if (ok) "Blocklist updated: ${"%,d".format(AdBlocker.domainCount)} domains"
                      else "Update failed — still using the current list")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Pick from hosts blocked on this page, or type one, to never block it on any site. */
    private fun showAllowDomain() {
        val recent = AdBlocker.recentBlocked()
        val entries = recent + "Type a domain…"
        AlertDialog.Builder(this)
            .setTitle(if (recent.isEmpty()) "Nothing blocked on this page" else "Always allow which domain?")
            .setItems(entries.toTypedArray()) { _, which ->
                if (which < recent.size) allowDomain(recent[which]) else typeDomainToAllow()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun typeDomainToAllow() {
        val input = EditText(this).apply {
            hint = "e.g. cdn.example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Always allow a domain")
            .setView(input)
            .setPositiveButton("Allow", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text.toString().trim().let { t -> AdBlocker.hostOf(if ("://" in t) t else "https://$t") }
                val domain = Hosts.normalizeDomainEntry(raw)
                if (domain == null) input.error = "Enter a domain like cdn.example.com"
                else { allowDomain(domain); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun allowDomain(domain: String) {
        AdBlocker.setDomainAllowed(domain, true)
        toast("$domain will no longer be blocked")
        webView.reload()
    }

    private fun showManageExceptions() {
        val ex = AdBlocker.exceptions
        val rows = ex.disabledSites.map { "Blocking off on site: $it" to { AdBlocker.setSiteDisabled(it, false) } } +
            ex.allowedDomains.map { "Always allowed: $it" to { AdBlocker.setDomainAllowed(it, false) } }
        if (rows.isEmpty()) { toast("No exceptions yet"); return }
        AlertDialog.Builder(this)
            .setTitle("Select an exception to remove")
            .setItems(rows.map { it.first }.toTypedArray()) { _, which ->
                rows[which].second()
                toast("Removed")
                webView.reload()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------------------------------------------------------- Full screen

    private fun setFullscreen(on: Boolean, announce: Boolean = true) {
        isFullscreen = on
        if (!on) webView.evaluateJavascript(UNDO_FILL_JS, null)
        val vis = if (on) View.GONE else View.VISIBLE
        controlBar.visibility = vis
        urlBar.visibility = vis
        hideSystemBars(on)
        if (on) {
            webView.requestFocus()
            if (announce) toast("Use the arrows to move the pointer, OK to click. BACK or MENU shows the controls")
            else toast("BACK shows the controls")
        } else {
            findViewById<View>(R.id.btnFullscreen).requestFocus()
        }
    }

    private fun exitVideoFullscreen() {
        val view = customView ?: return
        videoFullscreen.removeView(view)
        videoFullscreen.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        hideSystemBars(isFullscreen)
        if (webView.hasFocus()) pointer.show() else pointer.hide()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                if (hide) {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    it.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            window.decorView.systemUiVisibility = if (hide) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    // ---------------------------------------------------------------- Keys & exit

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            setFullscreen(!isFullscreen); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> webView.webChromeClient?.onHideCustomView()
            isFullscreen -> setFullscreen(false)
            webView.canGoBack() -> webView.goBack()
            else -> confirmExit()
        }
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Exit Malta TV?")
            .setPositiveButton("Exit") { _, _ -> finishAndRemoveTask() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- Lifecycle

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_URL, currentUrl)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    // WebView keeps playing media in the background; stop it when the app leaves the screen
    // (e.g. switching to another app), and resume when it comes back.
    override fun onStop() {
        super.onStop()
        webView.evaluateJavascript(PAUSE_MEDIA_JS, null)
        webView.pauseTimers()
    }

    override fun onStart() {
        super.onStart()
        webView.resumeTimers()
        webView.evaluateJavascript(RESUME_MEDIA_JS, null)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_CUSTOM = "custom_stations"
        private const val KEY_AUTO_FS = "auto_fullscreen"

        private const val AUTO_FS_JS = """
(function(){
  var C='mtv-fill';
  if(!document.getElementById('mtv-fill-style')){
    var s=document.createElement('style');s.id='mtv-fill-style';
    s.textContent='.mtv-fill{position:fixed!important;left:0!important;top:0!important;right:auto!important;bottom:auto!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;border:0!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important;transform:none!important}html.mtv-filled,html.mtv-filled body{overflow:hidden!important}.mtv-fill-anc{transform:none!important;filter:none!important;perspective:none!important;contain:none!important;will-change:auto!important;backdrop-filter:none!important;z-index:2147483647!important}';
    (document.head||document.documentElement).appendChild(s);
  }
  function size(e){var r=e.getBoundingClientRect();return {w:r.width,h:r.height,a:r.width*r.height};}
  var best=null,ba=0;
  document.querySelectorAll('video').forEach(function(v){var z=size(v);if(z.w>=200&&z.h>=100&&z.a>ba){ba=z.a;best=v;}});
  if(!best){
    document.querySelectorAll('iframe').forEach(function(f){var z=size(f);var r=z.w/z.h;
      if(z.w>=320&&z.h>=180&&r>1.2&&r<2.4&&z.a>ba){ba=z.a;best=f;}});
  }
  if(!best)return 'none';
  best.classList.add(C);document.documentElement.classList.add('mtv-filled');window.scrollTo(0,0);
  for(var a=best.parentElement;a&&a!==document.documentElement;a=a.parentElement){a.classList.add('mtv-fill-anc');}
  try{if(best.tagName==='VIDEO'&&best.paused){var p=best.play();if(p&&p.catch)p.catch(function(){});}}catch(e){}
  try{var q=best.requestFullscreen?best.requestFullscreen():(best.webkitRequestFullscreen?best.webkitRequestFullscreen():null);if(q&&q.catch)q.catch(function(){});}catch(e){}
  return best.tagName;
})()"""

        private const val PAUSE_MEDIA_JS = """
(function(){document.querySelectorAll('video,audio').forEach(function(m){
  if(!m.paused){m.setAttribute('data-mtv-resume','1');try{m.pause();}catch(e){}}});})()"""

        private const val RESUME_MEDIA_JS = """
(function(){document.querySelectorAll('[data-mtv-resume]').forEach(function(m){
  m.removeAttribute('data-mtv-resume');try{var p=m.play();if(p&&p.catch)p.catch(function(){});}catch(e){}});})()"""

        private const val UNDO_FILL_JS = """
(function(){document.querySelectorAll('.mtv-fill').forEach(function(e){e.classList.remove('mtv-fill');});
document.querySelectorAll('.mtv-fill-anc').forEach(function(e){e.classList.remove('mtv-fill-anc');});
document.documentElement.classList.remove('mtv-filled');})()"""
    }
}
