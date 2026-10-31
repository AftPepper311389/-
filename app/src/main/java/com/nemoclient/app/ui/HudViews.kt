package com.nemoclient.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.os.SystemClock
import com.nemoclient.app.R
import com.nemoclient.app.data.HUD_VERSION
import com.nemoclient.app.data.HudInfo
import com.nemoclient.app.data.HudModuleRow
import com.nemoclient.app.data.TargetFeed
import com.nemoclient.app.data.TargetInfo
import com.nemoclient.app.i18n.tr
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 文字主色：界面同款的暖白（不是纯白） */
private val WHITE: Int get() = Palette.ink

// =====================================================================
// 水印：胶囊 / 灵动岛
// =====================================================================

/**
 * 水印。四种样式（参考开源项目的 Watermark 模块）：
 *  胶囊：  [logo] rlin client | 账号 | 120fps | 32ms
 *  Neverlose：一排独立的小圆角块：名称 / 账号 / Ping / FPS / 时间，每块带蓝色小圆点
 *  Pharos：  无底板，大号名称 + 下面一行 FPS
 *  Logo：    只有一个渐变色的大 logo（颜色随 HUD 主题流动）
 * 黑色半透明底，不描边。fps / ping 来自 HudInfo（演示数据开启时用假数据）。
 */
class WatermarkHudView(ctx: Context) : HudCanvasView(ctx) {
    private val logo: Bitmap? = try {
        BitmapFactory.decodeResource(resources, R.drawable.ic_logo_r)
    } catch (e: Throwable) {
        null
    }
    private val logoSrc = Rect()
    private val logoDst = RectF()
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val timeFmt = java.text.SimpleDateFormat("HH:mm", Locale.US)
    private val xferP = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }

    override fun windowW() = dp(380f).toInt()
    override fun windowH() = dp(64f).toInt()

    private fun fpsOf(now: Long) = if (cfg.b("demo")) 116 + ((now / 700L) % 7L).toInt() else HudInfo.fps
    private fun pingOf(now: Long) = if (cfg.b("demo")) 28 + ((now / 1300L) % 9L).toInt() else HudInfo.ping

    private fun stats(now: Long): String {
        val fps = fpsOf(now)
        val ping = pingOf(now)
        val parts = ArrayList<String>(2)
        if (fps >= 0) parts.add("${fps}fps")
        if (ping >= 0) parts.add("${ping}ms")
        return parts.joinToString("  ·  ")
    }

    private fun drawLogo(c: Canvas, l: Float, t: Float, size: Float, alpha: Float) {
        val b = logo ?: return
        logoSrc.set(0, 0, b.width, b.height)
        logoDst.set(l, t, l + size, t + size)
        bmpPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        c.drawBitmap(b, logoSrc, logoDst, bmpPaint)
    }

    override fun render(c: Canvas, now: Long) {
        needFrame = true
        lazyMs = 120L
        when (cfg.i("style")) {
            1 -> renderNeverlose(c, now)
            2 -> renderPharos(c, now)
            3 -> renderLogo(c)
            else -> renderCapsule(c, now)
        }
    }

    /** Neverlose：一排独立的小块，每块 = 蓝色小圆点 + 文字；第一块是大写的客户端名 */
    private fun renderNeverlose(c: Canvas, now: Long) {
        val items = ArrayList<String>(5)
        items.add(HudInfo.user)
        val ping = pingOf(now)
        val fps = fpsOf(now)
        if (ping >= 0) items.add("${ping}ms")
        if (fps >= 0) items.add("${fps}fps")
        items.add(timeFmt.format(java.util.Date()))
        val name = "RLIN CLIENT"
        val h = dp(22f)
        val gap = dp(5f)
        val pad = dp(8f)
        val dot = dp(5f)
        val nameW = pad + measure(name, 12.5f, true) + pad
        var total = nameW
        val widths = FloatArray(items.size)
        for ((i, t) in items.withIndex()) {
            widths[i] = pad + dot + dp(5f) + measure(t, 11.5f) + pad
            total += gap + widths[i]
        }
        val y0 = dp(16f)
        var x = (winWidth() - total) / 2f
        val x00 = x
        val bg = (Palette.bgRgb or ((if (blurOn) 0x78 else 0xFF) shl 24))
        val accent = Palette.accent
        fxBehind(c, x, y0, x + nameW, y0 + h, dp(5f), Palette.accent, 1f)
        rrect(c, x, y0, x + nameW, y0 + h, dp(5f), bg)
        text(c, name, x + pad, centerBaseline(y0 + h / 2f, 12.5f, true), 12.5f, Palette.hudText, bold = true)
        x += nameW + gap
        for ((i, t) in items.withIndex()) {
            fxBehind(c, x, y0, x + widths[i], y0 + h, dp(5f), Palette.accent, 1f)
            rrect(c, x, y0, x + widths[i], y0 + h, dp(5f), bg)
            rrect(c, x + pad, y0 + h / 2f - dot / 2f, x + pad + dot, y0 + h / 2f + dot / 2f, dot / 2f, accent)
            text(c, t, x + pad + dot + dp(5f), centerBaseline(y0 + h / 2f, 11.5f), 11.5f, Palette.hudText)
            x += widths[i] + gap
        }
        content.set(x00, y0, x - gap, y0 + h)
        hasContent = true
    }

    /** Pharos：没有底板，大号名称 + 下面一行 FPS，水平居中 */
    private fun renderPharos(c: Canvas, now: Long) {
        val name = "rlin client"
        val fps = fpsOf(now)
        val fpsText = if (fps >= 0) fps.toString() else ""
        val nw = measure(name, 22f, true)
        val x0 = (winWidth() - nw) / 2f
        val base = dp(32f)
        text(c, name, x0, base, 22f, Palette.hudText, bold = true)
        if (fpsText.isNotEmpty()) text(c, fpsText, x0 + dp(1f), base + dp(17f), 13f, Palette.hudText)
        content.set(x0, base - dp(20f), x0 + nw, base + (if (fpsText.isEmpty()) dp(4f) else dp(21f)))
        hasContent = true
    }

    /** Logo：大 logo，竖向渐变着色（颜色来自 HUD 主题，缓慢流动） */
    private fun renderLogo(c: Canvas) {
        needFrame = true
        val size = dp(48f)
        val l = (winWidth() - size) / 2f
        val t = dp(8f)
        val b = logo
        if (b != null) {
            val sc = c.saveLayer(l, t, l + size, t + size, null)
            logoSrc.set(0, 0, b.width, b.height)
            logoDst.set(l, t, l + size, t + size)
            bmpPaint.alpha = 255
            c.drawBitmap(b, logoSrc, logoDst, bmpPaint)
            xferP.shader = LinearGradient(l, t, l, t + size,
                HudTheme.flow(cfg.theme, 0), HudTheme.flow(cfg.theme, 4), Shader.TileMode.CLAMP)
            c.drawRect(l, t, l + size, t + size, xferP)
            xferP.shader = null
            c.restoreToCount(sc)
        }
        content.set(l, t, l + size, t + size)
        hasContent = true
    }

    private fun renderCapsule(c: Canvas, now: Long) {
        val st = stats(now)
        val name = "rlin client"
        val info = " | " + HudInfo.user + (if (st.isEmpty()) "" else " | $st")
        val logoSize = dp(16f)
        val pad = dp(10f)
        val gap = dp(6f)
        val nameW = measure(name, 13f, true)
        val infoW = measure(info, 12f)
        val w = pad + logoSize + gap + nameW + infoW + pad
        val h = dp(28f)
        val x0 = (winWidth() - w) / 2f
        val y0 = dp(16f)
        fxBehind(c, x0, y0, x0 + w, y0 + h, h / 2f, Palette.accent, 1f)
        rrect(c, x0, y0, x0 + w, y0 + h, h / 2f, if (blurOn) (Palette.bgRgb or (0x47 shl 24)) else (Palette.bgRgb or (0xFF shl 24)))
        rim(c, x0, y0, x0 + w, y0 + h, h / 2f)
        blurR = h / 2f
        drawLogo(c, x0 + pad, y0 + (h - logoSize) / 2f, logoSize, 1f)
        val base = centerBaseline(y0 + h / 2f, 13f, true)
        text(c, name, x0 + pad + logoSize + gap, base, 13f, Palette.hudText, bold = true)
        text(c, info, x0 + pad + logoSize + gap + nameW, base, 12f, withAlpha(Palette.hudText, 0.8f))
        content.set(x0, y0, x0 + w, y0 + h)
        hasContent = true
    }

    private fun winWidth(): Float = getWidth().toFloat()
}

