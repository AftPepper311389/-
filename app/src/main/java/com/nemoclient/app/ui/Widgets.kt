package com.nemoclient.app.ui

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.view.Gravity
import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Android 16 (M3 Expressive) 风格滑条：
 *  通长粗轨道(22u 全圆角，active 段跟随主题色) + 深色竖胶囊手柄(高于轨道)，按压时手柄平滑变高 */
class FlatSlider(
    ctx: Context,
    private val min: Float,
    private val max: Float,
    private val step: Float,
    value: Float,
    private val onChange: (Float) -> Unit,
    private val onCommit: () -> Unit
) : View(ctx) {
    var value = value
        private set
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 手柄高度动画进度 0..1（0 = 30u 常态，1 = 34u 按压） */
    private var pressT = 0f
    private var pressAnim: ValueAnimator? = null
    private var pressed = false

    private fun pad() = Ui.f(13f)

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val x0 = pad()
        val x1 = width - pad()
        val cx = x0 + (x1 - x0) * ((value - min) / (max - min))

        val hw = Ui.f(11f)                      // 手柄宽（竖胶囊）
        val hh = Ui.f(30f) + Ui.f(4f) * pressT // 手柄高（按压微增，不超过行高避免裁剪）

        // 通长粗轨道：inactive 段
        val trackH = Ui.f(22f)
        p.style = Paint.Style.FILL
        p.color = withAlpha(Palette.sage, 0.5f)
        c.drawRoundRect(x0, cy - trackH / 2, x1, cy + trackH / 2, trackH / 2, trackH / 2, p)

        // active 段（到 thumb 中心，thumb 会盖住交界）
        p.color = Palette.accent
        c.drawRoundRect(x0, cy - trackH / 2, cx, cy + trackH / 2, trackH / 2, trackH / 2, p)

        // 手柄：竖胶囊（M3 specs：handle = On primary，与 primary 形成对比的前景色）
        if (Palette.light) {
            // 浅色主题：白色手柄 + 细边，不再是一根黑条
            p.color = 0xFFFFFFFF.toInt()
            c.drawRoundRect(cx - hw / 2, cy - hh / 2, cx + hw / 2, cy + hh / 2, hw / 2, hw / 2, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = Ui.f(1.2f); p.color = withAlpha(Palette.ink, 0.28f)
            c.drawRoundRect(cx - hw / 2, cy - hh / 2, cx + hw / 2, cy + hh / 2, hw / 2, hw / 2, p)
            p.style = Paint.Style.FILL
        } else {
            p.color = Palette.ink
            c.drawRoundRect(cx - hw / 2, cy - hh / 2, cx + hw / 2, cy + hh / 2, hw / 2, hw / 2, p)
        }
    }

    private fun animatePress(to: Float) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(pressT, to).apply {
            duration = 160
            interpolator = DecelerateInterpolator()
            addUpdateListener { pressT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pressed = true; animatePress(1f); update(e.x)
            }
            MotionEvent.ACTION_MOVE -> update(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false; animatePress(0f); onCommit()
            }
        }
        return true
    }

    private fun update(x: Float) {
        val frac = ((x - pad()) / (width - 2 * pad())).coerceIn(0f, 1f)
        var v = min + ((frac * (max - min)) / step).roundToInt() * step
        v = ((v * 100).roundToInt() / 100f).coerceIn(min, max)
        if (v != value) { value = v; invalidate(); onChange(v) }
    }
}

/** 分段选择（离散排布）：深棕药丸指示器在选项间平滑滑动，文字颜色同步渐变 */
class SegmentedView(
    ctx: Context,
    options: List<String>,
    selected: Int,
    private val onSelect: (Int) -> Unit
) : FrameLayout(ctx) {
    private var sel = selected
    private val indicator = View(ctx)
    private val labels = mutableListOf<TextView>()
    private var anim: ValueAnimator? = null

    init {
        indicator.background = rr(Palette.brown, Ui.f(999f))
        addView(indicator)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { i, o ->
            val t = TextView(ctx).apply {
                text = o
                px(11.5f)
                bold()
                gravity = Gravity.CENTER
                val hp = if (com.nemoclient.app.i18n.I18n.lang == com.nemoclient.app.i18n.Lang.EN) 8f else 11f
                setPadding(Ui.u(hp), Ui.u(5f), Ui.u(hp), Ui.u(5f))
                setOnClickListener { select(i) }
            }
            labels.add(t)
            row.addView(t, LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = if (i == 0) 0 else Ui.u(3f)
            })
        }
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        // 首次布局后把指示器摆到初始选项上（无动画）
        post { place(sel, sel, false) }
    }

    private fun select(i: Int) {
        if (i == sel) return
        val from = sel
        sel = i
        place(from, i, true)
        onSelect(i)
    }

    /** 指示器从 from 滑到 to；to 项文字渐白、from 项渐回次级色 */
    private fun place(from: Int, to: Int, animate: Boolean) {
        val a = paddingLeft + labels[from].left
        val b = paddingLeft + labels[to].left
        val top = paddingTop + labels[to].top
        val bot = paddingTop + labels[to].bottom
        if (!animate) {
            indicator.layout(a, top, a + labels[from].width, bot)
            tint(from, to, 1f)
            return
        }
        anim?.cancel()
        val aw = labels[from].width
        val bw = labels[to].width
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                val l = a + (b - a) * t
                val r = l + aw + (bw - aw) * t
                indicator.layout(l.toInt(), top, r.toInt(), bot)
                tint(from, to, t)
            }
            start()
        }
    }

    private fun tint(from: Int, to: Int, t: Float) {
        val eval = ArgbEvaluator()
        labels.forEachIndexed { i, tv ->
            val target = when (i) {
                to -> eval.evaluate(t, Palette.ink2, Color.WHITE) as Int
                from -> eval.evaluate(t, Color.WHITE, Palette.ink2) as Int
                else -> Palette.ink2
            }
            tv.setTextColor(target)
        }
    }
}

