package com.nemoclient.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.nemoclient.app.data.PosFeed
import com.nemoclient.app.data.RadarFeed
import com.nemoclient.app.data.RadarTarget
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 雷达 HUD（v0.17.1 精简）：一块圆角方块玻璃面板，里面只有：一圈淡淡的内框、十字线、视野扇形、目标圆点、
 * 最近的敌人（大一点 + 外圈高亮）、自己的朝向三角，以及边缘上的一个“N”。没有扫描线、刻度环和读数。
 * 目标来自 RadarFeed，朝向来自 PosFeed.yaw（MC：0=南，90=西，180=北，270=东）；没有数据时“演示数据”放几个绕圈的假目标。
 */
class RadarHudView(ctx: Context) : HudCanvasView(ctx) {
    private val blurBuf = ArrayList<Float>(6)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val box = RectF()

    private fun side(): Float = dp(when (cfg.i("size")) { 0 -> 92f; 2 -> 144f; else -> 116f })

    override fun windowW() = (side() + 2f * dp(PAD)).toInt()
    override fun windowH() = (side() + 2f * dp(PAD)).toInt()

    override fun collectBlur(out: MutableList<Float>, ox: Float, oy: Float) {
        if (blurBuf.size >= 6) {
            out.add(ox + blurBuf[0]); out.add(oy + blurBuf[1]); out.add(blurBuf[2]); out.add(blurBuf[3])
            out.add(blurBuf[4]); out.add(0.30f * cfg.opacity * blurBuf[5])
        }
    }

    private fun demoTargets(now: Long): List<RadarTarget> {
        val t = now / 1000f
        fun at(r: Float, a: Float, f: Boolean = false) = RadarTarget(r * cos(a), r * sin(a), f)
        return listOf(at(14f, t * 0.35f), at(26f + 3f * sin(t), 2.1f + t * 0.2f), at(33f, 4.0f - t * 0.25f), at(9f, 5.2f + t * 0.5f), at(22f, 1.0f, true))
    }

    override fun render(c: Canvas, now: Long) {
        blurBuf.clear()
        val live = RadarFeed.list.isNotEmpty() && now - RadarFeed.stamp < 1500L
        val demo = !live && (cfg.b("demo") || editing)
        val targets = if (live) RadarFeed.list else if (demo) demoTargets(now) else emptyList()
        if (demo) needFrame = true else needFrame = live
        val range = when (cfg.i("range")) { 0 -> 20f; 2 -> 80f; else -> 40f }
        val northUp = cfg.i("orient") == 1
        val theta = if (!PosFeed.yaw.isNaN()) Math.toRadians(PosFeed.yaw).toFloat()
            else if (demo) (now / 7000f) % (2f * PI.toFloat()) else 0f
        val northScreen = if (northUp) 0f else PI.toFloat() - theta          // 北在屏幕上的方向（顺时针偏离正上方）
        val facingScreen = if (northUp) theta + PI.toFloat() else 0f          // 自己朝向在屏幕上的方向

        val s = side()
        val l = dp(PAD); val t0 = dp(PAD)
        val cx = l + s / 2f; val cy = t0 + s / 2f
        val rad = s * 0.28f
        val accent = Palette.accent
        val ink = Palette.hudText

        fxBehind(c, l, t0, l + s, t0 + s, rad, accent, 1f)
        rrect(c, l, t0, l + s, t0 + s, rad, ((glassA(112f).toInt()) shl 24) or Palette.bgRgb)
        rim(c, l, t0, l + s, t0 + s, rad)
        if (blurOn) { blurBuf.add(l); blurBuf.add(t0); blurBuf.add(s); blurBuf.add(s); blurBuf.add(rad); blurBuf.add(1f) }

        c.save()
        clipRound(c, l, t0, l + s, t0 + s, rad)
        // 内框 + 十字线
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f)
        p.color = withAlpha(ink, 0.12f)
        box.set(l + s * 0.25f, t0 + s * 0.25f, l + s * 0.75f, t0 + s * 0.75f)
        c.drawRoundRect(box, rad * 0.5f, rad * 0.5f, p)
        p.color = withAlpha(ink, 0.08f)
        c.drawLine(l + dp(8f), cy, l + s - dp(8f), cy, p)
        c.drawLine(cx, t0 + dp(8f), cx, t0 + s - dp(8f), p)

