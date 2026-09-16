package com.sideproject.mocklocation

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.random.Random

/**
 * 模拟位置引擎。
 *
 * 原理：普通应用无法直接伪造 GPS，必须先在「开发者选项 → 选择模拟位置信息应用」
 * 里被选中，之后系统才允许本应用通过 LocationManager.addTestProvider /
 * setTestProviderLocation 往系统里灌位置。灌进去之后，所有取位置的应用
 * （地图、打卡、运动记录……）拿到的就是这个坐标。
 *
 * 同时模拟 gps / network / fused 三个 provider：
 *   - gps / network：老应用和大部分游戏读这两个
 *   - fused：Android 12+ 上 Google Play Services 融合定位的权威来源
 *
 * 所有位置以 WGS-84 计算（和手机真实 GPS 同一基准）。
 */
object MockEngine {

    private const val TAG = "MockEngine"
    private const val OP_MOCK_LOCATION = "android:mock_location"
    private const val TICK_MS = 1000L

    /** 候选 provider，"fused" 是 LocationManager.FUSED_PROVIDER（API 31 起有常量，这里用字面量保持兼容） */
    private val candidateProviders = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        "fused",
    )

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(MockState) -> Unit>()
    private val activeProviders = mutableListOf<String>()
    private val failedProviders = mutableListOf<String>()

    private var appContext: Context? = null
    private var ticking = false

    /** 静止模式下的目标点，用户输入的那个 */
    @Volatile
    private var target = Waypoint(39.904200, 116.407400)

    var state = MockState()
        private set

    // ---- 巡航状态 ----
    private var path: List<Waypoint> = emptyList()
    private var cumDist: DoubleArray = DoubleArray(0)
    private var travelled = 0.0
    private var routeSpeed = 1.4
    private var routeLoop = true
    private var routeRunning = false
    private var lastTravelledAt = 0L

    // ------------------------------------------------------------------ 订阅

    fun addListener(l: (MockState) -> Unit) {
        listeners.add(l)
        l(state)
    }

    fun removeListener(l: (MockState) -> Unit) {
        listeners.remove(l)
    }

    private fun publish() {
        val snap = state
        listeners.forEach { runCatching { it(snap) } }
    }

    fun currentTarget(): Waypoint = target

    // ------------------------------------------------------- 开发者选项是否已选本应用

    @SuppressLint("NewApi")
    fun isMockAppSelected(ctx: Context): Boolean {
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(OP_MOCK_LOCATION, android.os.Process.myUid(), ctx.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(OP_MOCK_LOCATION, android.os.Process.myUid(), ctx.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            Log.w(TAG, "检查模拟位置权限失败", t)
            false
        }
    }

    fun hasLocationPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun locationManager(): LocationManager? =
        appContext?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    // ------------------------------------------------------------------ 目标设置

    fun setTarget(ctx: Context?, lat: Double, lng: Double) {
        target = Waypoint(lat, lng)
        if (!routeRunning) {
            state = state.copy(lat = lat, lng = lng)
            pushNow()
            publish()
        }
        if (ctx != null) Prefs.saveTarget(ctx, target)
    }

    fun targetWaypoint(): Waypoint = target

    fun setAccuracy(v: Float) {
        state = state.copy(accuracy = v)
        publish()
    }

    fun setAltitude(v: Double) {
        state = state.copy(altitude = v)
        publish()
    }

    fun routeSpeed(): Double = routeSpeed
    fun isRouteRunning(): Boolean = routeRunning

    // ------------------------------------------------------------------ 启停

    /**
     * 注册 test provider 并开始灌位置。
     * @return null 表示成功，否则是可读的失败原因
     */
    @SuppressLint("NewApi")
    fun startMock(ctx: Context): String? {
        appContext = ctx.applicationContext
        val lm = locationManager() ?: return "无法获取 LocationManager"
        activeProviders.clear()
        failedProviders.clear()
        var firstError: String? = null

        for (p in candidateProviders) {
            try {
                addTestProvider(lm, p)
                lm.setTestProviderEnabled(p, true)
                activeProviders.add(p)
            } catch (t: SecurityException) {
                failedProviders.add(p)
                if (firstError == null) {
                    firstError = "系统拒绝模拟位置请求，请确认已在开发者选项里把「" +
                        ctx.getString(R.string.app_name) + "」选为模拟位置信息应用"
                }
                Log.w(TAG, "provider $p 被拒绝", t)
            } catch (t: Throwable) {
                failedProviders.add(p)
                if (firstError == null) firstError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "provider $p 添加失败", t)
            }
        }

        if (activeProviders.isEmpty()) {
            state = state.copy(running = false, providers = emptyList(), failedProviders = failedProviders.toList(), error = firstError)
            publish()
            return firstError ?: "无法创建模拟位置提供者"
        }

        state = state.copy(
            running = true,
            lat = target.lat,
            lng = target.lng,
            providers = activeProviders.toList(),
            failedProviders = failedProviders.toList(),
            error = null,
        )
        pushNow()
        publish()
        return null
    }

    private fun addTestProvider(lm: LocationManager, name: String) {
        try {
            addRaw(lm, name)
        } catch (e: IllegalArgumentException) {
            // 上次进程被杀没清干净，先移除再注册
            runCatching { lm.removeTestProvider(name) }
            addRaw(lm, name)
        }
    }

    @SuppressLint("NewApi")
    private fun addRaw(lm: LocationManager, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val props = android.location.provider.ProviderProperties.Builder()
                .setHasNetworkRequirement(false)
                .setHasSatelliteRequirement(false)
                .setHasCellRequirement(false)
                .setHasMonetaryCost(false)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(android.location.provider.ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(android.location.provider.ProviderProperties.ACCURACY_FINE)
                .build()
            lm.addTestProvider(name, props)
        } else {
            @Suppress("DEPRECATION")
            lm.addTestProvider(
                name,
                false, // requiresNetwork
                false, // requiresSatellite
                false, // requiresCell
                false, // hasMonetaryCost
                true,  // supportsAltitude
                true,  // supportsSpeed
                true,  // supportsBearing
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE,
            )
        }
    }

    /** 移除 provider，停止模拟。手机上的"假位置"随之消失 */
    fun stopMock(ctx: Context? = appContext) {
        routeRunning = false
        path = emptyList()
        cumDist = DoubleArray(0)
        travelled = 0.0
        val lm = (ctx ?: appContext)?.let { it.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager }
        if (lm != null) {
            for (p in activeProviders) {
                runCatching { lm.setTestProviderEnabled(p, false) }
                runCatching { lm.removeTestProvider(p) }
            }
        }
        activeProviders.clear()
        state = state.copy(
            running = false,
            providers = emptyList(),
            speedMps = 0.0,
            bearing = 0.0,
            routeActive = false,
            routeProgress = 0.0,
        )
        publish()
    }

    // ------------------------------------------------------------------ 每秒心跳

    fun startTicking() {
        if (ticking) return
        ticking = true
        lastTravelledAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun stopTicking() {
        ticking = false
        handler.removeCallbacks(ticker)
    }

    fun isTicking(): Boolean = ticking

    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun tick() {
        if (routeRunning) {
            val now = SystemClock.elapsedRealtime()
            val dt = if (lastTravelledAt == 0L) 1.0 else ((now - lastTravelledAt) / 1000.0).coerceIn(0.0, 5.0)
            lastTravelledAt = now
            advanceRoute(dt)
        } else {
            state = state.copy(lat = target.lat, lng = target.lng, speedMps = 0.0)
        }
        pushNow()
        publish()
    }

    /** 往系统灌一次位置。每个 provider 单独构造 Location，避免被系统共享引用 */
    private fun pushNow() {
        val lm = locationManager() ?: return
        if (activeProviders.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        val speed = if (routeRunning) routeSpeed.toFloat() else 0f
        val bearing = state.bearing.toFloat()
        for (p in activeProviders) {
            val loc = Location(p)
            loc.latitude = state.lat
            loc.longitude = state.lng
            loc.accuracy = jitteredAccuracy()
            loc.altitude = state.altitude + Random.nextDouble(-0.6, 0.6)
            loc.speed = if (speed < 0f) 0f else speed
            loc.bearing = ((bearing % 360f) + 360f) % 360f
            loc.time = nowMs
            loc.elapsedRealtimeNanos = nowElapsed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                loc.verticalAccuracyMeters = 1.5f
                loc.speedAccuracyMetersPerSecond = 0.3f
                loc.bearingAccuracyDegrees = 5f
            }
            try {
                lm.setTestProviderLocation(p, loc)
            } catch (t: Throwable) {
                Log.w(TAG, "推送位置到 $p 失败", t)
            }
        }
    }

    /** 精度上下浮动一点，让数据看起来像真实定位而不是一个死值 */
    private fun jitteredAccuracy(): Float {
        val base = state.accuracy
        return (base + Random.nextFloat() * 1.6f - 0.8f).coerceAtLeast(1f)
    }

    // ------------------------------------------------------------------ 巡航

    /**
     * 沿折线巡航。两次 setRoute 之间的点是途经点，速度单位 m/s。
     * @param loop true = 走到终点后回到起点继续；false = 到达终点停下
     */
    fun startRoute(ctx: Context?, points: List<Waypoint>, speedMps: Double, loop: Boolean) {
        val pts = sanitizePath(points)
        if (pts.size < 2) {
            state = state.copy(error = "巡航至少需要起点和终点两个不同的点")
            publish()
            return
        }
        path = pts
        cumDist = DoubleArray(pts.size)
        var acc = 0.0
        for (i in 1 until pts.size) {
            acc += Geo.distance(pts[i - 1], pts[i])
            cumDist[i] = acc
        }
        if (acc < 1.0) {
            state = state.copy(error = "两个点几乎重合（不到 1 米），换个位置")
            publish()
            return
        }
        routeSpeed = speedMps.coerceIn(0.05, 120.0)
        routeLoop = loop
        travelled = 0.0
        lastTravelledAt = SystemClock.elapsedRealtime()
        routeRunning = true
        val start = pts[0]
        state = state.copy(
            lat = start.lat,
            lng = start.lng,
            speedMps = routeSpeed,
            bearing = Geo.bearing(pts[0], pts[1]),
            routeActive = true,
            routeProgress = 0.0,
            error = null,
        )
        (ctx ?: appContext)?.let { Prefs.saveTarget(it, start) }
        pushNow()
        publish()
    }

    fun stopRoute() {
        routeRunning = false
        state = state.copy(routeActive = false, speedMps = 0.0)
        publish()
    }

    /** 去掉重复/非法点 */
    private fun sanitizePath(points: List<Waypoint>): List<Waypoint> {
        val out = mutableListOf<Waypoint>()
        for (p in points) {
            if (!Geo.isValidLat(p.lat) || !Geo.isValidLng(p.lng)) continue
            val last = out.lastOrNull()
            if (last != null && abs(last.lat - p.lat) < 1e-9 && abs(last.lng - p.lng) < 1e-9) continue
            out.add(p)
        }
        return out
    }

    private fun advanceRoute(dt: Double) {
        val total = cumDist.lastOrNull() ?: 0.0
        if (total <= 0.0) {
            stopRoute()
            return
        }
        travelled += routeSpeed * dt
        if (travelled >= total) {
            if (routeLoop) {
                travelled %= total
            } else {
                travelled = total
                routeRunning = false
            }
        }
        val (pos, segStart, segEnd) = positionAt(travelled)
        val brg = Geo.bearing(segStart, segEnd)
        state = state.copy(
            lat = pos.lat,
            lng = pos.lng,
            bearing = brg,
            speedMps = if (routeRunning) routeSpeed else 0.0,
            routeActive = routeRunning,
            routeProgress = (travelled / total).coerceIn(0.0, 1.0),
            error = null,
        )
    }

    /** 返回 (当前点, 所在段的起点, 所在段的终点) */
    private fun positionAt(dist: Double): Triple<Waypoint, Waypoint, Waypoint> {
        var i = 1
        while (i < cumDist.size - 1 && cumDist[i] < dist) i++
        val a = path[i - 1]
        val b = path[i]
        val segLen = cumDist[i] - cumDist[i - 1]
        val t = if (segLen <= 0.0) 0.0 else ((dist - cumDist[i - 1]) / segLen).coerceIn(0.0, 1.0)
        return Triple(Geo.lerp(a, b, t), a, b)
    }

    /** 巡航路径总长度与已走距离，UI 显示用 */
    fun routeInfo(): Triple<Double, Double, Int> =
        Triple(cumDist.lastOrNull() ?: 0.0, travelled, path.size)
}