class MaxHeightScrollView(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }
}

/** 圆角方形复选框（多选模式用） */
class CheckBoxView(ctx: Context) : View(ctx) {
    var checked = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Ui.u(22f), Ui.u(22f))

    override fun onDraw(c: Canvas) {
        val s = width.toFloat()
        val r = Ui.f(6f)
        if (checked) {
            p.style = Paint.Style.FILL; p.color = Palette.accent
            c.drawRoundRect(0f, 0f, s, s, r, r, p)
            p.style = Paint.Style.STROKE; p.color = Color.WHITE
            p.strokeWidth = Ui.f(2.2f); p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            path.reset()
            path.moveTo(s * 0.26f, s * 0.52f); path.lineTo(s * 0.43f, s * 0.69f); path.lineTo(s * 0.75f, s * 0.32f)
            c.drawPath(path, p)
        } else {
            val sw = Ui.f(2f)
            p.style = Paint.Style.STROKE; p.color = Palette.ink2; p.strokeWidth = sw
            c.drawRoundRect(sw / 2, sw / 2, s - sw / 2, s - sw / 2, r, r, p)
        }
    }
}

/** 圆形单选指示（空心圈 + 选中时的实心点） */
class RadioView(ctx: Context) : View(ctx) {
    var checked = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Ui.u(22f), Ui.u(22f))

    override fun onDraw(c: Canvas) {
        val s = width / 2f
        val sw = Ui.f(2f)
        p.style = Paint.Style.STROKE; p.strokeWidth = sw
        p.color = if (checked) Palette.accent else Palette.ink2
        c.drawCircle(s, s, s - sw / 2, p)
        if (checked) {
            p.style = Paint.Style.FILL; p.color = Palette.accent
            c.drawCircle(s, s, Ui.f(5.5f), p)
        }
    }
}

/** 向导背景：深色底 + 几团很淡的主题色渐变（随主题色自适应，不是光效） */
class SoftBackdropView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(Palette.inkBlack)
        blob(c, w * 0.16f, h * 0.30f, w * 0.55f, Palette.accent, 0.26f)
        blob(c, w * 0.86f, h * 0.82f, w * 0.62f, Palette.sage, 0.45f)
        blob(c, w * 0.64f, h * 0.06f, w * 0.45f, mixColor(Palette.accent, Palette.brown, 0.6f), 0.24f)
    }

    private fun blob(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, a: Float) {
        p.shader = RadialGradient(cx, cy, r, withAlpha(color, a), withAlpha(color, 0f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    }
}

/** HUD 配色选择：一排渐变圆点（对应 HudTheme 里的十套配色），选中的带一圈外环 */
class ThemeDotsView(
    ctx: Context,
    private var selected: Int,
    private val onSelect: (Int) -> Unit
) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val n = HudTheme.names.size

    private fun dot() = Ui.f(24f)

    private fun centerX(i: Int): Float {
        val d = dot()
        val ring = Ui.f(4f)
        val usable = width - 2 * (d / 2f + ring)
        return d / 2f + ring + usable * i / (n - 1).coerceAtLeast(1)
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val r = dot() / 2f
        for (i in 0 until n) {
            val cx = centerX(i)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(
                cx - r, cy - r, cx + r, cy + r,
                intArrayOf(HudTheme.colorAt(i, 0.0), HudTheme.colorAt(i, 0.5), HudTheme.colorAt(i, 1.0)),
                null, Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, r, p)
            p.shader = null
            if (i == selected) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = Ui.f(2f)
                p.color = Palette.ink
                c.drawCircle(cx, cy, r + Ui.f(3.2f), p)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { parent?.requestDisallowInterceptTouchEvent(true); return true }
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            var best = 0
            var bd = Float.MAX_VALUE
            for (i in 0 until n) {
                val dd = kotlin.math.abs(centerX(i) - e.x)
                if (dd < bd) { bd = dd; best = i }
            }
            if (best != selected) { selected = best; invalidate(); onSelect(best) }
            return true
        }
        return true
    }
}
