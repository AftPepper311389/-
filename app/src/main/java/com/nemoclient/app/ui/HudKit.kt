package com.nemoclient.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import com.nemoclient.app.data.HudCfg
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HUD 渐变主题：HSB 颜色表 + smoothstep 插值，随时间在色表里循环流动（每一行 / 每个字错开一点相位）。
 * 做法参考开源项目里 ModuleList 的 Gradient 模式：position = ((时间/速度 + 行号*偏移) % 1000) / 1000。
 */
object HudTheme {
    val names = listOf(
        "Cotton Candy", "Rainbow", "Aurora", "Sunset", "Ocean",
        "Lavender", "Peach", "Mint", "Cyberpunk", "Drift"
    )

    private fun t(vararg v: Float): Array<FloatArray> =
        Array(v.size / 3) { i -> floatArrayOf(v[i * 3], v[i * 3 + 1], v[i * 3 + 2]) }

    // 顺序与 names 一致；首尾相接，循环无缝
    private val tables: List<Array<FloatArray>> = listOf(
        // Cotton Candy
        t(0.92f, 0.40f, 1.0f, 0.88f, 0.35f, 1.0f, 0.80f, 0.30f, 1.0f, 0.70f, 0.35f, 1.0f, 0.60f, 0.40f, 1.0f, 0.92f, 0.40f, 1.0f),
        // Rainbow
        t(0.00f, 0.50f, 1.0f, 0.17f, 0.50f, 1.0f, 0.33f, 0.50f, 1.0f, 0.50f, 0.50f, 1.0f, 0.67f, 0.50f, 1.0f, 0.83f, 0.50f, 1.0f, 1.00f, 0.50f, 1.0f),
        // Aurora
        t(0.45f, 0.70f, 1.0f, 0.52f, 0.60f, 1.0f, 0.72f, 0.55f, 0.95f, 0.82f, 0.50f, 0.90f, 0.90f, 0.55f, 0.95f, 0.45f, 0.70f, 1.0f),
        // Sunset
        t(0.98f, 0.70f, 1.0f, 0.05f, 0.75f, 1.0f, 0.12f, 0.65f, 1.0f, 0.15f, 0.50f, 1.0f, 0.95f, 0.55f, 0.95f, 0.98f, 0.70f, 1.0f),
        // Ocean
        t(0.57f, 0.80f, 0.90f, 0.53f, 0.70f, 0.95f, 0.50f, 0.60f, 1.0f, 0.47f, 0.55f, 0.95f, 0.43f, 0.50f, 0.90f, 0.57f, 0.80f, 0.90f),
        // Lavender
        t(0.75f, 0.50f, 0.90f, 0.78f, 0.45f, 0.95f, 0.82f, 0.40f, 1.0f, 0.85f, 0.35f, 1.0f, 0.80f, 0.45f, 0.95f, 0.75f, 0.50f, 0.90f),
        // Peach
        t(0.05f, 0.50f, 1.0f, 0.08f, 0.45f, 1.0f, 0.10f, 0.40f, 1.0f, 0.98f, 0.45f, 1.0f, 0.95f, 0.50f, 1.0f, 0.05f, 0.50f, 1.0f),
        // Mint
        t(0.42f, 0.55f, 0.95f, 0.45f, 0.50f, 1.0f, 0.48f, 0.45f, 1.0f, 0.50f, 0.40f, 0.95f, 0.47f, 0.50f, 1.0f, 0.42f, 0.55f, 0.95f),
        // Cyberpunk
        t(0.85f, 0.75f, 1.0f, 0.90f, 0.70f, 1.0f, 0.95f, 0.65f, 0.95f, 0.55f, 0.75f, 1.0f, 0.50f, 0.70f, 1.0f, 0.85f, 0.75f, 1.0f),
        // Drift
        t(0.60f, 0.65f, 0.90f, 0.65f, 0.55f, 0.95f, 0.70f, 0.50f, 1.0f, 0.75f, 0.55f, 0.95f, 0.68f, 0.60f, 0.90f, 0.60f, 0.65f, 0.90f)
    )

    private fun hsb(h: Float, s: Float, b: Float): Int =
        Color.HSVToColor(floatArrayOf((h % 1f) * 360f, s.coerceIn(0f, 1f), b.coerceIn(0f, 1f)))

