package com.sideproject.mocklocation

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 一个经纬度点。整个应用内部统一用 WGS-84（和手机原始 GPS 一致）。 */
data class Waypoint(val lat: Double, val lng: Double)

/** 收藏的地点 */
data class Preset(val name: String, val lat: Double, val lng: Double)

/** 引擎对外暴露的完整状态，UI 订阅它刷新界面 */
data class MockState(
    val running: Boolean = false,
    val lat: Double = 39.904200,
    val lng: Double = 116.407400,
    val accuracy: Float = 5f,
    val altitude: Double = 50.0,
    val speedMps: Double = 0.0,
    val bearing: Double = 0.0,
    val routeActive: Boolean = false,
    val routeProgress: Double = 0.0,
    val providers: List<String> = emptyList(),
    val failedProviders: List<String> = emptyList(),
    val error: String? = null,
)

object Geo {

    /** 平均地球半径（IUGG），够用了 */
    private const val R = 6371008.8

    fun distance(a: Waypoint, b: Waypoint): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lng - a.lng)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(h)))
    }

    /** 起点到终点的初始方位角，0 = 正北 */
    fun bearing(a: Waypoint, b: Waypoint): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lng - a.lng)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** 从 from 点沿 bearingDeg 方向走 meters 米 */
    fun destination(from: Waypoint, bearingDeg: Double, meters: Double): Waypoint {
        val d = meters / R
        val br = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(from.lat)
        val l1 = Math.toRadians(from.lng)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(br))
        val l2 = l1 + atan2(sin(br) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Waypoint(Math.toDegrees(p2), normLng(Math.toDegrees(l2)))
    }

    /** 东/北方向各偏移若干米（方向键步进用） */
    fun offset(from: Waypoint, eastMeters: Double, northMeters: Double): Waypoint {
        val dist = hypot(eastMeters, northMeters)
        if (dist < 1e-9) return from
        val brg = (Math.toDegrees(atan2(eastMeters, northMeters)) + 360.0) % 360.0
        return destination(from, brg, dist)
    }

    /** 两点之间按比例取点，t ∈ [0,1]。短距离线性插值足够精确 */
    fun lerp(a: Waypoint, b: Waypoint, t: Double): Waypoint {
        val k = t.coerceIn(0.0, 1.0)
        return Waypoint(a.lat + (b.lat - a.lat) * k, normLng(a.lng + (b.lng - a.lng) * k))
    }

    fun normLng(lng: Double): Double = ((lng + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    /** 6 位小数 ≈ 0.1 米，写进输入框用 */
    fun fmt(v: Double): String {
        val s = String.format(java.util.Locale.US, "%.6f", v)
        return if (s == "-0.000000") "0.000000" else s
    }

    fun fmtDelta(meters: Double): String =
        if (abs(meters) >= 1000) String.format(java.util.Locale.US, "%.2f km", meters / 1000)
        else String.format(java.util.Locale.US, "%.1f m", meters)

    /** 粗略换算：当前缩放级别下 1 像素对应多少米（地图比例尺显示用） */
    fun metersPerPixel(lat: Double, zoom: Int): Double =
        156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)

    fun isValidLat(v: Double) = v.isFinite() && v >= -90.0 && v <= 90.0
    fun isValidLng(v: Double) = v.isFinite() && v >= -180.0 && v <= 180.0
}