        val rMax = s / 2f - dp(9f)
        val sc = rMax / range
        // 视野扇形（±35°）
        if (cfg.b("fov")) {
            val deg = Math.toDegrees(facingScreen.toDouble()).toFloat()
            path.reset(); path.moveTo(cx, cy)
            val fr = s * 0.62f
            path.lineTo(cx + sin(Math.toRadians((deg - 35).toDouble())).toFloat() * fr, cy - cos(Math.toRadians((deg - 35).toDouble())).toFloat() * fr)
            path.lineTo(cx + sin(Math.toRadians((deg + 35).toDouble())).toFloat() * fr, cy - cos(Math.toRadians((deg + 35).toDouble())).toFloat() * fr)
            path.close()
            p.style = Paint.Style.FILL; p.color = withAlpha(accent, 0.10f); c.drawPath(path, p)
        }

        // 目标
        var nearest = -1; var nearestD = Float.MAX_VALUE
        val fx = -sin(theta); val fz = cos(theta); val rx = -cos(theta); val rz = -sin(theta)
        val pts = FloatArray(targets.size * 3) // x, y, 是否在范围内（1 / 0）
        for ((i, tg) in targets.withIndex()) {
            val dist = hypot(tg.dx, tg.dz)
            val sxw = if (northUp) tg.dx else tg.dx * rx + tg.dz * rz
            val syw = if (northUp) -tg.dz else tg.dx * fx + tg.dz * fz
            var px = sxw * sc; var py = -syw * sc
            val inR = dist <= range
            if (!inR) { val k = rMax / hypot(px, py).coerceAtLeast(0.001f); px *= k; py *= k }
            pts[i * 3] = cx + px; pts[i * 3 + 1] = cy + py; pts[i * 3 + 2] = if (inR) 1f else 0f
            if (!tg.friend && dist < nearestD) { nearestD = dist; nearest = i }
        }
        p.style = Paint.Style.FILL
        for ((i, tg) in targets.withIndex()) {
            if (i == nearest) continue
            val col = if (tg.friend) 0xFF7FD6A8.toInt() else accent
            p.color = withAlpha(col, if (pts[i * 3 + 2] > 0f) 0.95f else 0.45f)
            c.drawCircle(pts[i * 3], pts[i * 3 + 1], dp(2.4f), p)
        }
        if (nearest >= 0) {
            val bx = pts[nearest * 3]; val by = pts[nearest * 3 + 1]
            p.color = withAlpha(accent, 0.25f); c.drawCircle(bx, by, dp(6.5f), p)
            p.color = accent; c.drawCircle(bx, by, dp(3.2f), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.2f); p.color = withAlpha(0xFFFFFFFF.toInt(), 0.85f)
            c.drawCircle(bx, by, dp(6.5f), p)
        }

        // 自己（朝向三角）
        val sa = sin(facingScreen); val ca = cos(facingScreen)
        val tri = arrayOf(floatArrayOf(0f, -dp(5f)), floatArrayOf(dp(3.6f), dp(4f)), floatArrayOf(0f, dp(1.8f)), floatArrayOf(-dp(3.6f), dp(4f)))
        path.reset()
        for ((i, q) in tri.withIndex()) {
            val x = cx + q[0] * ca - q[1] * sa; val y = cy + q[0] * sa + q[1] * ca
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt(); c.drawPath(path, p)
        c.restore()

        // 北（在内边缘上）
        val nx = sin(northScreen); val ny = -cos(northScreen)
        val half = s / 2f - dp(10f)
        val k = half / maxOf(abs(nx), abs(ny), 0.001f)
        text(c, "N", cx + nx * k, centerBaseline(cy + ny * k, 9f, true), 9f, accent, bold = true, shadow = false, align = Paint.Align.CENTER)

        content.set(l, t0, l + s, t0 + s)
        hasContent = true
    }

    private companion object { const val PAD = 16f }
}
