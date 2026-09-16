package com.sideproject.mocklocation

import android.content.Context

/** 轻量持久化：SharedPreferences，够用且不引第三方库 */
object Prefs {

    private const val FILE = "mock_location_prefs"
    private const val K_PRESETS = "presets"
    private const val K_LAT = "target_lat"
    private const val K_LNG = "target_lng"
    private const val K_ACC = "accuracy"
    private const val K_ALT = "altitude"
    private const val K_STEP = "step_index"
    private const val K_SPEED = "route_speed"
    private const val K_LOOP = "route_loop"
    private const val K_SOURCE = "map_source"
    private const val K_START = "route_start"
    private const val K_END = "route_end"
    private const val K_OVERLAY = "overlay_enabled"
    private const val K_OVERLAY_X = "overlay_x"
    private const val K_OVERLAY_Y = "overlay_y"
    private const val SEP = "\u0001"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- 收藏地点：每行 name\u0001lat\u0001lng ----

    fun presets(ctx: Context): MutableList<Preset> {
        val raw = sp(ctx).getString(K_PRESETS, "") ?: ""
        val out = mutableListOf<Preset>()
        raw.split("\n").forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split(SEP)
            if (parts.size == 3) {
                val lat = parts[1].toDoubleOrNull()
                val lng = parts[2].toDoubleOrNull()
                if (lat != null && lng != null) out.add(Preset(parts[0], lat, lng))
            }
        }
        return out
    }

    fun savePresets(ctx: Context, list: List<Preset>) {
        val raw = list.joinToString("\n") { "${it.name.replace(SEP, " ")}$SEP${it.lat}$SEP${it.lng}" }
        sp(ctx).edit().putString(K_PRESETS, raw).apply()
    }

    // ---- 目标点 ----

    fun target(ctx: Context): Waypoint? {
        val lat = sp(ctx).getString(K_LAT, null)?.toDoubleOrNull() ?: return null
        val lng = sp(ctx).getString(K_LNG, null)?.toDoubleOrNull() ?: return null
        if (!Geo.isValidLat(lat) || !Geo.isValidLng(lng)) return null
        return Waypoint(lat, lng)
    }

    fun saveTarget(ctx: Context, p: Waypoint) {
        sp(ctx).edit().putString(K_LAT, p.lat.toString()).putString(K_LNG, p.lng.toString()).apply()
    }

    fun accuracy(ctx: Context): Float = sp(ctx).getFloat(K_ACC, 5f)
    fun saveAccuracy(ctx: Context, v: Float) = sp(ctx).edit().putFloat(K_ACC, v).apply()

    fun altitude(ctx: Context): Double = sp(ctx).getFloat(K_ALT, 50f).toDouble()
    fun saveAltitude(ctx: Context, v: Double) = sp(ctx).edit().putFloat(K_ALT, v.toFloat()).apply()

    fun stepIndex(ctx: Context): Int = sp(ctx).getInt(K_STEP, 1)
    fun saveStepIndex(ctx: Context, v: Int) = sp(ctx).edit().putInt(K_STEP, v).apply()

    fun routeSpeed(ctx: Context): Float = sp(ctx).getFloat(K_SPEED, 1.4f)
    fun saveRouteSpeed(ctx: Context, v: Float) = sp(ctx).edit().putFloat(K_SPEED, v).apply()

    fun routeLoop(ctx: Context): Boolean = sp(ctx).getBoolean(K_LOOP, true)
    fun saveRouteLoop(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_LOOP, v).apply()

    /** 巡航起终点，退出应用也不丢 */
    fun routePoint(ctx: Context, start: Boolean): Waypoint? {
        val lat = sp(ctx).getString(if (start) K_START + "_lat" else K_END + "_lat", null)?.toDoubleOrNull() ?: return null
        val lng = sp(ctx).getString(if (start) K_START + "_lng" else K_END + "_lng", null)?.toDoubleOrNull() ?: return null
        if (!Geo.isValidLat(lat) || !Geo.isValidLng(lng)) return null
        return Waypoint(lat, lng)
    }

    fun saveRoutePoint(ctx: Context, start: Boolean, p: Waypoint?) {
        val e = sp(ctx).edit()
        val lp = if (start) K_START else K_END
        if (p == null) {
            e.remove(lp + "_lat").remove(lp + "_lng")
        } else {
            e.putString(lp + "_lat", p.lat.toString()).putString(lp + "_lng", p.lng.toString())
        }
        e.apply()
    }

    /** 地图坐标系：gcj = 高德（国内快、中文）、wgs = OSM（原生 WGS-84 无偏移） */
    fun mapSource(ctx: Context): String = sp(ctx).getString(K_SOURCE, "gcj") ?: "gcj"
    fun saveMapSource(ctx: Context, v: String) = sp(ctx).edit().putString(K_SOURCE, v).apply()

    // ---- 悬浮微调按钮 ----

    fun overlayEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_OVERLAY, false)
    fun saveOverlayEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_OVERLAY, v).apply()

    fun overlayX(ctx: Context): Int = sp(ctx).getInt(K_OVERLAY_X, 24)
    fun overlayY(ctx: Context): Int = sp(ctx).getInt(K_OVERLAY_Y, 260)

    fun saveOverlayPos(ctx: Context, x: Int, y: Int) =
        sp(ctx).edit().putInt(K_OVERLAY_X, x).putInt(K_OVERLAY_Y, y).apply()
}