// =====================================================================
// 模块列表
// =====================================================================

/**
 * 模块列表。做法参考开源项目的 ModuleListHud：
 *  - 已启用模块逐行显示，按文字宽度从长到短排；开 / 关时该行宽高从 0 长出来（240ms，缓出）或缩回去（180ms），并带一点横向滑动
 *  - 相邻两行宽度不同时，下一行宽出来的那一角按宽度差取圆角，所以整列像一块连在一起的阶梯
 *  - 靠屏幕哪一侧就向哪一侧对齐（自动）；文字颜色用 HUD 渐变主题，每行错开相位，随时间流动
 *  - 黑色半透明底；可选侧边线 / 后缀（模块当前模式）
 */
class ModuleListHudView(ctx: Context) : HudCanvasView(ctx) {
    var source: () -> List<HudModuleRow> = { emptyList() }

    private class Row(val name: String) {
        var shown = name
        var suffix: String? = null
        var measuredFor: String? = null
        var textW = 0f
        var rowW = 0f
        val prog = Tween(0f)
        var target = false
    }

    private class Lay(val row: Row, val idx: Int, val x: Float, val y: Float, val w: Float, val h: Float, val p: Float)

    private val rows = LinkedHashMap<String, Row>()
    private val loc = IntArray(2)
    private val blurBuf = ArrayList<Float>()   // 每行的模糊区域：x, y, w, h, 圆角, 透明度

    override fun collectBlur(out: MutableList<Float>, ox: Float, oy: Float) {
        var i = 0
        while (i + 5 < blurBuf.size) {
            out.add(ox + blurBuf[i]); out.add(oy + blurBuf[i + 1]); out.add(blurBuf[i + 2]); out.add(blurBuf[i + 3])
            out.add(blurBuf[i + 4]); out.add(0.30f * cfg.opacity * blurBuf[i + 5])
            i += 6
        }
    }

    override fun windowW() = min(dp(280f + 2f * FX_PAD).toInt(), (resources.displayMetrics.widthPixels * 0.8f).toInt())
    override fun windowH() = min(dp(18f).toInt() * MAX_ROWS + 2 + dp(2f * FX_PAD).toInt(), resources.displayMetrics.heightPixels)

    private fun setVisible(r: Row, v: Boolean, now: Long) {
        if (r.target == v) return
        r.target = v
        if (v) r.prog.animate(1f, 240L, Ease.OUT3, now) else r.prog.animate(0f, 180L, Ease.IN3, now)
    }

    override fun render(c: Canvas, now: Long) {
        blurBuf.clear()
        var want = source()
        if (want.isEmpty() && editing) want = DEMO
        val rowH = dp(18f)
        val padX = dp(5f)
        val radius = dp(4f)
        val lineW = dp(2f)
        val style = cfg.i("style")          // 0 阶梯 1 强调条 2 药丸 3 文字
        val anim = cfg.i("anim")            // 0 滑动 1 淡入 2 滑动+淡入 3 缩放
        val showLine = (cfg.b("line") || style == 1) && style != 3
        val showBg = cfg.b("bg") && style != 3
        val pill = style == 2
        val gap = if (pill) dp(2.5f) else 0f
        val showSuffix = cfg.b("suffix")

        val names = HashSet<String>()
        for (w in want) {
            names.add(w.name)
            val r = rows.getOrPut(w.name) { Row(w.name) }
            val suf = if (showSuffix && !w.suffix.isNullOrBlank()) w.suffix else null
            val lower = cfg.b("lower")
            val shownName = if (lower) w.name.lowercase() else w.name
            val shownSuf = if (lower) suf?.lowercase() else suf
            val full = if (shownSuf == null) shownName else shownName + " " + shownSuf
            val mkey = full + "@" + d
            if (r.measuredFor != mkey) {
                r.measuredFor = mkey
                r.shown = shownName
                r.suffix = shownSuf
                r.textW = measure(full, TEXT_SP)
            }
            r.rowW = r.textW + padX * 2f + (if (showLine) lineW else 0f)
            setVisible(r, true, now)
        }
        for (r in rows.values) if (!names.contains(r.name)) setVisible(r, false, now)

        val list = ArrayList<Row>(rows.size)
        val iter = rows.values.iterator()
        while (iter.hasNext()) {
            val r = iter.next()
            r.prog.tick(now)
            if (!r.target && r.prog.value <= 0.01f && !r.prog.running) iter.remove() else list.add(r)
        }
        if (list.isEmpty()) return
        when (cfg.i("sort")) {
            1 -> list.sortBy { it.textW }
            2 -> list.sortBy { it.name.lowercase() }
            else -> list.sortByDescending { it.textW }
        }

        getLocationOnScreen(loc)
        val right = loc[0] + getWidth() / 2f > resources.displayMetrics.widthPixels / 2f
        val winW = getWidth().toFloat()

        // 布局
        val lays = ArrayList<Lay>(list.size)
        var y = dp(FX_PAD)
        for ((i, r) in list.withIndex()) {
            val p = r.prog.value.coerceIn(0f, 1f)
            if (p <= 0.01f) continue
            val aw = max(0.1f, if (anim == 1) r.rowW else r.rowW * p)
            val ah = max(0.1f, rowH * p)
            val slide = if (anim == 0 || anim == 2) (if (right) SLIDE_DP else -SLIDE_DP) * d * (1f - p) else 0f
            val x = (if (right) winW - dp(FX_PAD) - aw else dp(FX_PAD)) + slide
            lays.add(Lay(r, i, x, y, aw, ah, p))
            y += (rowH + gap) * p
        }
        if (lays.isEmpty()) return
        needFrame = true
        lazyMs = 33L

        val n = lays.size
        if (fxOn && (showBg || style == 3)) {
            for (l in lays) {
                val col = when (cfg.i("color")) { 1 -> Palette.accent; 2 -> Palette.ink; else -> HudTheme.flow(cfg.theme, l.idx) }
                val pa = if (anim == 0) 1f else l.p
                fxBehind(c, l.x, l.y, l.x + l.w, l.y + l.h, if (pill) l.h / 2f else radius, col, pa, showBg)
            }
        }
        var minX = Float.MAX_VALUE
        var maxX = 0f
        for ((i, l) in lays.withIndex()) {
            val color = when (cfg.i("color")) {
                1 -> Palette.accent
                2 -> WHITE
                else -> HudTheme.flow(cfg.theme, l.idx)
            }
            val pa = if (anim == 0) 1f else l.p
            // 圆角：和下一行的宽度差决定“阶梯”那一角的圆角
            val diffR = if (i < n - 1) min(radius, abs(l.w - lays[i + 1].w)) else radius
            var tl = 0f
            var tr = 0f
            var br = 0f
            var bl = 0f
            if (!right) {
                br = diffR
                if (i == 0) { tl = radius; tr = radius }
                if (i == n - 1) { bl = radius; br = radius }
            } else {
                bl = diffR
                if (i == 0) { tl = radius; tr = radius }
                if (i == n - 1) { br = radius; bl = radius }
            }
            val x2 = l.x + l.w
            val y2 = l.y + l.h
            if (showBg) {
                val ba = (if (blurOn) BG_ALPHA * 0.72f else 255f) * pa
                if (blurOn) {
                    blurBuf.add(l.x); blurBuf.add(l.y); blurBuf.add(l.w); blurBuf.add(l.h)
                    blurBuf.add(if (pill) l.h / 2f else radius); blurBuf.add(pa)
                }
                if (pill) rrect(c, l.x, l.y, x2, y2, (y2 - l.y) / 2f, (((ba).toInt()) shl 24) or Palette.bgRgb)
                else rrectCorners(c, l.x, l.y, x2, y2, tl, tr, br, bl, (((ba).toInt()) shl 24) or Palette.bgRgb)
            }
            if (showLine) {
                val lx = if (right) x2 - lineW else l.x
                rrect(c, lx, l.y, lx + lineW, y2, if (pill) lineW / 2f else 0f, withAlpha(color, pa))
            }
            // 文字（裁在这一行的范围内，行在长出来的过程中文字被慢慢“揭开”）
            c.save()
            c.clipRect(l.x, l.y, x2, y2)
            val base = centerBaseline(l.y + rowH / 2f, TEXT_SP)
            val nameW = measure(l.row.shown, TEXT_SP)
            val reserve = if (showLine) lineW else 0f
            val tx = if (right) x2 - padX - l.row.textW - reserve else l.x + padX + reserve
            text(c, l.row.shown, tx, base, TEXT_SP, withAlpha(color, pa))
            val suf = l.row.suffix
            if (suf != null) {
                val sx = tx + nameW + measure(" ", TEXT_SP)
                text(c, suf, sx, base, TEXT_SP, withAlpha(Palette.hudText, 0.62f * pa))
            }
            c.restore()
            minX = min(minX, l.x)
            maxX = max(maxX, x2)
        }
        content.set(minX, dp(FX_PAD), maxX, y)
        hasContent = true
    }