    /** 色表上 position(0..1) 处的颜色；饱和度 / 亮度按百分比缩放 */
    fun colorAt(theme: Int, position: Double, satScale: Float = 90f, briScale: Float = 100f): Int {
        val tab = tables[theme.coerceIn(0, tables.size - 1)]
        var pos = position % 1.0
        if (pos < 0) pos += 1.0
        val scaled = pos * (tab.size - 1)
        val index = floor(scaled).toInt()
        var fraction = scaled - index
        fraction = fraction * fraction * (3.0 - 2.0 * fraction) // smoothstep
        if (index >= tab.size - 1) {
            val c = tab[tab.size - 1]
            return hsb(c[0], c[1] * satScale / 100f, c[2] * briScale / 100f)
        }
        val c1 = tab[index]
        val c2 = tab[index + 1]
        var h1 = c1[0]
        var h2 = c2[0]
        val diff = h2 - h1
        if (abs(diff) > 0.5f) { if (diff > 0f) h1 += 1f else h2 += 1f } // 色相走最短路径
        val f = fraction.toFloat()
        val hue = (h1 + (h2 - h1) * f) % 1f
        val sat = (c1[1] + (c2[1] - c1[1]) * f) * satScale / 100f
        val bri = (c1[2] + (c2[2] - c1[2]) * f) * briScale / 100f
        return hsb(hue, sat, bri)
    }

    /** 流动色：index 是第几行 / 第几个字，错开相位。speed 越小流得越快（毫秒除数） */
    fun flow(theme: Int, index: Int, speed: Int = 5, offsetPer: Int = 85): Int {
        val pos = ((System.currentTimeMillis() / speed + index.toLong() * offsetPer) % 1000L).toDouble() / 1000.0
        return colorAt(theme, pos)
    }
}

/** 缓动函数（t 为 0..1） */
object Ease {
    val LINEAR: (Float) -> Float = { it }
    val OUT3: (Float) -> Float = { t -> 1f - (1f - t).pow(3) }
    val OUT4: (Float) -> Float = { t -> 1f - (1f - t).pow(4) }
    val OUT5: (Float) -> Float = { t -> 1f - (1f - t).pow(5) }
    val IN3: (Float) -> Float = { t -> t.pow(3) }
    val ELASTIC: (Float) -> Float = { t ->
        if (t <= 0f || t >= 1f) t
        else (2.0.pow(-10.0 * t) * sin((t * 10.0 - 0.75) * 2.0943951023931953) + 1.0).toFloat()
    }
}

/** 按时间推进的补间：animate 从当前值出发；目标没变就不重启（快速连续触发也不会卡住） */
class Tween(initial: Float = 0f) {
    var value = initial
        private set
    var target = initial
        private set
    private var from = initial
    private var start = 0L
    private var dur = 0L
    private var ease: (Float) -> Float = Ease.LINEAR

    val running: Boolean get() = dur > 0L

    fun set(v: Float) { value = v; from = v; target = v; dur = 0L }

    fun animate(to: Float, ms: Long, e: (Float) -> Float, now: Long) {
        if (to == target) return
        from = value
        target = to
        start = now
        dur = ms
        ease = e
    }

    fun tick(now: Long): Float {
        if (dur <= 0L) { value = target; return value }
        val t = ((now - start).toFloat() / dur).coerceIn(0f, 1f)
        value = from + (target - from) * ease(t)
        if (t >= 1f) { value = target; dur = 0L }
        return value
    }
}

/** 弹簧：欠阻尼会有一点回弹（灵动岛的宽度变化用） */
class Spring(initial: Float, private val stiffness: Float = 300f, private val damping: Float = 0.8f) {
    var value = initial
        private set
    var target = initial
    private var vel = 0f
    private var last = 0L

    val settled: Boolean get() = abs(target - value) < 0.4f && abs(vel) < 2f

    fun set(v: Float) { value = v; target = v; vel = 0f; last = 0L }

    fun step(now: Long) {
        if (last == 0L) { last = now; return }
        val dt = ((now - last) / 1000f).coerceAtMost(0.05f)
        last = now
        val c = 2f * damping * sqrt(stiffness)
        var rem = dt
        while (rem > 0f) {
            val h = min(rem, 1f / 240f)
            val acc = stiffness * (target - value) - c * vel
            vel += acc * h
            value += vel * h
            rem -= h
        }
    }
}

