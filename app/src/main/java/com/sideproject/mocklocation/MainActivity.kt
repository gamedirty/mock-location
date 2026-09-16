package com.sideproject.mocklocation

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    // ---- 视图 ----
    private lateinit var dotStatus: View
    private lateinit var tvStatus: TextView
    private lateinit var tvProvider: TextView
    private lateinit var tvCoords: TextView
    private lateinit var tvMeta: TextView
    private lateinit var bannerSetup: View
    private lateinit var tvBanner: TextView
    private lateinit var btnDevOptions: TextView

    private lateinit var tvSelfTest: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var btnSelfTest: TextView
    private lateinit var btnCopyDiag: TextView
    private lateinit var btnLocSettings: TextView

    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var etAcc: EditText
    private lateinit var etAlt: EditText

    private lateinit var btnMap: TextView
    private lateinit var btnHere: TextView
    private lateinit var btnPaste: TextView
    private lateinit var btnCopy: TextView

    private lateinit var stepChips: List<TextView>
    private lateinit var tvStepCenter: TextView

    private lateinit var tvRouteStart: TextView
    private lateinit var tvRouteEnd: TextView
    private lateinit var btnRouteStartHere: TextView
    private lateinit var btnRouteEndHere: TextView
    private lateinit var seekSpeed: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var chipLoop: TextView
    private lateinit var btnRouteGo: TextView
    private lateinit var btnRouteStop: TextView
    private lateinit var tvRouteInfo: TextView

    private lateinit var presetList: LinearLayout
    private lateinit var tvPresetEmpty: TextView
    private lateinit var btnSavePreset: TextView

    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnClearLog: TextView

    private lateinit var btnToggle: TextView

    // ---- 状态 ----
    private val steps = doubleArrayOf(1.0, 10.0, 100.0, 1000.0)
    private val stepLabels = arrayOf("1 m", "10 m", "100 m", "1 km")
    private var stepIndex = 1
    private var routeStart: Waypoint? = null
    private var routeEnd: Waypoint? = null
    private val logLines = ArrayDeque<String>()
    private val ui = Handler(Looper.getMainLooper())
    private var lastRunning = false

    private val stateListener: (MockState) -> Unit = { st -> render(st) }

    // ------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        restoreState()
        wireListeners()
        appendLog("就绪。当前目标 ${Geo.fmt(MockEngine.state.lat)}, ${Geo.fmt(MockEngine.state.lng)}")
        requestPermissionsIfNeeded()
        refreshBanner()
        renderPresets()
    }

    override fun onStart() {
        super.onStart()
        MockEngine.addListener(stateListener)
    }

    override fun onStop() {
        super.onStop()
        MockEngine.removeListener(stateListener)
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统设置里授权回来
        refreshBanner()
    }

    // ------------------------------------------------------------ 绑定

    private fun bindViews() {
        dotStatus = findViewById(R.id.dotStatus)
        tvStatus = findViewById(R.id.tvStatus)
        tvProvider = findViewById(R.id.tvProvider)
        tvCoords = findViewById(R.id.tvCoords)
        tvMeta = findViewById(R.id.tvMeta)
        bannerSetup = findViewById(R.id.bannerSetup)
        tvBanner = findViewById(R.id.tvBanner)
        btnDevOptions = findViewById(R.id.btnDevOptions)

        tvSelfTest = findViewById(R.id.tvSelfTest)
        tvVerdict = findViewById(R.id.tvVerdict)
        btnSelfTest = findViewById(R.id.btnSelfTest)
        btnCopyDiag = findViewById(R.id.btnCopyDiag)
        btnLocSettings = findViewById(R.id.btnLocSettings)

        etLat = findViewById(R.id.etLat)
        etLng = findViewById(R.id.etLng)
        etAcc = findViewById(R.id.etAcc)
        etAlt = findViewById(R.id.etAlt)

        btnMap = findViewById(R.id.btnMap)
        btnHere = findViewById(R.id.btnHere)
        btnPaste = findViewById(R.id.btnPaste)
        btnCopy = findViewById(R.id.btnCopy)

        stepChips = listOf(
            findViewById(R.id.chipStep0),
            findViewById(R.id.chipStep1),
            findViewById(R.id.chipStep2),
            findViewById(R.id.chipStep3),
        )
        tvStepCenter = findViewById(R.id.tvStepCenter)

        tvRouteStart = findViewById(R.id.tvRouteStart)
        tvRouteEnd = findViewById(R.id.tvRouteEnd)
        btnRouteStartHere = findViewById(R.id.btnRouteStartHere)
        btnRouteEndHere = findViewById(R.id.btnRouteEndHere)
        seekSpeed = findViewById(R.id.seekSpeed)
        tvSpeed = findViewById(R.id.tvSpeed)
        chipLoop = findViewById(R.id.chipLoop)
        btnRouteGo = findViewById(R.id.btnRouteGo)
        btnRouteStop = findViewById(R.id.btnRouteStop)
        tvRouteInfo = findViewById(R.id.tvRouteInfo)

        presetList = findViewById(R.id.presetList)
        tvPresetEmpty = findViewById(R.id.tvPresetEmpty)
        btnSavePreset = findViewById(R.id.btnSavePreset)

        tvLog = findViewById(R.id.tvLog)
        logScroll = findViewById(R.id.logScroll)
        btnClearLog = findViewById(R.id.btnClearLog)

        btnToggle = findViewById(R.id.btnToggle)
    }

    private fun restoreState() {
        val running = MockEngine.state.running
        val p = if (running) Waypoint(MockEngine.state.lat, MockEngine.state.lng)
        else Prefs.target(this) ?: Waypoint(39.904200, 116.407400)
        etLat.setText(Geo.fmt(p.lat))
        etLng.setText(Geo.fmt(p.lng))
        etAcc.setText(trimNum(Prefs.accuracy(this).toDouble()))
        etAlt.setText(trimNum(Prefs.altitude(this)))

        MockEngine.setAccuracy(Prefs.accuracy(this).coerceIn(1f, 500f))
        MockEngine.setAltitude(Prefs.altitude(this))
        if (!running && !MockEngine.isTicking()) {
            MockEngine.setTarget(this, p.lat, p.lng)
        }

        stepIndex = Prefs.stepIndex(this).coerceIn(0, steps.size - 1)
        refreshStepUi()

        routeStart = Prefs.routePoint(this, true)
        routeEnd = Prefs.routePoint(this, false)
        refreshRouteUi()

        val speed = Prefs.routeSpeed(this).coerceIn(0.1f, 30f)
        seekSpeed.max = 300
        seekSpeed.progress = (speed * 10).roundToInt().coerceIn(1, 300)
        updateSpeedLabel()
        setLoopChip(Prefs.routeLoop(this))
    }

    private fun wireListeners() {
        btnToggle.setOnClickListener { if (MockEngine.state.running) stopMock() else startMock() }
        btnDevOptions.setOnClickListener { openDevOptions() }

        btnMap.setOnClickListener {
            val t = readFields() ?: return@setOnClickListener
            mapPicker.launch(
                Intent(this, MapPickerActivity::class.java)
                    .putExtra(MapPickerActivity.EXTRA_LAT, t.point.lat)
                    .putExtra(MapPickerActivity.EXTRA_LNG, t.point.lng),
            )
        }
        btnHere.setOnClickListener { fetchRealLocation() }
        btnPaste.setOnClickListener { pasteFromClipboard() }
        btnCopy.setOnClickListener { copyCoords() }

        stepChips.forEachIndexed { i, chip ->
            chip.setOnClickListener {
                stepIndex = i
                Prefs.saveStepIndex(this, i)
                refreshStepUi()
            }
        }

        bindHold(findViewById(R.id.btnNW), -1.0, 1.0)
        bindHold(findViewById(R.id.btnN), 0.0, 1.0)
        bindHold(findViewById(R.id.btnNE), 1.0, 1.0)
        bindHold(findViewById(R.id.btnW), -1.0, 0.0)
        bindHold(findViewById(R.id.btnE), 1.0, 0.0)
        bindHold(findViewById(R.id.btnSW), -1.0, -1.0)
        bindHold(findViewById(R.id.btnS), 0.0, -1.0)
        bindHold(findViewById(R.id.btnSE), 1.0, -1.0)

        btnRouteStartHere.setOnClickListener {
            readFields()?.let { setRoutePoint(true, it.point) }
        }
        btnRouteEndHere.setOnClickListener {
            readFields()?.let { setRoutePoint(false, it.point) }
        }
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress < 1) sb?.progress = 1
                updateSpeedLabel()
                Prefs.saveRouteSpeed(this@MainActivity, currentSpeed().toFloat())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        chipLoop.setOnClickListener {
            val on = !chipLoop.isSelected
            setLoopChip(on)
            Prefs.saveRouteLoop(this, on)
        }
        btnRouteGo.setOnClickListener { startRoute() }
        btnRouteStop.setOnClickListener {
            MockEngine.stopRoute()
            appendLog("巡航已停止（仍停留在最后一个点）")
        }

        btnSavePreset.setOnClickListener { savePresetDialog() }
        btnClearLog.setOnClickListener {
            logLines.clear()
            tvLog.text = ""
        }

        btnSelfTest.setOnClickListener { runSelfTest() }
        btnCopyDiag.setOnClickListener { copyDiagnostics() }
        btnLocSettings.setOnClickListener { openLocationSettings() }
    }

    // ------------------------------------------------------------ 渲染

    private fun render(st: MockState) {
        tvCoords.text = "${Geo.fmt(st.lat)}, ${Geo.fmt(st.lng)}"

        if (st.running) {
            tvStatus.text = "模拟中"
            setDot(R.color.accent)
        } else {
            tvStatus.text = "未开启模拟"
            setDot(R.color.text_dim)
        }

        val prov = if (st.providers.isEmpty()) "—" else st.providers.joinToString(", ")
        val failed = if (st.failedProviders.isEmpty()) "" else "　失败: ${st.failedProviders.joinToString(",")}"
        tvProvider.text = "provider $prov$failed"

        val meta = buildString {
            append("精度 ±").append(st.accuracy.toInt()).append(" m")
            append("　海拔 ").append(st.altitude.roundToInt()).append(" m")
            if (st.routeActive) {
                append("　速度 ").append(String.format(Locale.US, "%.1f", st.speedMps)).append(" m/s")
                append("　方向 ").append(st.bearing.roundToInt()).append("°")
            }
            if (st.error != null) append("\n⚠ ").append(st.error)
        }
        tvMeta.text = meta

        if (st.running != lastRunning) {
            lastRunning = st.running
            if (st.running) {
                btnToggle.text = "停止模拟"
                btnToggle.setBackgroundResource(R.drawable.bg_btn_danger)
                btnToggle.setTextColor(ContextCompat.getColor(this, R.color.danger))
            } else {
                btnToggle.text = "开始模拟"
                btnToggle.setBackgroundResource(R.drawable.bg_btn_primary)
                btnToggle.setTextColor(ContextCompat.getColor(this, R.color.ink))
            }
        }

        // 巡航进度
        if (st.routeActive) {
            val (total, walked, _) = MockEngine.routeInfo()
            tvRouteInfo.text = "路径全长 ${Geo.fmtDelta(total)}　已走 ${Geo.fmtDelta(walked)}" +
                "（${(st.routeProgress * 100).roundToInt()}%）　全程耗时约 ${fmtDuration(total / MockEngine.routeSpeed().coerceAtLeast(0.05))}"
            tvRouteInfo.setTextColor(ContextCompat.getColor(this, R.color.accent))
        } else {
            val s = routeStart
            val e = routeEnd
            tvRouteInfo.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            tvRouteInfo.text = if (s != null && e != null) {
                "直线距离 ${Geo.fmtDelta(Geo.distance(s, e))}　全程约 ${fmtDuration(Geo.distance(s, e) / currentSpeed().coerceAtLeast(0.05))}"
            } else {
                "把起点和终点都设好就能开跑"
            }
        }
    }

    private fun setDot(colorRes: Int) {
        val d = ContextCompat.getDrawable(this, R.drawable.bg_dot)?.mutate() ?: return
        d.setTint(ContextCompat.getColor(this, colorRes))
        dotStatus.background = d
    }

    private fun refreshBanner() {
        val selected = MockEngine.isMockAppSelected(this)
        val hasLoc = MockEngine.hasLocationPermission(this)
        if (selected && hasLoc) {
            bannerSetup.visibility = View.GONE
            return
        }
        bannerSetup.visibility = View.VISIBLE
        val app = getString(R.string.app_name)
        tvBanner.text = when {
            !selected && !hasLoc ->
                "还需要两步授权：\n" +
                    "1. 开发者选项 →「选择模拟位置信息应用」→ 选「$app」\n" +
                    "2. 允许「$app」获取位置信息\n" +
                    "（开发者选项在：设置 → 关于手机 → 连点版本号 7 次）"
            !selected ->
                "还差一步：到开发者选项 →「选择模拟位置信息应用」→ 选「$app」。\n" +
                    "（开发者选项在：设置 → 关于手机 → 连点版本号 7 次）"
            else ->
                "定位权限未授予。Android 12 以上模拟 fused provider、以及「我的位置」都需要它。"
        }
    }

    private fun refreshStepUi() {
        stepChips.forEachIndexed { i, c -> c.isSelected = (i == stepIndex) }
        tvStepCenter.text = stepLabels[stepIndex]
    }

    private fun refreshRouteUi() {
        tvRouteStart.text = routeStart?.let { "${Geo.fmt(it.lat)}\n${Geo.fmt(it.lng)}" } ?: "未设置"
        tvRouteEnd.text = routeEnd?.let { "${Geo.fmt(it.lat)}\n${Geo.fmt(it.lng)}" } ?: "未设置"
        render(MockEngine.state)
    }

    private fun updateSpeedLabel() {
        val s = currentSpeed()
        tvSpeed.text = "速度 ${String.format(Locale.US, "%.1f", s)} m/s\n≈ ${String.format(Locale.US, "%.1f", s * 3.6)} km/h"
    }

    private fun currentSpeed(): Double = (seekSpeed.progress.coerceAtLeast(1) / 10.0)

    private fun setLoopChip(on: Boolean) {
        chipLoop.isSelected = on
        chipLoop.text = if (on) "走到终点后循环" else "走到终点后停下"
    }

    // ------------------------------------------------------------ 坐标输入

    private class Target(val point: Waypoint, val acc: Float, val alt: Double)

    private fun readFields(quiet: Boolean = false): Target? {
        val lat = etLat.text.toString().trim().toDoubleOrNull()
        val lng = etLng.text.toString().trim().toDoubleOrNull()
        if (lat == null || lng == null) {
            if (!quiet) toast("纬度/经度填个数字，比如 39.9042 / 116.4074")
            return null
        }
        if (!Geo.isValidLat(lat) || !Geo.isValidLng(lng)) {
            if (!quiet) toast("纬度要在 -90~90，经度要在 -180~180")
            return null
        }
        val acc = (etAcc.text.toString().trim().toDoubleOrNull() ?: 5.0).coerceIn(1.0, 500.0).toFloat()
        val alt = (etAlt.text.toString().trim().toDoubleOrNull() ?: 50.0).coerceIn(-500.0, 9000.0)
        return Target(Waypoint(lat, lng), acc, alt)
    }

    private fun applyTarget(p: Waypoint, from: String) {
        val acc = (etAcc.text.toString().trim().toDoubleOrNull() ?: 5.0).coerceIn(1.0, 500.0).toFloat()
        val alt = (etAlt.text.toString().trim().toDoubleOrNull() ?: 50.0).coerceIn(-500.0, 9000.0)
        etLat.setText(Geo.fmt(p.lat))
        etLng.setText(Geo.fmt(p.lng))
        MockEngine.setAccuracy(acc)
        MockEngine.setAltitude(alt)
        MockEngine.setTarget(this, p.lat, p.lng)
        Prefs.saveAccuracy(this, acc)
        Prefs.saveAltitude(this, alt)
        appendLog("$from → ${Geo.fmt(p.lat)}, ${Geo.fmt(p.lng)}")
    }

    private fun nudge(de: Double, dn: Double) {
        val t = readFields() ?: return
        val step = steps[stepIndex]
        val len = sqrt(de * de + dn * dn)
        if (len <= 0.0) return
        val east = de / len * step
        val north = dn / len * step
        val np = Geo.offset(t.point, east, north)
        etLat.setText(Geo.fmt(np.lat))
        etLng.setText(Geo.fmt(np.lng))
        MockEngine.setTarget(this, np.lat, np.lng)
    }

    /** 单击走一格，长按连续走 */
    private fun bindHold(v: View, de: Double, dn: Double) {
        var held = false
        val repeater = object : Runnable {
            override fun run() {
                if (!held) return
                nudge(de, dn)
                ui.postDelayed(this, 120L)
            }
        }
        v.setOnClickListener { nudge(de, dn) }
        v.setOnLongClickListener {
            held = true
            nudge(de, dn)
            ui.postDelayed(repeater, 320L)
            true
        }
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    held = false
                    ui.removeCallbacks(repeater)
                }
            }
            false
        }
    }

    private fun copyCoords() {
        val t = readFields(quiet = true) ?: return
        val text = "${Geo.fmt(t.point.lat)},${Geo.fmt(t.point.lng)}"
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("coords", text))
        toast("已复制 $text")
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val raw = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (raw.isNullOrBlank()) {
            toast("剪贴板是空的")
            return
        }
        val nums = Regex("-?\\d+(?:\\.\\d+)?").findAll(raw).mapNotNull { it.value.toDoubleOrNull() }.toList()
        for (i in 0 until nums.size - 1) {
            val a = nums[i]
            val b = nums[i + 1]
            if (Geo.isValidLat(a) && Geo.isValidLng(b)) {
                applyTarget(Waypoint(a, b), "剪贴板")
                return
            }
        }
        toast("没从剪贴板里认出坐标，格式如 39.904200,116.407400")
    }

    // ------------------------------------------------------------ 真实定位

    @SuppressLint("MissingPermission")
    private fun fetchRealLocation() {
        if (!MockEngine.hasLocationPermission(this)) {
            requestPermissionsIfNeeded(force = true)
            return
        }
        if (MockEngine.state.running) {
            appendLog("⚠ 模拟正在运行，读到的「真实位置」其实是模拟坐标")
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            toast("没有定位服务")
            return
        }

        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || l.time > best!!.time) best = l
        }
        if (best != null) {
            applyTarget(Waypoint(best.latitude, best.longitude), "真实位置（上次缓存）")
        }

        val provider = when {
            runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ->
                LocationManager.GPS_PROVIDER
            runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
                LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            if (best == null) toast("系统的定位开关没打开")
            return
        }

        appendLog("正在等待一次实时定位…")
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { lm.removeUpdates(this) }
                applyTarget(Waypoint(location.latitude, location.longitude), "真实位置")
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            ui.postDelayed({ runCatching { lm.removeUpdates(listener) } }, 15000L)
        } catch (t: Throwable) {
            appendLog("取真实定位失败：${t.message}")
        }
    }

    // ------------------------------------------------------------ 启停模拟

    private fun startMock() {
        val t = readFields() ?: return
        if (!MockEngine.isMockAppSelected(this)) {
            appendLog("✗ 系统还没把本应用设为模拟位置信息应用")
            showSetupDialog()
            refreshBanner()
            return
        }
        if (!MockEngine.hasLocationPermission(this)) {
            appendLog("✗ 缺少定位权限")
            requestPermissionsIfNeeded(force = true)
            return
        }

        MockEngine.setAccuracy(t.acc)
        MockEngine.setAltitude(t.alt)
        Prefs.saveAccuracy(this, t.acc)
        Prefs.saveAltitude(this, t.alt)
        MockEngine.setTarget(this, t.point.lat, t.point.lng)

        val err = MockEngine.startMock(this)
        if (err != null) {
            appendLog("✗ 启动失败：$err")
            toast(err)
            refreshBanner()
            return
        }

        MockEngine.startTicking()
        val fg = MockService.start(this)
        val prov = MockEngine.state.providers.joinToString(", ")
        appendLog("✓ 模拟已开启 @ ${Geo.fmt(t.point.lat)}, ${Geo.fmt(t.point.lng)}（$prov）")
        if (!fg) {
            appendLog("⚠ 前台服务没能启动，退到后台可能失效；本应用在前台时正常")
        }
    }

    private fun stopMock() {
        MockEngine.stopTicking()
        MockEngine.stopMock(this)
        MockService.stop(this)
        appendLog("已停止模拟，位置交还真实 GPS")
    }

    private fun showSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle("先把自己设成模拟位置应用")
            .setMessage(
                "系统只允许被选中的应用伪造位置，步骤：\n\n" +
                    "1. 打开「开发者选项」\n" +
                    "2. 找到「选择模拟位置信息应用」\n" +
                    "3. 选中「${getString(R.string.app_name)}」\n\n" +
                    "如果找不到开发者选项：设置 → 关于手机 → 连点「版本号」7 次。",
            )
            .setPositiveButton("去设置") { _, _ -> openDevOptions() }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun openDevOptions() {
        val candidates = listOf(
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        for (action in candidates) {
            try {
                startActivity(Intent(action))
                return
            } catch (_: Throwable) {
            }
        }
        toast("没能打开设置，请手动到 设置 → 系统 → 开发者选项")
    }

    // ------------------------------------------------------------ 巡航

    private fun setRoutePoint(start: Boolean, p: Waypoint) {
        if (start) routeStart = p else routeEnd = p
        Prefs.saveRoutePoint(this, start, p)
        refreshRouteUi()
        appendLog("${if (start) "巡航起点" else "巡航终点"} = ${Geo.fmt(p.lat)}, ${Geo.fmt(p.lng)}")
    }

    private fun startRoute() {
        val s = routeStart
        val e = routeEnd
        if (s == null || e == null) {
            toast("先把起点和终点都设好")
            return
        }
        val total = Geo.distance(s, e)
        if (total < 1.0) {
            toast("起终点几乎重合，换个位置")
            return
        }
        if (!MockEngine.state.running) {
            appendLog("巡航需要先开启模拟，正在自动开启…")
            startMock()
            if (!MockEngine.state.running) return
        }
        val speed = currentSpeed()
        MockEngine.startRoute(this, listOf(s, e), speed, chipLoop.isSelected)
        MockEngine.startTicking()
        appendLog(
            "✓ 开始巡航：${Geo.fmtDelta(total)} @ ${String.format(Locale.US, "%.1f", speed)} m/s" +
                "（约 ${fmtDuration(total / speed)}），${if (chipLoop.isSelected) "循环" else "到终点停下"}",
        )
    }

    // ------------------------------------------------------------ 自检

    private val selfTestResults = LinkedHashMap<String, Location>()

    private fun Location.mockFlag(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock
        else @Suppress("DEPRECATION") isFromMockProvider

    /**
     * 按普通应用的方式向系统要一次位置，看看到底发回来的是什么。
     * 用来区分两种"没生效"：系统根本没发模拟位置，还是目标应用自己拒绝模拟位置。
     */
    @SuppressLint("MissingPermission")
    private fun runSelfTest() {
        if (!MockEngine.hasLocationPermission(this)) {
            appendLog("自检需要定位权限")
            requestPermissionsIfNeeded(force = true)
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            toast("这台设备没有定位服务")
            return
        }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
        selfTestResults.clear()
        for (p in providers) {
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
                ?.let { selfTestResults[it.provider ?: p] = it }
        }
        renderSelfTest("…正在监听系统回传（4 秒）")

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                selfTestResults[location.provider ?: "?"] = location
                renderSelfTest(null)
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        for (p in providers) {
            runCatching { lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper()) }
        }
        ui.postDelayed({
            runCatching { lm.removeUpdates(listener) }
            renderSelfTest(null)
            appendLog("自检完成")
        }, 4000L)
    }

    private fun renderSelfTest(status: String?) {
        val target = MockEngine.currentTarget()
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
        val sb = StringBuilder()
        var near = 0
        var far = 0
        var mockFlagged = false

        for (p in providers) {
            val l = selfTestResults[p]
            if (l == null) {
                sb.append(String.format(Locale.US, "%-7s 无数据\n", p))
                continue
            }
            val dist = Geo.distance(Waypoint(l.latitude, l.longitude), target)
            val flag = l.mockFlag()
            if (flag) mockFlagged = true
            if (dist < 100.0) near++ else far++
            // 拆两行：窄屏上单行会折行，读起来更乱
            sb.append(String.format(Locale.US, "%-7s %.6f, %.6f\n", p, l.latitude, l.longitude))
            sb.append(
                String.format(
                    Locale.US,
                    "        ±%.0fm   %s   离目标 %s\n",
                    l.accuracy, if (flag) "模拟✔" else "未标记", Geo.fmtDelta(dist),
                ),
            )
        }
        if (status != null) sb.append(status).append('\n')
        tvSelfTest.text = sb.toString().trimEnd()

        val good: Boolean
        val verdict: String
        when {
            !MockEngine.state.running -> {
                good = false
                verdict = "模拟没在跑：先点底部「开始模拟」，再回来自检。"
            }
            near > 0 && mockFlagged -> {
                good = true
                verdict = "系统层面已经生效：普通应用按常规方式取位置，拿到的就是你的模拟坐标，" +
                    "而且带着「模拟」标记。某个应用如果还是显示真实位置，就是它自己识别并拒绝了模拟位置" +
                    "（见上面的说明），这种情况本应用无解。"
            }
            near > 0 -> {
                good = true
                verdict = "系统发出的位置就是你的目标坐标（没带模拟标记）。目标应用若仍显示真实位置，" +
                    "说明它用的是自己那套网络定位（WiFi/基站）。"
            }
            far > 0 -> {
                good = false
                verdict = "系统发回来的还是真实位置，模拟没生效：确认本应用仍被选为「模拟位置信息应用」，" +
                    "然后停止、重新开始一次模拟。"
            }
            else -> {
                good = false
                verdict = "一个位置都没取到：先确认系统的定位总开关是打开的。"
            }
        }
        tvVerdict.text = verdict
        tvVerdict.setTextColor(ContextCompat.getColor(this, if (good) R.color.accent else R.color.amber))
    }

    private fun copyDiagnostics() {
        val st = MockEngine.state
        val sb = StringBuilder()
        sb.append("位置模拟 · 自检报告\n")
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")  ").append(Build.DISPLAY).append('\n')
        sb.append("已选为模拟位置应用: ").append(MockEngine.isMockAppSelected(this)).append('\n')
        sb.append("定位权限: ").append(MockEngine.hasLocationPermission(this)).append('\n')
        sb.append("模拟运行中: ").append(st.running)
            .append("  providers=").append(st.providers.joinToString(","))
        if (st.failedProviders.isNotEmpty()) {
            sb.append("  失败=").append(st.failedProviders.joinToString(","))
        }
        sb.append('\n')
        sb.append("目标坐标: ").append(Geo.fmt(st.lat)).append(", ").append(Geo.fmt(st.lng))
            .append("  ±").append(st.accuracy.toInt()).append("m\n")
        sb.append("--- 自检 ---\n").append(tvSelfTest.text).append('\n')
        sb.append("--- 判定 ---\n").append(tvVerdict.text).append('\n')
        sb.append("--- 日志 ---\n").append(logLines.joinToString("\n"))

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("diag", sb.toString()))
        toast("诊断信息已复制，可以直接粘给我")
    }

    private fun openLocationSettings() {
        for (action in listOf(Settings.ACTION_LOCATION_SOURCE_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                startActivity(Intent(action))
                return
            } catch (_: Throwable) {
            }
        }
        toast("没能打开定位设置，手动进：设置 → 位置信息")
    }

    // ------------------------------------------------------------ 收藏地点

    private fun renderPresets() {
        presetList.removeAllViews()
        val list = Prefs.presets(this)
        tvPresetEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        list.forEach { presetList.addView(buildPresetRow(it)) }
    }

    private fun buildPresetRow(p: Preset): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_row)
            setPadding(dp(10), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = p.name
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "${Geo.fmt(p.lat)}, ${Geo.fmt(p.lng)}"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.MONOSPACE
        })

        val wp = Waypoint(p.lat, p.lng)
        val use = { _: View -> applyTarget(wp, "收藏：${p.name}") }
        col.setOnClickListener(use)
        row.setOnClickListener(use)

        val del = { _: View ->
            AlertDialog.Builder(this)
                .setTitle("删除「${p.name}」？")
                .setPositiveButton("删除") { _, _ ->
                    val next = Prefs.presets(this).filterNot { it.name == p.name && it.lat == p.lat && it.lng == p.lng }
                    Prefs.savePresets(this, next)
                    renderPresets()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        row.setOnLongClickListener { del(it); true }
        col.setOnLongClickListener { del(it); true }

        row.addView(col)
        row.addView(smallBtn("起点") { setRoutePoint(true, wp) })
        row.addView(smallBtn("终点") { setRoutePoint(false, wp) })
        return row
    }

    private fun smallBtn(label: String, onClick: (View) -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundResource(R.drawable.bg_btn)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(4) }
            isClickable = true
            isFocusable = true
            setOnClickListener { v -> onClick(v) }
        }

    private fun savePresetDialog() {
        val t = readFields() ?: return
        val input = EditText(this).apply {
            hint = "名字，比如「公司」「家」"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
            setText("${Geo.fmt(t.point.lat)}, ${Geo.fmt(t.point.lng)}")
            setSelection(text.length)
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(16), dp(4), dp(16), 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        AlertDialog.Builder(this)
            .setTitle("收藏这个坐标")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().ifBlank { Geo.fmt(t.point.lat) }
                val list = Prefs.presets(this)
                list.add(Preset(name, t.point.lat, t.point.lng))
                Prefs.savePresets(this, list)
                renderPresets()
                appendLog("已收藏「$name」")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 权限 / 工具

    private val mapPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        val data = res.data ?: return@registerForActivityResult
        val lat = data.getDoubleExtra(MapPickerActivity.EXTRA_LAT, Double.NaN)
        val lng = data.getDoubleExtra(MapPickerActivity.EXTRA_LNG, Double.NaN)
        if (lat.isFinite() && lng.isFinite() && Geo.isValidLat(lat) && Geo.isValidLng(lng)) {
            applyTarget(Waypoint(lat, lng), "地图选点")
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine) {
            appendLog("定位权限已授予")
        } else {
            appendLog("定位权限被拒：Android 12 以上 fused provider 会模拟失败，gps/network 通常仍可用")
        }
        refreshBanner()
    }

    private fun requestPermissionsIfNeeded(force: Boolean = false) {
        val want = mutableListOf<String>()
        if (!MockEngine.hasLocationPermission(this)) {
            want.add(Manifest.permission.ACCESS_FINE_LOCATION)
            want.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            want.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (want.isEmpty()) return
        if (force || !permLauncherRequested) {
            permLauncherRequested = true
            permLauncher.launch(want.toTypedArray())
        }
    }

    private var permLauncherRequested = false

    private fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logLines.addLast("$ts  $msg")
        while (logLines.size > 80) logLines.removeFirst()
        tvLog.text = logLines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

    private fun fmtDuration(seconds: Double): String {
        val s = seconds.coerceAtLeast(0.0)
        val h = (s / 3600).toInt()
        val m = ((s % 3600) / 60).toInt()
        val sec = (s % 60).roundToInt()
        return if (h > 0) "$h 小时 $m 分" else if (m > 0) "$m 分 $sec 秒" else "$sec 秒"
    }
}