    private companion object {
        const val FX_PAD = 16f
        const val MAX_ROWS = 24
        const val TEXT_SP = 12.5f
        const val SLIDE_DP = 18f
        const val BG_ALPHA = 118f // 黑底 alpha（参考项目是 80，这里没有背景模糊，所以略深一点）
        val DEMO = listOf(
            HudModuleRow("KillAura", "Distance"),
            HudModuleRow("Speed", "Strafe"),
            HudModuleRow("Sprint", null),
            HudModuleRow("ESP", "Box")
        )
    }
}

// =====================================================================
// 目标信息：Round / Rise
// =====================================================================

/**
 * 目标信息。四种样式（参考开源项目的 hud/target）：Round / Rise / Moon / Naven / Simple。
 * 下面是其中两种的说明：
 *  Round：小卡片，圆角方形头像 + 名字 + 距离/血量 + 细血条
 *  Rise： 宽卡片，圆角方形头像 + "Name: " + 逐字渐变的名字 + 粗胶囊血条（渐变 + 光晕）+ 血量数字
 * 动画：出现时面板淡入 → 名字上滑 → 血条长出来（分段错开）；丢失目标后停留 0.3 秒再淡出；
 * 血条有一层灰色“残影”缓慢追上；目标掉血时头像缩小再弹性回弹。头像位没有皮肤时留空。
 */
class TargetHudView(ctx: Context) : HudCanvasView(ctx) {
    private val fade = Tween(0f)
    private val slide = Tween(5f)       // 单位 dp
    private val contentT = Tween(0f)
    private val hp = Tween(1f)
    private val lag = Tween(1f)
    private val scale = Tween(1f)