/** 颜色整体乘一个透明度系数（保留原有 alpha） */
fun mulAlpha(color: Int, f: Float): Int {
    val a = ((color ushr 24) * f.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
    return (a shl 24) or (color and 0x00FFFFFF)
}

/**
 * HUD 视图基类：每个 HUD 是一个独立的悬浮窗，窗口大小固定（windowW / windowH），内容用 Canvas 自己画。
 * 有动画的时候逐帧重绘（Choreographer），没有就停下来，不空转；数据变化时调用 kick() 唤醒。
 * 风格参考：纯黑半透明小圆角面板、白字带轻微阴影，不描边；编辑模式下给内容加一圈虚线框。
 */
abstract class HudCanvasView(ctx: Context) : View(ctx) {
    private val density = resources.displayMetrics.density

    /** 绘制用的“dp”单位：屏幕密度 × 该 HUD 的缩放。所有尺寸（含窗口大小）都由它换算，所以缩放自动生效 */
    protected val d: Float get() = density * cfg.scale

    /** 当前 HUD 的选项值 + 配色，由宿主在仓库变化时更新 */
    var cfg: HudCfg = HudCfg.EMPTY
        set(v) { field = v; kick() }

    /** 编辑模式：可拖动，并显示示例内容 + 虚线框 */
    var editing = false
        set(v) { field = v; kick() }

    protected val content = RectF()
    protected var hasContent = false
    protected var needFrame = false

    /** 本帧 HUD 背后要不要做实时模糊：圆角半径（<0 = 不要）+ 强度 0..1（淡入淡出时跟着变）。由 render 填写 */
    /** 这个 HUD 的背景模糊现在是否生效：总开关开着 + 该 HUD 自己的“背景模糊”开着。底板会据此调淡，让模糊透出来 */
    protected val blurOn: Boolean get() = blurAvailable && cfg.blur

    /** 编辑模式下被选中（设置面板正对着它）：描边用主题色实线。setter 用自定义 JVM 名避开 View.setSelected(Z) */
    @set:JvmName("setHudSelected")
    var selected = false
        set(v) { field = v; kick() }

    /** 文字 / 图标的透明度：随总透明度下降，但最低保持 60%，保证看得清 */
    protected val textK: Float get() = 1f

    /** 底板在整层透明度之上还要再乘多少，使底板最终透明度 = cfg.opacity */
    protected val bgScale: Float get() = (cfg.opacity / textK).coerceIn(0f, 1f)

    protected var blurR = -1f
    protected var blurK = 1f

    private val tmpBlur = RectF()

    /** 把本 HUD 背后要模糊的区域追加到 out：每块 6 个 float = x, y, w, h, 圆角, 着色强度（坐标 = 窗口内坐标 + 偏移） */
    open fun collectBlur(out: MutableList<Float>, ox: Float, oy: Float) {
        val r = blurShape(tmpBlur)
        if (r < 0f) return
        out.add(ox + tmpBlur.left); out.add(oy + tmpBlur.top)
        out.add(tmpBlur.width()); out.add(tmpBlur.height())
        out.add(r); out.add(0.30f * cfg.opacity * blurK)
    }

    /** 供游戏内宿主读取：把背后模糊的圆角矩形（窗口内坐标）写进 out，返回半径；不需要模糊时返回 -1 */
    fun blurShape(out: RectF): Float {
        if (!hasContent || blurR < 0f || blurK < 0.04f || !blurOn) return -1f
        out.set(content)
        return blurR
    }

    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val textP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xE6FFFFFF.toInt()
        pathEffect = dash
    }
    private val path = Path()
    private val rimP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val radii = FloatArray(8)

    private var posted = false
    private val frameCb = Choreographer.FrameCallback {
        posted = false
        if (isAttachedToWindow) invalidate()
    }

    abstract fun windowW(): Int
    abstract fun windowH(): Int
    protected abstract fun render(c: Canvas, now: Long)

    fun kick() { invalidate() }

    /** 当前内容在窗口内的范围（拖动吸附用）；还没有内容时返回 false */
    fun contentRect(out: RectF): Boolean {
        if (!hasContent) return false
        out.set(content)
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(windowW(), windowH())
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCb)
        removeCallbacks(lazyRun)
        posted = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        // HUD 用自己的配色（“HUD 配色”设置），画完立刻还原，不影响 GUI
        val savedLight = Palette.light
        Palette.light = Palette.hudLight
        try { drawHud(c) } finally { Palette.light = savedLight }
    }

    /** 子类在 render 里设置 > 0 表示“现在没在动画”，下一帧推迟这么多毫秒（省电 / 不抢帧） */
    protected var lazyMs = 0L
    private val lazyRun = Runnable { Choreographer.getInstance().postFrameCallback(frameCb) }

    private fun drawHud(c: Canvas) {
        needFrame = false
        lazyMs = 0L
        hasContent = false
        blurR = -1f
        blurK = 1f
        // 透明度只“主要”作用在底板：整层按 textK 变淡（文字 / 图标保持可读），底板再额外乘 bgScale，
        // 两者相乘正好等于用户设的透明度
        val op = textK
        if (op < 0.99f) {
            val sc = c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (op * 255f).toInt().coerceIn(0, 255))
            render(c, SystemClock.uptimeMillis())
            c.restoreToCount(sc)
        } else {
            render(c, SystemClock.uptimeMillis())
        }
        if (editing && hasContent) {
            editPaint.strokeWidth = (if (selected) 2f else 1.5f) * density
            editPaint.pathEffect = if (selected) null else dash
            editPaint.color = if (selected) Palette.accent else withAlpha(Palette.hudText, 0.9f)
            rect.set(content)
            rect.inset(-3f * density, -3f * density)
            c.drawRoundRect(rect, 8f * density, 8f * density, editPaint)
        }
        if ((needFrame || editing) && !posted) {
            posted = true
            if (lazyMs > 0L && !editing) postDelayed(lazyRun, lazyMs) else Choreographer.getInstance().postFrameCallback(frameCb)
        }
    }

    // ---------------- 辉光 / 边缘阴影 ----------------
    private val fxP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fxPath = Path()
    private val fxSrc = Rect()
    private val fxDst = RectF()
    private val fxSlices = HashMap<Long, Bitmap>()

    /**
     * 辉光 / 阴影用的“九宫格”素材：只缓存一张小的 ALPHA_8 位图（圆角方块 + 高斯模糊），
     * 之后任意大小的矩形都用 9 次 drawBitmap 拉伸出来——不再每帧做模糊，HUD 动起来也不卡。
     */
    private fun fxSlice(rad: Int, blur: Int): Bitmap {
        val key = (rad.toLong() shl 24) or blur.toLong()
        fxSlices[key]?.let { return it }
        if (fxSlices.size > 40) { for (b in fxSlices.values) b.recycle(); fxSlices.clear() }
        val inner = 2 * rad + 2
        val side = inner + 2 * blur
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF000000.toInt()
        p.maskFilter = BlurMaskFilter(blur.toFloat(), BlurMaskFilter.Blur.NORMAL)
        Canvas(bmp).drawRoundRect(blur.toFloat(), blur.toFloat(), (blur + inner).toFloat(), (blur + inner).toFloat(), rad.toFloat(), rad.toFloat(), p)
        fxSlices[key] = bmp
        return bmp
    }

    /** 把 (l,t,r,b) 外扩 blur 的范围用九宫格画出来，color 带透明度 */
    private fun fxDraw(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, blurPx: Float, color: Int) {
        val bl = max(2, blurPx.toInt())
        val rd = max(1, min(rad.toInt(), (min(r - l, b - t) / 2f).toInt()))
        val bmp = fxSlice(rd, bl)
        val cs = bl + rd
        val s = bmp.width
        fxP.color = color
        val x0 = l - bl; val x3 = r + bl; val y0 = t - bl; val y3 = b + bl
        val xs = floatArrayOf(x0, x0 + cs, x3 - cs, x3)
        val ys = floatArrayOf(y0, y0 + cs, y3 - cs, y3)
        val sx = intArrayOf(0, cs, cs + 2, s)
        for (j in 0 until 3) for (i in 0 until 3) {
            fxSrc.set(sx[i], sx[j], sx[i + 1], sx[j + 1])
            fxDst.set(xs[i], ys[j], xs[i + 1], ys[j + 1])
            c.drawBitmap(bmp, fxSrc, fxDst, fxP)
        }
    }

    /** 是否开了辉光或阴影（有的 HUD 据此预留边距） */
    protected val fxOn: Boolean get() = cfg.i("glow") > 0 || cfg.i("shadow") > 0

    /**
     * 在底板之前调用：先画“矩形边缘向外发散的柔和阴影”，再画辉光。
     * clipInside = 把底板内部挖掉，免得半透明底板透出一团黑。
     */
    protected fun fxBehind(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, glowColor: Int, alpha: Float, clipInside: Boolean = true) {
        val sh = cfg.i("shadow")
        val gl = cfg.i("glow")
        if (sh <= 0 && gl <= 0 || alpha <= 0.01f) return
        val k = density * cfg.scale
        c.save()
        if (clipInside && android.os.Build.VERSION.SDK_INT >= 26) {
            val rr = min(rad, min((r - l) / 2f, (b - t) / 2f)).coerceAtLeast(0f)
            fxPath.reset(); fxPath.addRoundRect(l, t, r, b, rr, rr, Path.Direction.CW)
            c.clipOutPath(fxPath)
        }
        if (sh > 0) {
            val a2 = alpha * (if (Palette.light) 0.75f else 1f)
            val s1 = 0.5f * sh * k
            fxDraw(c, l - s1, t - s1, r + s1, b + s1, rad + s1, k * (3f + 2f * sh), withAlpha(0xFF000000.toInt(), min(1f, 0.38f + 0.12f * sh) * a2))
            val s2 = 1.2f * sh * k
            fxDraw(c, l - s2, t - s2, r + s2, b + s2, rad + s2, k * (4f + 3f * sh), withAlpha(0xFF000000.toInt(), min(1f, 0.45f + 0.13f * sh) * a2))
        }
        if (gl > 0) {
            val s1 = 0.5f * gl * k
            fxDraw(c, l - s1, t - s1, r + s1, b + s1, rad + s1, k * (3f + 2f * gl), withAlpha(glowColor, min(1f, 0.40f + 0.14f * gl) * alpha))
            val s2 = 1.0f * gl * k
            fxDraw(c, l - s2, t - s2, r + s2, b + s2, rad + s2, k * (4f + 3f * gl), withAlpha(glowColor, min(1f, 0.36f + 0.14f * gl) * alpha))
        }
        c.restore()
    }

    // ---------------- Material 风格图标（无底板）----------------
    private val gP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gPath = Path()
    private val gPath2 = Path()
    private val gOval = RectF()

    /** kind：0 信息 1 对勾 2 警告三角 3 叉 4 月牙 6 行星。color 已带透明度 */
    protected fun drawMatGlyph(c: Canvas, kind: Int, cx: Float, cy: Float, r: Float, color: Int) {
        gP.color = color
        gP.strokeCap = Paint.Cap.ROUND; gP.strokeJoin = Paint.Join.ROUND
        gP.strokeWidth = r * 0.2f
        gPath.reset()
        when (kind) {
            1 -> {
                gP.style = Paint.Style.STROKE
                gPath.moveTo(cx - r * 0.5f, cy + r * 0.02f); gPath.lineTo(cx - r * 0.12f, cy + r * 0.4f); gPath.lineTo(cx + r * 0.55f, cy - r * 0.38f)
                c.drawPath(gPath, gP)
            }
            2 -> {
                gP.style = Paint.Style.STROKE
                gPath.moveTo(cx, cy - r * 0.62f); gPath.lineTo(cx + r * 0.7f, cy + r * 0.52f); gPath.lineTo(cx - r * 0.7f, cy + r * 0.52f); gPath.close()
                gPath.moveTo(cx, cy - r * 0.12f); gPath.lineTo(cx, cy + r * 0.18f)
                gPath.moveTo(cx, cy + r * 0.34f); gPath.lineTo(cx, cy + r * 0.35f)
                c.drawPath(gPath, gP)
            }
            3 -> {
                gP.style = Paint.Style.STROKE
                gPath.moveTo(cx - r * 0.45f, cy - r * 0.45f); gPath.lineTo(cx + r * 0.45f, cy + r * 0.45f)
                gPath.moveTo(cx + r * 0.45f, cy - r * 0.45f); gPath.lineTo(cx - r * 0.45f, cy + r * 0.45f)
                c.drawPath(gPath, gP)
            }
            4 -> {
                gP.style = Paint.Style.FILL
                gPath.addCircle(cx, cy, r * 0.82f, Path.Direction.CW)
                gPath2.reset(); gPath2.addCircle(cx + r * 0.5f, cy - r * 0.32f, r * 0.72f, Path.Direction.CW)
                gPath.op(gPath2, Path.Op.DIFFERENCE)
                c.drawPath(gPath, gP)
            }
            6 -> {
                gP.style = Paint.Style.FILL
                c.drawCircle(cx, cy, r * 0.52f, gP)
                gP.style = Paint.Style.STROKE
                gP.strokeWidth = r * 0.16f
                c.save(); c.rotate(-24f, cx, cy)
                gOval.set(cx - r * 0.98f, cy - r * 0.3f, cx + r * 0.98f, cy + r * 0.3f)
                c.drawOval(gOval, gP)
                c.restore()
            }
            else -> {
                gP.style = Paint.Style.STROKE
                c.drawCircle(cx, cy, r * 0.8f, gP)
                gPath.moveTo(cx, cy - r * 0.02f); gPath.lineTo(cx, cy + r * 0.38f)
                gPath.moveTo(cx, cy - r * 0.36f); gPath.lineTo(cx, cy - r * 0.35f)
                c.drawPath(gPath, gP)
            }
        }
    }

    // ---------------- 绘制小工具 ----------------
    /** 玻璃底板的一圈细亮边（开着模糊时让 HUD 更“通透”：像一块有厚度的玻璃） */
    protected fun rim(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, alpha: Float = 1f) {
        rimP.style = Paint.Style.STROKE
        rimP.strokeWidth = max(1f, density * 0.9f)
        rimP.color = withAlpha(Palette.ink, 0.16f * alpha * (if (blurOn) 1f else 0.6f))
        val rr = min(rad, min((r - l) / 2f, (b - t) / 2f)).coerceAtLeast(0f)
        c.drawRoundRect(l + 0.5f, t + 0.5f, r - 0.5f, b - 0.5f, rr, rr, rimP)
    }

    /** 开着模糊时底板 alpha 压低到 62%：透出模糊 */
    protected fun glassA(a: Float): Float = if (blurOn) a * 0.62f else 255f

    protected fun dp(x: Float): Float = x * d

    protected fun rrect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) {
        fill.shader = null
        fill.style = Paint.Style.FILL
        fill.color = mulAlpha(color, bgScale)
        val rr = min(rad, min((r - l) / 2f, (b - t) / 2f)).coerceAtLeast(0f)
        c.drawRoundRect(l, t, r, b, rr, rr, fill)
    }

    /** 四个角各自的圆角：左上 / 右上 / 右下 / 左下 */
    protected fun rrectCorners(
        c: Canvas, l: Float, t: Float, r: Float, b: Float,
        tl: Float, tr: Float, br: Float, bl: Float, color: Int
    ) {
        path.reset()
        rect.set(l, t, r, b)
        radii[0] = tl; radii[1] = tl
        radii[2] = tr; radii[3] = tr
        radii[4] = br; radii[5] = br
        radii[6] = bl; radii[7] = bl
        path.addRoundRect(rect, radii, Path.Direction.CW)
        fill.shader = null
        fill.style = Paint.Style.FILL
        fill.color = mulAlpha(color, bgScale)
        c.drawPath(path, fill)
    }

    protected fun clipRound(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float) {
        path.reset()
        rect.set(l, t, r, b)
        path.addRoundRect(rect, rad, rad, Path.Direction.CW)
        c.clipPath(path)
    }

    protected fun setText(sizeDp: Float, bold: Boolean) {
        textP.textSize = sizeDp * d
        textP.typeface = if (bold) BOLD else MEDIUM
    }

    protected fun text(
        c: Canvas, s: String, x: Float, baseline: Float, sizeDp: Float, color: Int,
        bold: Boolean = false, shadow: Boolean = true, align: Paint.Align = Paint.Align.LEFT
    ) {
        setText(sizeDp, bold)
        textP.color = color
        textP.textAlign = align
        if (shadow) {
            val sa = (((color ushr 24) * 0.45f).toInt().coerceIn(0, 255)) shl 24
            textP.setShadowLayer(1.6f * d, 0f, 0.8f * d, sa)
        } else {
            textP.clearShadowLayer()
        }
        c.drawText(s, x, baseline, textP)
        textP.textAlign = Paint.Align.LEFT
    }

    protected fun measure(s: String, sizeDp: Float, bold: Boolean = false): Float {
        setText(sizeDp, bold)
        return textP.measureText(s)
    }

    /** 让文字在 centerY 处垂直居中的基线位置 */
    protected fun centerBaseline(centerY: Float, sizeDp: Float, bold: Boolean = false): Float {
        setText(sizeDp, bold)
        val fm = textP.fontMetrics
        return centerY - (fm.ascent + fm.descent) / 2f
    }

    companion object {
        /** 由宿主同步：ClickGUI 的“界面模糊”总开关 */
        @Volatile var blurAvailable = false
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
}
