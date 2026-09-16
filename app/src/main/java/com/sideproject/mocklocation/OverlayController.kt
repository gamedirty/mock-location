package com.sideproject.mocklocation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 悬浮微调按钮。
 *
 * 目标应用占着前台的时候不用来回切应用：悬浮在它上面直接推位置。
 * 它只是把坐标喂给 MockEngine，不碰任何检测逻辑。
 *
 * 由 MockService 负责显示/隐藏，所以生命周期跟着"模拟开关"走。
 */
object OverlayController {

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var listener: ((MockState) -> Unit)? = null
    private var coordsView: TextView? = null
    private var stepView: TextView? = null

    private val steps = doubleArrayOf(1.0, 10.0, 100.0, 1000.0)
    private val stepLabels = arrayOf("1m", "10m", "100m", "1km")

    /** 悬浮窗权限：Android 6 起要用户单独授予，授权后跳到别的应用上方才有画面 */
    fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun isShowing(): Boolean = view != null

    @SuppressLint("InflateParams")
    fun show(ctx: Context) {
        if (view != null) return
        if (!canDraw(ctx)) return
        val app = ctx.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val v = LayoutInflater.from(app).inflate(R.layout.overlay_nudge, null)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = Prefs.overlayX(app)
        p.y = Prefs.overlayY(app)

        coordsView = v.findViewById(R.id.ovCoords)
        stepView = v.findViewById(R.id.ovStep)

        v.findViewById<View>(R.id.ovNW).setOnClickListener { nudge(app, -1.0, 1.0) }
        v.findViewById<View>(R.id.ovN).setOnClickListener { nudge(app, 0.0, 1.0) }
        v.findViewById<View>(R.id.ovNE).setOnClickListener { nudge(app, 1.0, 1.0) }
        v.findViewById<View>(R.id.ovW).setOnClickListener { nudge(app, -1.0, 0.0) }
        v.findViewById<View>(R.id.ovE).setOnClickListener { nudge(app, 1.0, 0.0) }
        v.findViewById<View>(R.id.ovSW).setOnClickListener { nudge(app, -1.0, -1.0) }
        v.findViewById<View>(R.id.ovS).setOnClickListener { nudge(app, 0.0, -1.0) }
        v.findViewById<View>(R.id.ovSE).setOnClickListener { nudge(app, 1.0, -1.0) }
        v.findViewById<View>(R.id.ovStep).setOnClickListener { cycleStep(app) }
        v.findViewById<View>(R.id.ovClose).setOnClickListener {
            Prefs.saveOverlayEnabled(app, false)
            hide(app)
        }

        bindDrag(app, wm, v, p)

        runCatching { wm.addView(v, p) }.onFailure { return }
        view = v
        params = p
        refreshStep(app)

        val l: (MockState) -> Unit = { st ->
            coordsView?.text = "${Geo.fmt(st.lat)}, ${Geo.fmt(st.lng)}"
        }
        listener = l
        MockEngine.addListener(l)
    }

    fun hide(ctx: Context) {
        listener?.let { MockEngine.removeListener(it) }
        listener = null
        val v = view ?: return
        val wm = ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { wm?.removeView(v) }
        view = null
        params = null
        coordsView = null
        stepView = null
    }

    /** 拖动把手：位置记下来，下次还在原地 */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindDrag(
        ctx: Context,
        wm: WindowManager,
        v: View,
        p: WindowManager.LayoutParams,
    ) {
        val handle = v.findViewById<View>(R.id.ovHandle)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = p.x
                    startY = p.y
                    dragging = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    p.x = startX + (ev.rawX - downX).toInt()
                    p.y = startY + (ev.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(v, p) }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging && (abs(ev.rawX - downX) > 4 || abs(ev.rawY - downY) > 4)) {
                        Prefs.saveOverlayPos(ctx, p.x, p.y)
                    }
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun nudge(ctx: Context, de: Double, dn: Double) {
        val step = steps[Prefs.stepIndex(ctx).coerceIn(0, steps.size - 1)]
        val len = sqrt(de * de + dn * dn)
        if (len <= 0.0) return
        val t = MockEngine.currentTarget()
        val np = Geo.offset(t, de / len * step, dn / len * step)
        MockEngine.setTarget(ctx, np.lat, np.lng)
        refreshStep(ctx)
    }

    private fun cycleStep(ctx: Context) {
        val next = (Prefs.stepIndex(ctx) + 1) % steps.size
        Prefs.saveStepIndex(ctx, next)
        refreshStep(ctx)
    }

    private fun refreshStep(ctx: Context) {
        val i = Prefs.stepIndex(ctx).coerceIn(0, stepLabels.size - 1)
        stepView?.text = stepLabels[i]
        stepView?.setTextColor(
            ContextCompat.getColor(ctx, R.color.text),
        )
    }
}