    private var cur: TargetInfo? = null
    private var visible = false
    private var lastActive = 0L
    private var showStart = 0L
    private var slideStarted = false
    private var contentStarted = false
    private var needSnap = true
    private var lastHealth = -1f

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG) // 皮肤是像素风：不做平滑
    private val src = Rect()
    private val dst = RectF()

    override fun windowW() = dp(254f).toInt()
    override fun windowH() = dp(108f).toInt()

    private fun startShow(now: Long) {
        fade.animate(1f, 350L, Ease.OUT3, now)
        slide.set(5f)
        contentT.set(0f)
        showStart = now
        slideStarted = false
        contentStarted = false
        needSnap = true
    }

    override fun render(c: Canvas, now: Long) {
        val live = TargetFeed.current ?: (if (editing) PREVIEW else null)
        var changed = false
        var hurt = false
        if (live != null) {
            lastActive = now
            val prev = cur
            if (prev == null || prev.name != live.name) {
                changed = true
            } else if (lastHealth >= 0f && live.health < lastHealth - 0.01f) {
                hurt = true
            }
            lastHealth = live.health
            cur = live
        }
        val shouldShow = live != null || now - lastActive < 300L
        if (shouldShow && !visible) {
            visible = true
            startShow(now)
        } else if (!shouldShow && visible) {
            visible = false
            fade.animate(0f, 150L, Ease.IN3, now)
            slide.animate(5f, 150L, Ease.IN3, now)
            contentT.animate(0f, 150L, Ease.IN3, now)
        } else if (changed && visible) {
            startShow(now)
        }
        if (hurt) {
            scale.set(0.7f)
            scale.animate(1f, 1500L, Ease.ELASTIC, now)
        }
        if (visible) {
            if (!slideStarted && now - showStart >= 30L) { slide.animate(0f, 300L, Ease.OUT3, now); slideStarted = true }
            if (!contentStarted && now - showStart >= 52L) { contentT.animate(1f, 400L, Ease.OUT3, now); contentStarted = true }
            val t = cur
            if (t != null) {
                val ratio = if (t.maxHealth > 0f) (t.health / t.maxHealth).coerceIn(0f, 1f) else 0f
                if (needSnap) {
                    hp.set(ratio); lag.set(ratio); needSnap = false
                } else {
                    hp.animate(ratio, 500L, Ease.OUT4, now)
                    lag.animate(ratio, 1500L, Ease.OUT5, now)
                }
            }
        }
        val f = fade.tick(now)
        slide.tick(now); contentT.tick(now); hp.tick(now); lag.tick(now); scale.tick(now)
        if (f <= 0.01f) return
        val t = cur ?: return
        needFrame = true
        when (cfg.i("style")) {
            1 -> renderRise(c, t, f)
            2 -> renderMoon(c, t, f)
            3 -> renderNaven(c, t, f)
            4 -> renderSimple(c, t, f)
            else -> renderRound(c, t, f)
        }
    }

    /** Moon：头像 + 名字，血量数字在同一行最右（青色）；血条横跨整个面板；头像下方预留 4 个装备格（留空） */
    private val heartPath = Path()
    private val heartP = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 心形读数：每颗 = 2 点生命，满 / 半 / 空；放不下时自动缩小。返回占用宽度 */
    private fun drawHearts(c: Canvas, left: Float, cy: Float, health: Float, max: Float, maxW: Float, f: Float) {
        val n = ceil(max / 2f).toInt().coerceIn(1, 10)
        val size = min(dp(10f), (maxW - (n - 1) * dp(1.5f)) / n)
        if (size <= dp(3f)) return
        val gap = dp(1.5f)
        for (i in 0 until n) {
            val x = left + i * (size + gap)
            val fill = ((health - i * 2f) / 2f).coerceIn(0f, 1f)     // 0 空 / .5 半 / 1 满
            heartShape(x, cy - size / 2f, size)
            heartP.style = Paint.Style.FILL
            heartP.color = withAlpha(0xFFE0554B.toInt(), 0.22f * f)
            c.drawPath(heartPath, heartP)
            if (fill > 0f) {
                c.save()
                c.clipRect(x, cy - size / 2f - 1f, x + size * fill, cy + size / 2f + 1f)
                heartP.color = withAlpha(0xFFE0554B.toInt(), f)
                c.drawPath(heartPath, heartP)
                c.restore()
            }
        }
    }

    private fun heartShape(x: Float, y: Float, s: Float) {
        heartPath.reset()
        heartPath.moveTo(x + s * 0.5f, y + s * 0.92f)
        heartPath.cubicTo(x - s * 0.15f, y + s * 0.55f, x + s * 0.02f, y - s * 0.05f, x + s * 0.26f, y + s * 0.06f)
        heartPath.cubicTo(x + s * 0.40f, y + s * 0.10f, x + s * 0.48f, y + s * 0.22f, x + s * 0.5f, y + s * 0.30f)
        heartPath.cubicTo(x + s * 0.52f, y + s * 0.22f, x + s * 0.60f, y + s * 0.10f, x + s * 0.74f, y + s * 0.06f)
        heartPath.cubicTo(x + s * 0.98f, y - s * 0.05f, x + s * 1.15f, y + s * 0.55f, x + s * 0.5f, y + s * 0.92f)
        heartPath.close()
    }

    private fun renderMoon(c: Canvas, t: TargetInfo, f: Float) {
        val x = dp(16f)
        val y = dp(16f)
        val w = dp(170f)
        val h = dp(62f)
        fxBehind(c, x, y, x + w, y + h, dp(7f), Palette.accent, f)
        rrect(c, x, y, x + w, y + h, dp(7f), ((((glassA(110f) * f).toInt()) shl 24) or Palette.bgRgb))
        rim(c, x, y, x + w, y + h, dp(7f), f)
        blurR = dp(7f); blurK = f
        val cs = scale.value * max(0.7f, f)
        val slot = dp(36f)
        val hs = slot * cs
        drawHead(c, t, x + dp(6f) + (slot - hs) / 2f, y + dp(6f) + (slot - hs) / 2f, hs, dp(7f) * cs, f)
        val cx = x + dp(6f) + slot + dp(8f)
        val slideOff = slide.value * d
        text(c, t.name, cx, y + dp(20f) + slideOff, 13f, withAlpha(Palette.hudText, f), bold = true)
        text(c, ceil(t.health).toInt().toString(), x + w - dp(8f), y + dp(20f) + slideOff, 13f,
            withAlpha(Palette.accent, f), bold = true, align = Paint.Align.RIGHT)
        // 装备格（以后接真实装备）
        val q = dp(13f)
        for (i in 0 until 4) {
            val qx = cx + i * (q + dp(4f))
            rrect(c, qx, y + dp(28f), qx + q, y + dp(28f) + q, dp(3f), mulAlpha(0x1AFFFFFF, f))
        }
        val bx = x + dp(6f)
        val bw = w - dp(12f)
        val by = y + h - dp(12f)
        val bh = dp(5f)
        rrect(c, bx, by, bx + bw, by + bh, dp(3f), ((((100f * f).toInt()) shl 24) or Palette.bgRgb))
        rrect(c, bx, by, bx + bw * lag.value, by + bh, dp(3f), (((120f * f).toInt()) shl 24) or 0x636363)
        gradientBar(c, bx, by, bw * hp.value * contentT.value, bh, mixColor(Palette.accent, 0xFF000000.toInt(), 0.12f), mixColor(Palette.accent, WHITE, 0.28f), f)
        content.set(x, y, x + w, y + h)
        hasContent = true
    }

    /** Naven：偏紫的深色面板，红色名字，三行文字（血量 / 距离），底部红色血条；面板宽度跟名字走 */
    private fun renderNaven(c: Canvas, t: TargetInfo, f: Float) {
        val x = dp(16f)
        val y = dp(16f)
        val nameW = measure(t.name, 13f, true)
        val w = max(dp(170f), nameW + dp(72f))
        val h = dp(70f)
        fxBehind(c, x, y, x + w, y + h, dp(8f), Palette.accent, f)
        rrect(c, x, y, x + w, y + h, dp(8f), mulAlpha((Palette.bgRgb or ((if (blurOn) 0xC8 else 0xFF) shl 24)), if (blurOn) 0.62f * f else f))
        rim(c, x, y, x + w, y + h, dp(8f), f)
        blurR = dp(8f); blurK = f
        val hs = dp(40f) * max(0.8f, f)
        drawHead(c, t, x + dp(9f), y + dp(10f), hs, dp(3f), f)
        val cx = x + dp(56f)
        val slideOff = slide.value * d
        val red = 0xFF9A4338.toInt()
        text(c, t.name, cx, y + dp(21f), 13f, withAlpha(red, f), bold = true)
        val white = withAlpha(WHITE, f)
        if (cfg.i("readout") == 1) drawHearts(c, cx, y + dp(30f) + slideOff, t.health, t.maxHealth, x + w - dp(8f) - cx, f)
        else text(c, "Health: " + String.format(Locale.US, "%.1f", t.health), cx, y + dp(34f) + slideOff, 10.5f, white)
        text(c, "Distance: " + String.format(Locale.US, "%.1f", t.distance), cx, y + dp(46f) + slideOff, 10.5f, white)
        val bx = x + dp(8f)
        val bw = w - dp(16f)
        val by = y + h - dp(12f)
        val bh = dp(5f)
        rrect(c, bx, by, bx + bw, by + bh, dp(2.5f), ((((90f * f).toInt()) shl 24) or Palette.bgRgb))
        rrect(c, bx, by, bx + bw * hp.value * contentT.value, by + bh, dp(2.5f), withAlpha(red, f))
        content.set(x, y, x + w, y + h)
        hasContent = true
    }

    /** Simple：很淡的小面板，名字 + "HP: n"，细白血条，红色掉血残影 */
    private fun renderSimple(c: Canvas, t: TargetInfo, f: Float) {
        val x = dp(16f)
        val y = dp(16f)
        val w = dp(160f)
        val h = dp(50f)
        fxBehind(c, x, y, x + w, y + h, dp(7f), Palette.accent, f)
        rrect(c, x, y, x + w, y + h, dp(7f), ((((glassA(92f) * f).toInt()) shl 24) or Palette.bgRgb))
        rim(c, x, y, x + w, y + h, dp(7f), f)
        blurR = dp(7f); blurK = f
        val cs = scale.value * max(0.7f, f)
        val slot = dp(38f)
        val hs = slot * cs
        drawHead(c, t, x + dp(6f) + (slot - hs) / 2f, y + (h - hs) / 2f, hs, dp(7f) * cs, f)
        val cx = x + dp(6f) + slot + dp(8f)
        val slideOff = slide.value * d
        text(c, t.name, cx, y + dp(19f) + slideOff, 13f, withAlpha(Palette.hudText, f), bold = true)
        if (cfg.i("readout") == 1) drawHearts(c, cx, y + dp(28f) + slideOff, t.health, t.maxHealth, x + w - dp(8f) - cx, f)
        else text(c, "HP: " + ceil(t.health).toInt(), cx, y + dp(32f) + slideOff, 10.5f, withAlpha(Palette.hudText2, f))
        val bx = cx
        val bw = x + w - dp(8f) - bx
        val by = y + h - dp(11f)
        val bh = dp(4f)
        rrect(c, bx, by, bx + bw, by + bh, dp(2f), ((((90f * f).toInt()) shl 24) or Palette.bgRgb))
        rrect(c, bx, by, bx + bw * lag.value, by + bh, dp(2f), (((120f * f).toInt()) shl 24) or 0x961E1E)
        gradientBar(c, bx, by, bw * hp.value * contentT.value, bh, WHITE, WHITE, f)
        content.set(x, y, x + w, y + h)
        hasContent = true
    }

    /** 圆角方形头像；没有皮肤就只画一块很淡的底（留空） */
    private fun drawHead(c: Canvas, t: TargetInfo, l: Float, tp: Float, size: Float, rad: Float, f: Float) {
        val bmp = t.avatar
        if (bmp == null) {
            rrect(c, l, tp, l + size, tp + size, rad, mulAlpha(0x1AFFFFFF, f))
            return
        }
        c.save()
        clipRound(c, l, tp, l + size, tp + size, rad)
        src.set(0, 0, bmp.width, bmp.height)
        dst.set(l, tp, l + size, tp + size)
        bmpPaint.isFilterBitmap = false
        bmpPaint.alpha = (f * 255f).toInt().coerceIn(0, 255)
        c.drawBitmap(bmp, src, dst, bmpPaint)
        c.restore()
    }

    private fun gradientBar(c: Canvas, l: Float, tp: Float, w: Float, h: Float, c1: Int, c2: Int, f: Float) {
        if (w < 0.5f) return
        fill.style = Paint.Style.FILL
        fill.shader = LinearGradient(l, tp, l + w, tp, withAlpha(c1, f), withAlpha(c2, f), Shader.TileMode.CLAMP)
        val rr = min(h / 2f, w / 2f)
        c.drawRoundRect(l, tp, l + w, tp + h, rr, rr, fill)
        fill.shader = null
    }

    private fun renderRound(c: Canvas, t: TargetInfo, f: Float) {
        val x = dp(16f)
        val y = dp(16f)
        val w = dp(170f)
        val h = dp(52f)
        fxBehind(c, x, y, x + w, y + h, dp(7f), Palette.accent, f)
        rrect(c, x, y, x + w, y + h, dp(7f), ((((glassA(118f) * f).toInt()) shl 24) or Palette.bgRgb))
        rim(c, x, y, x + w, y + h, dp(7f), f)
        blurR = dp(7f); blurK = f
        val cs = scale.value * max(0.7f, f)
        val slot = dp(40f)
        val hs = slot * cs
        drawHead(c, t, x + dp(6f) + (slot - hs) / 2f, y + (h - hs) / 2f, hs, dp(7f) * cs, f)
        val cx = x + dp(6f) + slot + dp(9f)
        val slideOff = slide.value * d
        val white = withAlpha(WHITE, f)
        text(c, t.name, cx, y + dp(20f) + slideOff, 13f, white, bold = true)
        val sub = String.format(Locale.US, "%.1fm  ·  %d/%d", t.distance, ceil(t.health).toInt(), ceil(t.maxHealth).toInt())
        text(c, sub, cx, y + dp(32f) + slideOff, 10.5f, withAlpha(Palette.hudText2, f))
        // 血条
        val bx = cx
        val by = y + dp(38f)
        val bw = x + w - dp(9f) - bx
        val bh = dp(5f)
        rrect(c, bx, by, bx + bw, by + bh, dp(3f), ((((100f * f).toInt()) shl 24) or Palette.bgRgb))
        rrect(c, bx, by, bx + bw * lag.value, by + bh, dp(3f), (((120f * f).toInt()) shl 24) or 0x636363)
        gradientBar(c, bx, by, bw * hp.value * contentT.value, bh,
            HudTheme.flow(cfg.theme, 0), HudTheme.flow(cfg.theme, 4), f)
        content.set(x, y, x + w, y + h)
        hasContent = true
    }

    private fun renderRise(c: Canvas, t: TargetInfo, f: Float) {
        val x = dp(16f)
        val y = dp(16f)
        val w = dp(216f)
        val h = dp(60f)
        fxBehind(c, x, y, x + w, y + h, dp(12f), Palette.accent, f)
        rrect(c, x, y, x + w, y + h, dp(12f), ((((glassA(100f) * f).toInt()) shl 24) or Palette.bgRgb))
        rim(c, x, y, x + w, y + h, dp(12f), f)
        blurR = dp(12f); blurK = f
        val hs = dp(48f) * scale.value * max(0.7f, f)
        val slot = dp(48f)
        drawHead(c, t, x + dp(6f) + (slot - hs) / 2f, y + (h - hs) / 2f, hs, dp(10f), f)
        val cx = x + dp(6f) + slot + dp(8f)
        val slideOff = slide.value * d
        // "Name: " + 逐字渐变的名字
        val prefix = "Name: "
        val nameBase = y + dp(23f) + slideOff
        text(c, prefix, cx, nameBase, 14f, withAlpha(Palette.hudText, f), bold = true)
        var nx = cx + measure(prefix, 14f, true)
        for ((i, ch) in t.name.withIndex()) {
            val s = ch.toString()
            text(c, s, nx, nameBase, 14f, withAlpha(HudTheme.flow(cfg.theme, i), f), bold = true)
            nx += measure(s, 14f, true)
        }
        // 胶囊血条 + 光晕
        val bx = cx
        val bh = dp(10f)
        val by = y + dp(34f)
        val bw = dp(112f)
        rrect(c, bx, by, bx + bw, by + bh, bh / 2f, ((((100f * f).toInt()) shl 24) or Palette.bgRgb))
        val fillW = bw * hp.value * contentT.value
        val c1 = HudTheme.flow(cfg.theme, 0)
        val c2 = HudTheme.flow(cfg.theme, 4)
        if (fillW > 0.5f) {
            for (k in 3 downTo 1) {
                val g = dp(1.6f) * k
                rrect(c, bx - g, by - g, bx + fillW + g, by + bh + g, bh / 2f + g, withAlpha(c1, 0.07f * f))
            }
        }
        gradientBar(c, bx, by, fillW, bh, c1, c2, f)
        val hpText = String.format(Locale.US, "%.1f", t.health)
        text(c, hpText, x + w - dp(8f), centerBaseline(by + bh / 2f, 12f, true), 12f, withAlpha(Palette.hudText, f),
            bold = true, align = Paint.Align.RIGHT)
        content.set(x, y, x + w, y + h)
        hasContent = true
    }

    private companion object {
        val PREVIEW = TargetInfo("Steve", 14f, 20f, 3.2f)
    }
}

