package com.sideproject.mocklocation

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84（GPS 原始坐标）与 GCJ-02（国测局坐标，高德/腾讯地图用）互转。
 *
 * 手机 GPS 芯片输出的、以及 mock location 喂给系统的，都是 WGS-84。
 * 而国内地图瓦片（高德）是 GCJ-02，两者在国内相差 50~700 米，所以在地图上
 * 选点必须做这一步换算，否则模拟出来的位置会整体偏移。
 */
object CoordTransform {

    private const val A = 6378245.0            // 克拉索夫斯基椭球长半轴
    private const val EE = 0.00669342162296594323 // 偏心率平方

    /** 国外不偏移，直接返回原坐标 */
    fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    fun wgs84ToGcj02(lat: Double, lng: Double): DoubleArray {
        if (outOfChina(lat, lng)) return doubleArrayOf(lat, lng)
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * Math.PI)
        return doubleArrayOf(lat + dLat, lng + dLng)
    }

    /** 反解：正解再取差值迭代一次，误差 < 1 米 */
    fun gcj02ToWgs84(lat: Double, lng: Double): DoubleArray {
        if (outOfChina(lat, lng)) return doubleArrayOf(lat, lng)
        val fwd = wgs84ToGcj02(lat, lng)
        val dLat = fwd[0] - lat
        val dLng = fwd[1] - lng
        return doubleArrayOf(lat - dLat, lng - dLng)
    }

    /** 百度坐标 BD-09 → GCJ-02（预留：要接百度瓦片时用得上） */
    fun bd09ToGcj02(lat: Double, lng: Double): DoubleArray {
        val x = lng - 0.0065
        val y = lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * Math.PI * 3000.0 / 180.0)
        val theta = Math.atan2(y, x) - 0.000003 * cos(x * Math.PI * 3000.0 / 180.0)
        return doubleArrayOf(z * sin(theta), z * cos(theta))
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
