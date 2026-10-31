package com.nemoclient.app.ui

import android.annotation.SuppressLint
import android.animation.ArgbEvaluator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.view.Gravity
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.hypot

/** 设计稿坐标系 → 屏幕像素。设计稿窗口宽 1120，unit = 实际窗口宽 / 1120，实现比例自适应。 */
object Ui {
    /** 设计稿宽度：1120 → 1040，窗口内所有控件整体放大约 8% */
    const val DESIGN_W = 1040f
    var unit = 1f
    var panelW = 1120
    var panelH = 680
    fun u(x: Float): Int = (x * unit).toInt().coerceAtLeast(if (x > 0f) 1 else 0)
    fun u(x: Int): Int = u(x.toFloat())
    fun f(x: Float): Float = x * unit
}

/** 设计稿配色（深色毛玻璃） */
object Palette {
    /** 浅色主题开关（ModuleRepo.applyAppearance 里设置；切换后要重建界面） */
    @JvmStatic var light = false
    /** HUD 是否用浅色（= 浅色主题 && “HUD 配色”选了跟随外观）；HUD 绘制时临时把 light 换成它 */
    @JvmStatic var hudLight = false

    private fun pick(d: Int, l: Int) = if (light) l else d

    /** 自定义调色板（Monet 式）：true 时所有中性色按强调色的色相重新生成 */
    @JvmStatic var tint = false
    private var hue = 0f
    private var sat = 0.3f
    fun setCustom(argb: Int) {
        accent = argb or (0xFF shl 24)
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(argb, hsv)
        hue = hsv[0]; sat = hsv[1].coerceIn(0.12f, 0.6f); tint = true
    }
    /** 按色相取一个色：s 是饱和度系数，v 是明度 */
    private fun t(s: Float, v: Float, a: Int = 0xFF): Int =
        (a shl 24) or (android.graphics.Color.HSVToColor(floatArrayOf(hue, (sat * s).coerceIn(0f, 1f), v.coerceIn(0f, 1f))) and 0xFFFFFF)
    private fun tp(d: Int, l: Int, dark: () -> Int, lite: () -> Int) = if (tint) (if (light) lite() else dark()) else pick(d, l)

    val inkBlack: Int get() = tp(0xFF211C15.toInt(), 0xFFE9E3D6.toInt(), { t(0.55f, 0.10f) }, { t(0.30f, 0.91f) })
    val sage: Int get() = if (tint) t(0.35f, 0.38f) else 0xFF5F5F53.toInt()
    val brown: Int get() = if (tint) android.graphics.Color.HSVToColor(floatArrayOf(hue, (sat + 0.15f).coerceAtMost(0.9f), 0.40f)) else 0xFF653B25.toInt()
    val ink: Int get() = tp(0xFFEFE6DB.toInt(), 0xFF2A241C.toInt(), { t(0.12f, 0.93f) }, { t(0.45f, 0.14f) })
    val ink2: Int get() = tp(0xFFA89E8E.toInt(), 0xFF7C7262.toInt(), { t(0.22f, 0.68f) }, { t(0.32f, 0.42f) })
    val panelD: Int get() = tp(0xFF3D3C32.toInt(), 0xFFDAD2C3.toInt(), { t(0.45f, 0.22f) }, { t(0.35f, 0.84f) })
    val line: Int get() = (if (light) 0x1F000000 else 0x1A000000) or (ink and 0xFFFFFF)
    val glass: Int get() = if (tint) (if (light) t(0.25f, 0.94f, 0xB0) else t(0.45f, 0.22f, 0x8C)) else pick(0x8C3D3C32.toInt(), 0xB0EFE9DC.toInt())
    /** HUD 底板的 RGB（不含 alpha） */
    val bgRgb: Int get() = if (tint) (if (light) t(0.32f, 0.89f) else t(0.55f, 0.10f)) and 0xFFFFFF else pick(0x211C15, 0xE2DBCD)
    /** HUD 上的主文字 / 次文字色 */
    val hudText: Int get() = pick(0xFFFFFFFF.toInt(), 0xFF2A241C.toInt())
    val hudText2: Int get() = pick(0xFFC8C2B8.toInt(), 0xFF6E6454.toInt())
    /** 弹出卡片 / 提示条 / 遮罩的底色（深色：原来的深棕；浅色：奶油色，不再是一块黑） */
    val sheetBg: Int get() = if (tint) (if (light) t(0.18f, 0.95f, 0xF2) else t(0.55f, 0.17f, 0xC7)) else pick(0xC72F2C24.toInt(), 0xF2F2EDE2.toInt())
    val toastBg: Int get() = if (tint) (if (light) t(0.18f, 0.95f, 0xF2) else t(0.55f, 0.17f, 0xE6)) else pick(0xE62F2C24.toInt(), 0xF2F2EDE2.toInt())
    val scrim: Int get() = pick(0x4D14100B, 0x3A3A3022)
    /** 画在强调色底上的文字 / 图标颜色：强调色很亮（比如黄色）时用深色，否则用白色 */
    val onAccent: Int get() {
        val c = accent
        val l = 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)
        return if (l > 155f) 0xFF1B1710.toInt() else 0xFFFFFFFF.toInt()
    }
    val accents = intArrayOf(0xFFB15F32.toInt(), 0xFF653B25.toInt(), 0xFF5F5F53.toInt())
    var accent = accents[0]
}