// =====================================================================
// 开关提示：Southside / Simple / Akarin / Naven
// =====================================================================

/**
 * 开关提示。参考开源项目的 NotificationHud：
 *  - 卡片贴在窗口右下角，最旧的在最下面，新的一张叠在前一张的上方；从右侧滑入（带一点上浮），停留一会儿后向右滑出
 *  - 每次开关都是一张新卡片，不会改动已有的卡片（同一个模块连续开关，会叠出两张）
 *  - 最多 5 张，超出时最旧的先退场；退场的卡片原地滑出，不再占位，其余卡片顺滑下落补位
 *  - 四种样式：Southside（深色大卡片 + 图标 + 标题 + 底部计时条）、Simple（深色小卡片 + 图标 + 一行字）、
 *    Akarin（半透明深色 + 左侧渐变色条 + 两行字）、Naven（纯色：开启绿 / 关闭红）
 */
class NotifyHudView(ctx: Context) : HudCanvasView(ctx) {
    private class Entry(val name: String, val enabled: Boolean, val born: Long, val sticky: Boolean) {
        /** 自定义消息（HudNotify）：title / msg / kind（0 信息 1 成功 2 警告 3 错误）；模块开关提示 custom = false */
        var custom = false
        var title = ""
        var msg = ""
        var kind = 0
        var style = 0
        var w = 0f
        var h = 0f
        var spacing = 0f
        var exiting = false
        var exitStart = 0L
        val y = Tween(0f)
        var placed = false
    }

