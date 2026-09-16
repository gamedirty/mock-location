package com.sideproject.mocklocation

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * 地图选点。
 *
 * 底图有两套坐标系，选哪套都由用户的底图决定，本应用内部始终只认 WGS-84：
 *   高德（GCJ-02）—— 国内直连快、中文标注，选完自动换算回 WGS-84
 *   OSM（WGS-84）—— 原生无偏移，国外或需要精确对应时用
 * 瓦片由 assets/map.html 里的手写 slippy map 直接加载，不依赖任何在线 JS 库。
 */
class MapPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
    }

    private lateinit var web: WebView
    private lateinit var topBar: View
    private lateinit var bottomPanel: View
    private lateinit var tvMapInfo: TextView
    private lateinit var tvPicked: TextView
    private lateinit var tvPickedHint: TextView
    private lateinit var tvZoom: TextView
    private lateinit var btnSourceGcj: TextView
    private lateinit var btnSourceWgs: TextView

    private var source = "gcj"
    private var zoom = 16
    private var originLat = 39.904200
    private var originLng = 116.407400
    private var picked: Waypoint? = null

    /** 暴露给 map.html 的回调，注意方法名和 JS 里调用的一致 */
    inner class Bridge {
        @JavascriptInterface
        fun onPick(lat: Double, lng: Double) {
            runOnUiThread {
                val wgs = fromMapDatum(lat, lng)
                if (!Geo.isValidLat(wgs.lat) || !Geo.isValidLng(wgs.lng)) return@runOnUiThread
                picked = wgs
                updatePickedUi()
            }
        }

        @JavascriptInterface
        fun onZoom(z: Int) {
            runOnUiThread {
                zoom = z
                updateZoomUi()
            }
        }

        @JavascriptInterface
        fun onInfo(text: String) {
            runOnUiThread { tvMapInfo.text = text }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_picker)

        originLat = intent.getDoubleExtra(EXTRA_LAT, originLat)
        originLng = intent.getDoubleExtra(EXTRA_LNG, originLng)
        if (!Geo.isValidLat(originLat) || !Geo.isValidLng(originLng)) {
            originLat = 39.904200
            originLng = 116.407400
        }
        source = Prefs.mapSource(this)
        picked = Waypoint(originLat, originLng)

        web = findViewById(R.id.webView)
        topBar = findViewById(R.id.topBar)
        bottomPanel = findViewById(R.id.bottomPanel)
        tvMapInfo = findViewById(R.id.tvMapInfo)
        tvPicked = findViewById(R.id.tvPicked)
        tvPickedHint = findViewById(R.id.tvPickedHint)
        tvZoom = findViewById(R.id.tvZoom)
        btnSourceGcj = findViewById(R.id.btnSourceGcj)
        btnSourceWgs = findViewById(R.id.btnSourceWgs)

        btnSourceGcj.setOnClickListener { switchSource("gcj") }
        btnSourceWgs.setOnClickListener { switchSource("wgs") }
        findViewById<TextView>(R.id.btnCancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        findViewById<TextView>(R.id.btnConfirm).setOnClickListener {
            val p = picked ?: return@setOnClickListener
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_LAT, p.lat).putExtra(EXTRA_LNG, p.lng),
            )
            finish()
        }

        setupWebView()
        updateSourceChips()
        updateZoomUi()
        updatePickedUi()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mediaPlaybackRequiresUserGesture = true

        web.setBackgroundColor(Color.parseColor("#0B0F14"))
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pushInit()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    tvMapInfo.text = "地图页面加载失败：${error?.description ?: ""}"
                }
            }
        }
        web.loadUrl("file:///android_asset/map.html")
    }

    /** 把中心点注入地图（注意传的是底图自己的坐标系），并告知上下工具条挡住多少 */
    private fun pushInit() {
        val m = toMapDatum(Waypoint(originLat, originLng))
        val d = resources.displayMetrics.density
        val insetTop = (if (topBar.height > 0) topBar.height else dp(56)) / d
        val insetBottom = (if (bottomPanel.height > 0) bottomPanel.height else dp(170)) / d
        val js = String.format(
            Locale.US,
            "init(%.8f, %.8f, %d, '%s', %.1f, %.1f)",
            m[0], m[1], zoom, source, insetTop, insetBottom,
        )
        web.evaluateJavascript(js, null)
        // 底部面板量完高度后再校正一次（面板高度会随提示文案换行变化）
        web.postDelayed({ if (!isFinishing) pushInsets() }, 320)
    }

    private fun pushInsets() {
        val d = resources.displayMetrics.density
        val top = (if (topBar.height > 0) topBar.height else dp(56)) / d
        val bottom = (if (bottomPanel.height > 0) bottomPanel.height else dp(170)) / d
        web.evaluateJavascript(String.format(Locale.US, "setInsets(%.1f, %.1f)", top, bottom), null)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun switchSource(next: String) {
        if (next == source) return
        val keep = picked ?: Waypoint(originLat, originLng)
        source = next
        Prefs.saveMapSource(this, next)
        originLat = keep.lat
        originLng = keep.lng
        updateSourceChips()
        tvMapInfo.text = "正在切换底图…"
        pushInit()
    }

    private fun toMapDatum(p: Waypoint): DoubleArray =
        if (source == "gcj") CoordTransform.wgs84ToGcj02(p.lat, p.lng)
        else doubleArrayOf(p.lat, p.lng)

    private fun fromMapDatum(lat: Double, lng: Double): Waypoint =
        if (source == "gcj") {
            val w = CoordTransform.gcj02ToWgs84(lat, lng)
            Waypoint(w[0], w[1])
        } else {
            Waypoint(lat, lng)
        }

    private fun updateSourceChips() {
        btnSourceGcj.isSelected = source == "gcj"
        btnSourceWgs.isSelected = source == "wgs"
    }

    private fun updateZoomUi() {
        val px = Geo.metersPerPixel(picked?.lat ?: originLat, zoom)
        tvZoom.text = "z$zoom · ${String.format(Locale.US, "%.1f", px)} m/px"
    }

    private fun updatePickedUi() {
        val p = picked ?: return
        tvPicked.text = "${Geo.fmt(p.lat)}, ${Geo.fmt(p.lng)}"
        val note = if (source == "gcj") {
            val gcj = toMapDatum(p)
            val offset = Geo.distance(p, Waypoint(gcj[0], gcj[1]))
            "高德底图坐标为 GCJ-02，已换算成 WGS-84 使用（本次偏移 ${Geo.fmtDelta(offset)}）"
        } else {
            "OSM 底图本身就是 WGS-84，无需换算"
        }
        tvPickedHint.text = "上面这行就是写入模拟位置的坐标（WGS-84）。$note"
        updateZoomUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }
}