fun withAlpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a * 255).toInt() shl 24)

fun rr(fill: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, stroke)
}

fun Context.dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

/** 设计稿字号（px，按 unit 缩放） */
fun TextView.px(size: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.f(size) * com.nemoclient.app.i18n.I18n.textScale)

fun TextView.bold() { typeface = Typeface.DEFAULT_BOLD }

/** 悬浮窗拖动：点按触发 onTap，拖动结束触发 onDrop（自由停放，不吸边） */
@SuppressLint("ClickableViewAccessibility")
fun View.makeDraggable(
    wm: WindowManager,
    lp: WindowManager.LayoutParams,
    onTap: () -> Unit,
    onDrop: (Int, Int) -> Unit,
    onPress: ((Boolean) -> Unit)? = null
) {
    var downX = 0f
    var downY = 0f
    var startX = 0
    var startY = 0
    var moved = false
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                startX = lp.x; startY = lp.y
                moved = false
                onPress?.invoke(true)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!moved && hypot(dx, dy) > slop) { moved = true; onPress?.invoke(false) }
                if (moved) {
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    wm.updateViewLayout(v, lp)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                onPress?.invoke(false)
                if (moved) onDrop(lp.x, lp.y) else onTap()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                onPress?.invoke(false)
                true
            }
            else -> false
        }
    }
}

fun ripple(base: Drawable?, radius: Float): Drawable = RippleDrawable(
    ColorStateList.valueOf(0x24FFFFFF), base,
    GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius }
)

fun circleRipple(): Drawable = RippleDrawable(
    ColorStateList.valueOf(withAlpha(Palette.sage, 0.45f)), null, ShapeDrawable(OvalShape())
)

/** 胶囊按钮。style: 0=主题色实心 1=次级 2=次级(红字) 3=红色实心 */
fun pillButton(ctx: Context, text: String, style: Int = 1, onClick: () -> Unit): TextView = TextView(ctx).apply {
    this.text = text
    px(12.5f); bold()
    gravity = Gravity.CENTER
    val filled = style == 0 || style == 3
    val fill = when (style) {
        0 -> Palette.accent
        3 -> 0xFFB5483A.toInt()
        else -> withAlpha(Palette.sage, 0.35f)
    }
    setTextColor(when (style) { 0 -> Palette.onAccent; 3 -> Color.WHITE; 2 -> 0xFFE0826F.toInt(); else -> Palette.ink })
    setPadding(Ui.u(16f), Ui.u(8f), Ui.u(16f), Ui.u(8f))
    background = ripple(rr(fill, Ui.f(999f), if (filled) 0 else Palette.line, if (filled) 0 else Ui.u(1f)), Ui.f(999f))
    setOnClickListener { onClick() }
}

fun mixColor(a: Int, b: Int, t: Float): Int = ArgbEvaluator().evaluate(t, a, b) as Int