    private val entries = ArrayList<Entry>()
    private val cardBlur = ArrayList<Float>()   // 本帧每张卡片的模糊区域：x, y, w, h, 圆角, 透明度（窗口内坐标）
    private val previews = ArrayList<Entry>()
    private var previewKey = ""
    private val iconPath = Path()
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun windowW() = dp(CARD_MAX + SLIDE_ROOM + 16f).toInt()
    override fun windowH() = dp(16f + MAX_CARDS * 62f).toInt()

    /** 默认摆放用：卡片静止时的右边缘离窗口右边缘多远（让默认位置下卡片正好贴屏幕右侧） */
    fun restInset(): Int = dp(SLIDE_ROOM).toInt()

    /** 每次调用都新增一张卡片，不复用、不修改已有的卡片 */
    fun push(name: String, enabled: Boolean) {
        if (!enabled && !cfg.b("off")) return
        val now = SystemClock.uptimeMillis()
        val e = Entry(name, enabled, now, false)
        measureEntry(e)
        entries.add(e)
        // 超过上限：最旧的开始退场
        while (entries.count { !it.exiting } > MAX_CARDS) {
            val old = entries.first { !it.exiting }
            old.exiting = true
            old.exitStart = now
        }
        kick()
    }

    private fun line1x(e: Entry) = if (e.custom) e.title else e.name
    private fun line2x(e: Entry, st: String) = if (e.custom) e.msg else st
    private fun status(e: Entry) = tr(if (e.enabled) "已开启" else "已关闭")

    /** 自定义消息卡片 */
    fun pushMessage(title: String, message: String, kind: Int) {
        val now = SystemClock.uptimeMillis()
        val e = Entry(title, true, now, false)
        e.custom = true; e.title = title; e.msg = message; e.kind = kind.coerceIn(0, 3)
        measureEntry(e)
        entries.add(e)
        while (entries.count { !it.exiting } > MAX_CARDS) {
            val old = entries.first { !it.exiting }
            old.exiting = true; old.exitStart = now
        }
        kick()
    }

    private fun holdMs(): Float = when (cfg.i("hold")) { 1 -> 3000f; 2 -> 5000f; else -> 1800f }

    /** 截断到 maxW 以内，超出加省略号 */
    private fun fit(s: String, sizeDp: Float, bold: Boolean, maxW: Float): String {
        if (measure(s, sizeDp, bold) <= maxW) return s
        var n = s.length
        while (n > 1 && measure(s.substring(0, n) + "…", sizeDp, bold) > maxW) n--
        return s.substring(0, n) + "…"
    }

    private fun kindColor(k: Int): Int = when (k) {
        1 -> 0xFF3E8E5A.toInt()
        2 -> 0xFFD39B2F.toInt()
        3 -> 0xFFB5483A.toInt()
        else -> Palette.accent
    }

    private fun measureEntry(e: Entry) {
        e.style = cfg.i("style")
        val st = status(e)
        if (e.style == 4) { // Material：大圆角卡片，图标 + 标题 + 副标题
            val t1 = if (e.custom) e.title else e.name
            val t2 = if (e.custom) e.msg else st
            e.w = min(max(dp(156f), dp(16f + 22f + 14f + 18f) + max(measure(t1, 14f, true), measure(t2, 11.5f))), dp(CARD_MAX))
            e.h = dp(54f); e.spacing = dp(8f)
            return
        }
        if (e.custom) {
            val tm = measure(e.title, 12.5f, true)
            val mm = measure(e.msg, 11f)
            when (e.style) {
                1 -> { e.w = dp(8f) * 2f + dp(18f) + dp(8f) + measure(e.title + "  " + e.msg, 13f) + dp(4f); e.h = dp(36f); e.spacing = dp(5f) }
                2 -> { e.w = max(dp(132f), dp(12f) + max(tm, mm) + dp(12f)); e.h = dp(38f); e.spacing = dp(5f) }
                3 -> { e.w = measure(e.title + "  " + e.msg, 13f) + dp(20f); e.h = dp(26f); e.spacing = dp(4f) }
                else -> { e.w = max(dp(196f), dp(8f) + dp(22f) + dp(8f) + max(tm, mm) + dp(12f)); e.h = dp(48f); e.spacing = dp(6f) }
            }
            e.w = min(e.w, dp(CARD_MAX))
            return
        }
        when (e.style) {
            1 -> { // Simple
                e.w = dp(8f) * 2f + dp(18f) + dp(8f) + measure(e.name + " " + st, 13f) + dp(4f)
                e.h = dp(36f); e.spacing = dp(5f)
            }
            2 -> { // Akarin
                e.w = max(dp(132f), dp(12f) + measure(st + " " + e.name, 12f) + dp(12f))
                e.h = dp(38f); e.spacing = dp(5f)
            }
            3 -> { // Naven
                e.w = measure(e.name + " " + st, 13f) + dp(20f)
                e.h = dp(26f); e.spacing = dp(4f)
            }
            else -> { // Southside
                e.w = dp(196f)
                e.h = dp(48f); e.spacing = dp(6f)
            }
        }
        e.w = min(e.w, dp(CARD_MAX))
    }

