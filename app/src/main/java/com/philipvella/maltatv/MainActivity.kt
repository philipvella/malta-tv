package com.philipvella.maltatv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
        Station("Net Live", "https://netondemand.mt/", true),
        Station("TVM Live", "https://tvmi.mt/live/2", true),
        Station("ONE TV", "https://play.one.com.mt/tv", true),
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
        findViewById<Button>(R.id.btnExit).setOnClickListener { confirmExit() }

        stations = loadStations()
        renderStations()

        val startUrl = savedInstanceState?.getString(KEY_URL)
            ?: prefs.getString(KEY_LAST_URL, null)?.takeIf { last -> stations.any { it.url == last } }
            ?: stations.first().url
        openUrl(startUrl)

        if (savedInstanceState == null) {
            stationList.post { stationList.getChildAt(0)?.requestFocus() }
        }
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
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
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                // Stay inside the app for normal web links; ignore intent:, market:, etc.
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                urlText.text = "🌐 $url"
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                urlText.text = "🌐 $url"
                progress.visibility = View.INVISIBLE
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

    private fun openUrl(url: String) {
        currentUrl = url
        prefs.edit().putString(KEY_LAST_URL, url).apply()
        webView.loadUrl(url)
        highlightActiveStation()
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
                setOnClickListener { openUrl(station.url) }
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

    // ---------------------------------------------------------------- Full screen

    private fun setFullscreen(on: Boolean) {
        isFullscreen = on
        val vis = if (on) View.GONE else View.VISIBLE
        controlBar.visibility = vis
        urlBar.visibility = vis
        hideSystemBars(on)
        if (on) {
            webView.requestFocus()
            toast("Use the arrows to move the pointer, OK to click. BACK or MENU shows the controls")
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

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_CUSTOM = "custom_stations"
    }
}