    override fun collectBlur(out: MutableList<Float>, ox: Float, oy: Float) {
        var i = 0
        while (i + 5 < cardBlur.size) {
            out.add(ox + cardBlur[i]); out.add(oy + cardBlur[i + 1])
            out.add(cardBlur[i + 2]); out.add(cardBlur[i + 3])
            out.add(cardBlur[i + 4]); out.add(0.30f * cfg.opacity * cardBlur[i + 5])
            i += 6
        }
    }

    override fun render(c: Canvas, now: Long) {
        cardBlur.clear()
        // 编辑模式：没有真实卡片时放两张不会消失的示例卡片（展示“新的叠在上面”）
        val key = cfg.i("style").toString() + "@" + d
        if (!editing || entries.isNotEmpty()) previews.clear()
        if (editing && entries.isEmpty() && (previews.isEmpty() || previewKey != key)) {
            previews.clear()
            previewKey = key
            previews.add(Entry("KillAura", false, now, true).also { measureEntry(it) })
            previews.add(Entry("Sprint", true, now, true).also { measureEntry(it) })
        }

        // 生命周期：停留时间到了就退场；退场动画结束后移除
        val iter = entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (!e.exiting && !e.sticky && now - e.born - IN_MS.toLong() >= holdMs().toLong()) { e.exiting = true; e.exitStart = now }
            if (e.exiting && now - e.exitStart >= EXIT_MS) iter.remove()
        }
        val list = ArrayList<Entry>(entries)
        list.addAll(previews)
        if (list.isEmpty()) return
        needFrame = true

        val winW = getWidth().toFloat()
        val winH = getHeight().toFloat()
        val restRight = winW - dp(SLIDE_ROOM)

        // 排位：只有没在退场的卡片占位，最旧的在最下面，新的往上叠
        var nextY = winH - dp(16f)
        for (e in list) {
            if (e.exiting) continue
            val top = nextY - e.h
            nextY = top - e.spacing
            if (!e.placed) { e.y.set(top); e.placed = true } else e.y.animate(top, 260L, Ease.OUT4, now)
        }

        var minL = Float.MAX_VALUE
        var minT = Float.MAX_VALUE
        var maxB = 0f
        for (e in list) {
            e.y.tick(now)
            val age = (now - e.born).toFloat()
            val slideT = Ease.OUT4((age / IN_MS).coerceIn(0f, 1f))
            val fadeT = Ease.OUT3((age / (IN_MS * 0.6f)).coerceIn(0f, 1f))
            var offset = dp(SLIDE_ROOM) * (1f - slideT)
            val rise = dp(10f) * (1f - Ease.OUT3((age / IN_MS).coerceIn(0f, 1f)))
            var alpha = fadeT
            if (e.exiting) {
                val q = ((now - e.exitStart).toFloat() / EXIT_MS).coerceIn(0f, 1f)
                offset += dp(70f) * Ease.IN3(q)
                alpha *= 1f - q
            }
            val left = restRight - e.w + offset
            val y = e.y.value + rise
            if (alpha > 0.01f) drawCard(c, e, left, y, alpha, now)
            if (!e.exiting) {
                minL = min(minL, restRight - e.w)
                minT = min(minT, e.y.value)
                maxB = max(maxB, e.y.value + e.h)
            }
        }
        if (minL != Float.MAX_VALUE) {
            content.set(minL, minT, restRight, maxB)
            hasContent = true
        }
    }

    private fun drawCard(c: Canvas, e: Entry, l: Float, t: Float, a: Float, now: Long) {
        val r = l + e.w
        val b = t + e.h
        val st = status(e)
        // 毛玻璃：底板变半透明，并登记一块模糊区域（Akarin / Naven 本身带半透明或彩色底，也一样处理）
        val glass = cfg.b("glass") && blurOn
        val dark = mulAlpha(if (glass) (Palette.bgRgb or (0x78 shl 24)) else (Palette.bgRgb or (0xFF shl 24)), a)
        if (glass) {
            val rad = if (e.style == 0) dp(8f) else if (e.style == 4) dp(18f) else dp(7f)
            cardBlur.add(l); cardBlur.add(t); cardBlur.add(e.w); cardBlur.add(e.h); cardBlur.add(rad); cardBlur.add(a)
        }
        val fxRad = when (e.style) { 0 -> dp(8f); 4 -> dp(18f); else -> dp(7f) }
        fxBehind(c, l, t, r, b, fxRad, if (e.custom) kindColor(e.kind) else Palette.accent, a)
        val ik = if (e.custom) e.kind else if (e.enabled) 1 else 4      // 图标种类：0 信息 1 成功 2 警告 3 错误 4 已关闭
        val line1 = if (e.custom) e.title else tr("模块")
        val line2 = if (e.custom) e.msg else e.name + " " + st
        val single = if (e.custom) e.title + "  " + e.msg else e.name + " " + st
        when (e.style) {
            1 -> { // Simple
                rrect(c, l, t, r, b, dp(7f), dark)
                drawIcon(c, l + dp(8f) + dp(9f), t + e.h / 2f, dp(9f), ik, a)
                val tx = l + dp(8f) + dp(18f) + dp(8f)
                text(c, fit(single, 13f, false, r - tx - dp(6f)), tx, centerBaseline(t + e.h / 2f, 13f), 13f, withAlpha(Palette.hudText, a))
            }
            2 -> { // Akarin
                rrect(c, l, t, r, b, dp(7f), (((if (glass) 110f else 255f) * a).toInt() shl 24) or Palette.bgRgb)
                val c1 = if (e.custom) kindColor(e.kind) else HudTheme.flow(cfg.theme, 0)
                val c2 = if (e.custom) kindColor(e.kind) else HudTheme.flow(cfg.theme, 1)
                val sy = t + dp(8f)
                val sh = dp(15f)
                rrect(c, l, sy - dp(1.5f), l + dp(3.5f), sy + sh + dp(1.5f), dp(2f), withAlpha(c1, a * 0.13f))
                fill.style = Paint.Style.FILL
                fill.shader = LinearGradient(l, sy, l, sy + sh, withAlpha(c1, a), withAlpha(c2, a), Shader.TileMode.CLAMP)
                c.drawRoundRect(l, sy, l + dp(2f), sy + sh, dp(1f), dp(1f), fill)
                fill.shader = null
                text(c, fit(line1, 10.5f, false, e.w - dp(20f)), l + dp(10f), t + dp(16f), 10.5f, mulAlpha(Palette.hudText, a))
                val l2 = if (e.custom) line2 else st + " " + e.name
                text(c, fit(l2, 12f, false, e.w - dp(20f)), l + dp(10f), t + dp(31f), 12f, mulAlpha(withAlpha(Palette.hudText, 0.63f), a))
            }
            3 -> { // Naven
                val base = if (e.custom) kindColor(e.kind) else if (e.enabled) 0xFF3E8E5A.toInt() else 0xFF9A4338.toInt()
                rrect(c, l, t, r, b, dp(7f), mulAlpha(mulAlpha(base, if (glass) 0.62f else 1f), a))
                text(c, fit(single, 13f, false, e.w - dp(20f)), l + dp(10f), centerBaseline(t + e.h / 2f, 13f), 13f, withAlpha(Palette.hudText, a))
            }
            4 -> { // Material
                val rad = dp(18f)
                val tint = mixColor(Palette.bgRgb or (0xFF shl 24), 0xFF6A6880.toInt(), 0.22f)
                rrect(c, l, t, r, b, rad, mulAlpha(withAlpha(tint, if (glass) 0.64f else 0.96f), a))
                rim(c, l, t, r, b, rad, a)
                val ic = when (ik) {
                    1 -> Palette.accent
                    2 -> 0xFFF2F0F6.toInt()
                    3 -> 0xFFE0705F.toInt()
                    4 -> Palette.hudText2
                    else -> Palette.accent
                }
                val gk = when (ik) { 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 3; else -> 0 }
                val cy = t + dp(25f)
                drawMatGlyph(c, gk, l + dp(16f) + dp(11f), cy, dp(11f), mulAlpha(ic, a))
                val tx = l + dp(16f + 22f + 14f)
                text(c, fit(line1x(e), 14f, true, r - tx - dp(14f)), tx, cy - dp(2f), 14f, withAlpha(Palette.hudText, a), bold = true)
                text(c, fit(line2x(e, st), 11.5f, false, r - tx - dp(14f)), tx, cy + dp(14f), 11.5f, mulAlpha(Palette.hudText2, a))
                if (cfg.b("bar")) {
                    val hold = if (e.sticky) 0.6f else
                        (((if (e.exiting) e.exitStart else now) - e.born - IN_MS.toLong()).toFloat() / holdMs()).coerceIn(0f, 1f)
                    val bx0 = l + dp(18f); val bx1 = r - dp(18f); val by = b - dp(8f)
                    rrect(c, bx0, by, bx1, by + dp(2f), dp(1f), withAlpha(Palette.hudText, 0.14f * a))
                    if (hold < 1f) rrect(c, bx0, by, bx0 + (bx1 - bx0) * (1f - hold), by + dp(2f), dp(1f), withAlpha(Palette.hudText, 0.9f * a))
                }
            }
            else -> { // Southside
                val rad = dp(8f)
                rrect(c, l, t, r, b, rad, dark)
                val barH = if (cfg.b("bar")) dp(3f) else 0f
                if (barH > 0f) {
                    // 底部计时条：先在卡片范围内铺灰色，再铺进度（用卡片圆角裁剪，所以条的两端跟着圆角走）
                    val hold = if (e.sticky) 0.6f else
                        (((if (e.exiting) e.exitStart else now) - e.born - IN_MS.toLong()).toFloat() / holdMs()).coerceIn(0f, 1f)
                    c.save()
                    clipRound(c, l, t, r, b, rad)
                    rrect(c, l, b - barH, r, b + 1f, 0f, mulAlpha(0xFF3A3A3A.toInt(), a))
                    rrect(c, l, b - barH, l + e.w * hold, b + 1f, 0f, withAlpha(if (e.custom) kindColor(e.kind) else WHITE, a))
                    c.restore()
                }
                val cy = t + (e.h - barH) / 2f
                drawIcon(c, l + dp(8f) + dp(11f), cy, dp(11f), ik, a)
                val tx = l + dp(8f) + dp(22f) + dp(8f)
                text(c, fit(line1, 12.5f, true, r - tx - dp(8f)), tx, cy - dp(2f), 12.5f, withAlpha(Palette.hudText, a), bold = true)
                text(c, fit(line2, 10.5f, false, r - tx - dp(8f)), tx, cy + dp(12f), 10.5f, mulAlpha(Palette.hudText2, a))
            }
        }
    }

    /** 圆形图标。kind：0 信息（主题色 + i）1 成功（白底对勾）2 警告（琥珀 + !）3 错误（红 + ×）4 已关闭（灰底叉） */
    private fun drawIcon(c: Canvas, cx: Float, cy: Float, r: Float, kind: Int, a: Float) {
        val ink = mulAlpha((Palette.bgRgb or (0xFF shl 24)), a)
        val bg = when (kind) {
            1 -> withAlpha(WHITE, a)
            2 -> mulAlpha(0xFFD39B2F.toInt(), a)
            3 -> mulAlpha(0xFFB5483A.toInt(), a)
            0 -> mulAlpha(Palette.accent, a)
            else -> mulAlpha(0x66FFFFFF, a)
        }
        rrect(c, cx - r, cy - r, cx + r, cy + r, r, bg)
        strokeP.strokeWidth = r * 0.18f
        strokeP.color = if (kind == 1 || kind == 2 || kind == 4) ink else mulAlpha(0xFFFFFFFF.toInt(), a)
        iconPath.reset()
        when (kind) {
            1 -> {
                iconPath.moveTo(cx - r * 0.42f, cy + r * 0.02f)
                iconPath.lineTo(cx - r * 0.10f, cy + r * 0.34f)
                iconPath.lineTo(cx + r * 0.44f, cy - r * 0.30f)
            }
            0 -> {          // i：一竖 + 一点
                iconPath.moveTo(cx, cy - r * 0.02f); iconPath.lineTo(cx, cy + r * 0.42f)
                iconPath.moveTo(cx, cy - r * 0.40f); iconPath.lineTo(cx, cy - r * 0.38f)
            }
            2 -> {          // !
                iconPath.moveTo(cx, cy - r * 0.46f); iconPath.lineTo(cx, cy + r * 0.10f)
                iconPath.moveTo(cx, cy + r * 0.40f); iconPath.lineTo(cx, cy + r * 0.42f)
            }
            else -> {
                iconPath.moveTo(cx - r * 0.32f, cy - r * 0.32f); iconPath.lineTo(cx + r * 0.32f, cy + r * 0.32f)
                iconPath.moveTo(cx + r * 0.32f, cy - r * 0.32f); iconPath.lineTo(cx - r * 0.32f, cy + r * 0.32f)
            }
        }
        c.drawPath(iconPath, strokeP)
    }

    private companion object {
        const val CARD_MAX = 220f
        const val SLIDE_ROOM = 210f
        const val MAX_CARDS = 5
        const val IN_MS = 300f
        const val EXIT_MS = 220f
    }
}

// =====================================================================
// 编辑模式的对齐辅助线：整屏、不拦截触摸，只画吸附上的那几条线
// =====================================================================
class HudGuideView(ctx: Context) : View(ctx) {
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun show(vx: List<Float>, vy: List<Float>) {
        if (xs == vx && ys == vy) return
        xs.clear(); xs.addAll(vx)
        ys.clear(); ys.addAll(vy)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (xs.isEmpty() && ys.isEmpty()) return
        val d = resources.displayMetrics.density
        p.strokeWidth = 1.2f * d
        p.color = Palette.accent
        for (x in xs) c.drawLine(x, 0f, x, height.toFloat(), p)
        for (y in ys) c.drawLine(0f, y, width.toFloat(), y, p)
    }
}
