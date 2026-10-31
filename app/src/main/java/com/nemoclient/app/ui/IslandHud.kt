package com.nemoclient.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nemoclient.app.R
import com.nemoclient.app.data.HUD_VERSION
import com.nemoclient.app.data.BlocksFeed
import com.nemoclient.app.data.HudInfo
import com.nemoclient.app.data.HudOpt
import com.nemoclient.app.data.HudType
import com.nemoclient.app.data.ModuleRepo
import com.nemoclient.app.music.MusicListener
import com.nemoclient.app.music.MusicPlayer
import com.nemoclient.app.i18n.tr
import com.nemoclient.app.ui.endfield.HudAnimationOptions
import com.nemoclient.app.ui.endfield.HudAnimationState
import com.nemoclient.app.ui.endfield.HudAnimationStates
import com.nemoclient.app.ui.endfield.HudIconCatalog
import com.nemoclient.app.ui.endfield.HudNode
import com.nemoclient.app.ui.endfield.HudNodeState
import com.nemoclient.app.ui.endfield.HudTimeline
import com.nemoclient.app.ui.endfield.HudTimelines
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val ISL_WHITE: Int get() = Palette.ink

/** 灵动岛 / 设置面板共用的玻璃底色：和 ClickGUI 面板同一配方（暖墨色，开模糊时半透明，没有模糊时几乎不透明） */
internal fun islandGlass(blur: Boolean): Int = withAlpha(Palette.inkBlack, if (blur) 0.5f else 1f)

/**
 * 灵动岛 HUD（布局参考开源项目 Onyx 的 Watermark 灵动岛，配色 / 材质换成本客户端自己的）：
 *  常驻：[logo]  名称 / 版本·账号  ……  帧率
 *  事件：[状态圆标]  小字标签 / 主文字  ……  状态词，底部一条细进度条（剩余停留时间）
 * 事件来自模块开关或 HudNotify（信息 / 成功 / 警告 / 错误）。宽度用弹簧弹开，文字带一点纵向滚入，
 * 玻璃底色与编辑 HUD 时展开的设置面板完全一致。
 */
class IslandHudView(ctx: Context) : HudCanvasView(ctx) {
    private val logo: Bitmap? = try {
        BitmapFactory.decodeResource(resources, R.drawable.ic_logo_r)
    } catch (e: Throwable) {
        null
    }
    private val logoSrc = Rect()
    private val logoDst = RectF()
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val islandW = Spring(0f, 320f, 0.72f)
    private val islandH = Spring(38f * 0f, 320f, 0.78f)
    private var islandInit = false
    private val mode = Tween(0f)

    /**
     * 任务队列：开关提示 / 消息 / 进度 / 音乐 都是一个“任务”，同时来多个就在岛里叠成多行（最新的在上面，最多 3 行）。
     * kind：0 信息 1 成功 2 警告 3 错误 4 已关闭（中性） 5 音乐。
     * 时间型任务（progress = null）按剩余停留时间走细进度条，到点自己退场；
     * 值型任务（给了 progress）进度条跟着值走，超过 5 秒没更新就退场；sticky 的任务（比如音乐）一直留到被 removeTask。
     */
    private class Task(val id: String) {
        var kind = 0
        var badge = ""
        var text = ""
        var state: String? = null
        var progress: Float? = null
        var born = 0L
        var updated = 0L
        var duration = 2400L
        var sticky = false
        val enter = Tween(0f)
        val shown = Tween(0f)
        var leaving = false
        var rowW = 0f
        var cap = 0f      // 这一行文字最大宽度（音乐行会按歌词长度放宽）
        var mkey = ""
    }
    private val tasks = ArrayList<Task>()

    override fun windowW() = dp(380f).toInt()
    override fun windowH() = if (cfg.i("style") == 1) dp(190f).toInt() else dp(112f).toInt()

    /** 模块开关提示（主线程调用） */
    fun push(name: String, on: Boolean) {
        if (cfg.i("style") == 1) {
            efTrigger(EfData("/// " + tr("模块").uppercase(), name, tr(if (on) "已开启" else "已关闭"), "",
                name, "", if (on) 1.0 else 0.0, "bolt", "power", efKindAccent(if (on) 1 else 4)))
            return
        }
        upsertTask("mod:$name", tr("模块"), name, tr(if (on) "已开启" else "已关闭"), if (on) 1 else 4, null, 2400L)
    }

    /** 通用消息（HudNotify） */
    fun pushMessage(title: String, message: String, kind: Int) {
        if (cfg.i("style") == 1) {
            val k = kind.coerceIn(0, 3)
            efTrigger(EfData("/// " + title.uppercase(), message, title, "", message, "", 1.0,
                arrayOf("bolt", "shield", "gauge", "power")[k], arrayOf("link", "shield", "gauge", "power")[k], efKindAccent(k)))
            return
        }
        upsertTask("msg:$title", title, message, null, kind.coerceIn(0, 3), null, 2400L)
    }

    /**
     * 任务队列入口（主线程）：id 相同就更新那一行。progress = null → 时间型（durationMs 后退场）；
     * 给了 progress（0..1）→ 值型，进度条跟着走，5 秒没更新就退场。
     */
    fun upsertTask(id: String, badge: String, text: String, state: String?, kind: Int, progress: Float?, durationMs: Long = 2400L, sticky: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        var t = tasks.firstOrNull { it.id == id }
        if (t == null) { t = Task(id); tasks.add(0, t); t.shown.set(progress ?: 1f) }
        t.badge = badge; t.text = text; t.state = state; t.kind = kind
        t.progress = progress; t.duration = durationMs; t.sticky = sticky
        t.born = now; t.updated = now; t.leaving = false
        t.enter.animate(1f, 280L, Ease.OUT3, now)
        if (progress == null && id.startsWith("mod:")) { // 材质样式里的开关：从相反位置滑过去
            val on = kind == 1
            t.shown.set(if (on) 0f else 1f); t.shown.animate(if (on) 1f else 0f, 320L, Ease.OUT3, now)
        }
        if (progress != null) t.shown.animate(progress.coerceIn(0f, 1f), 500L, Ease.OUT3, now)
        // 材质样式：新的模块开关提示立即顶掉旧的（不排队、不淡入淡出），开关本身的滑动动画照常播放
        if (cfg.i("style") == 2 && id.startsWith("mod:")) {
            val me: Task = t
            tasks.removeAll { it !== me && it.id.startsWith("mod:") }
            tasks.remove(me); tasks.add(0, me)
            me.enter.set(1f)
        }
        kick()
    }

    /** 音乐行（给将来的“后台音乐 + 歌词”用）：badge = 歌手，text = 歌名 / 当前歌词，state = “01:23 / 03:45”，progress = 播放进度 */
    fun upsertMusic(id: String, artist: String, title: String, timeText: String?, progress: Float) =
        upsertTask(id, artist, title, timeText, 5, progress, 0L, sticky = true)

    fun removeTask(id: String) {
        val t = tasks.firstOrNull { it.id == id } ?: return
        if (!t.leaving) { t.leaving = true; t.enter.animate(0f, 200L, Ease.IN3, SystemClock.uptimeMillis()); kick() }
    }

    // ---- 内置播放器联动 ----
    private val musicListener = MusicListener { changed -> musicChanged(changed) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); MusicPlayer.addListener(musicListener); musicChanged(false) }
    override fun onDetachedFromWindow() { MusicPlayer.removeListener(musicListener); super.onDetachedFromWindow() }

    private fun musicOn() = MusicPlayer.active && (MusicPlayer.playing || MusicPlayer.loading) && cfg.b("music")

    private fun musicChanged(trackChanged: Boolean) {
        val t = MusicPlayer.track
        if (cfg.i("style") == 1) {
            if (trackChanged && t != null && musicOn())
                efTrigger(EfData("/// NOW PLAYING", t.title, MusicPlayer.fmt(MusicPlayer.durMs), "", t.artist, "", 0.0,
                    "signal", "activity", 0xFF8E7CC3.toInt()))
            kick()
            return
        }
        if (!musicOn() || t == null) { removeTask("music"); return }
        val line = MusicPlayer.currentLine().ifEmpty { t.title }
        val prog = if (MusicPlayer.durMs > 0) (MusicPlayer.posMs.toFloat() / MusicPlayer.durMs).coerceIn(0f, 1f) else 0f
        upsertMusic("music", t.artist.ifEmpty { "♪" }, line, MusicPlayer.fmt(MusicPlayer.posMs), prog)
    }

    private fun kindColor(k: Int): Int = when (k) {
        1 -> 0xFF4FAE72.toInt()
        2 -> 0xFFD9A441.toInt()
        3 -> 0xFFD0604F.toInt()
        4 -> Palette.ink2
        5 -> 0xFF8E7CC3.toInt()
        else -> Palette.accent
    }

    private fun stats(): String {
        val parts = ArrayList<String>(2)
        if (HudInfo.fps >= 0) parts.add("${HudInfo.fps} fps")
        if (HudInfo.ping >= 0) parts.add("${HudInfo.ping} ms")
        return parts.joinToString("  ")
    }

    private fun drawLogo(c: Canvas, l: Float, t: Float, size: Float, alpha: Float) {
        val b = logo ?: return
        logoSrc.set(0, 0, b.width, b.height)
        logoDst.set(l, t, l + size, t + size)
        bmpPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        c.drawBitmap(b, logoSrc, logoDst, bmpPaint)
    }

    private fun fit(s: String, sizeDp: Float, bold: Boolean, maxW: Float): String {
        if (measure(s, sizeDp, bold) <= maxW) return s
        var n = s.length
        while (n > 1 && measure(s.substring(0, n) + "…", sizeDp, bold) > maxW) n--
        return s.substring(0, n) + "…"
    }

    // ---- 搭路方块计数（BlocksFeed）：做成一个“仪表”任务，数值 ≤ 阈值变红，进度 = 当前 / 本轮峰值 ----
    private var blocksPeak = 0
    private var blocksSync = 0L
    private var blocksOn = false

    private fun syncBlocks(now: Long) {
        if (now - blocksSync < 120L) return
        blocksSync = now
        val n = BlocksFeed.count
        val live = cfg.b("blocks") && n >= 0 && now - BlocksFeed.stamp < 1500L
        if (!live) {
            if (blocksOn) { blocksOn = false; blocksPeak = 0; removeTask("blocks") }
            return
        }
        blocksOn = true
        if (n > blocksPeak) blocksPeak = n
        val fr = if (blocksPeak > 0) n.toFloat() / blocksPeak else 0f
        upsertTask("blocks", "Blocks", "Scaffold", n.toString(), if (n <= BlocksFeed.LOW) 3 else 0, fr, 0L, sticky = true)
    }

    override fun render(c: Canvas, now: Long) {
        needFrame = true
        syncBlocks(now)
        if (cfg.i("style") == 1) { renderEndfield(c, now); return }
        if (cfg.i("style") == 2) { renderMaterial(c, now); return }
        val pad = dp(12f)
        val icon = dp(18f)
        val gap = dp(9f)
        val st = stats()
        val textMax = dp(190f)

        // ---- 常驻内容的宽度
        val nameW = measure("rlin client", 12.5f, true)
        val subText = HUD_VERSION + "  ·  " + HudInfo.user
        val subW = measure(subText, 10.5f)
        val stW = measure(st, 11.5f)
        val tIdle = pad + icon + gap + max(nameW, subW) + (if (st.isEmpty()) 0f else gap * 2f + stW) + pad

        // ---- 任务：到点退场 → 动画结束后移除
        val iter = tasks.iterator()
        while (iter.hasNext()) {
            val t = iter.next()
            if (!t.leaving && !t.sticky) {
                val expired = if (t.progress == null) now - t.born >= t.duration else now - t.updated >= VALUE_TIMEOUT_MS
                if (expired) { t.leaving = true; t.enter.animate(0f, 200L, Ease.IN3, now) }
            }
            t.enter.tick(now); t.shown.tick(now)
            if (t.leaving && t.enter.value <= 0.01f && !t.enter.running) iter.remove()
        }
        val rows = ArrayList<Task>(MAX_ROWS)
        for (t in tasks) { if (rows.size < MAX_ROWS) rows.add(t) }
        val active = rows.any { !it.leaving }
        mode.animate(if (active) 1f else 0f, 240L, Ease.OUT3, now)
        val m = mode.tick(now)

        var tRows = 0f
        for (t in rows) {
            val key = t.badge + "|" + t.text + "|" + t.state + "|" + d
            if (t.mkey != key) {
                t.mkey = key
                val sw = if (t.state != null) measure(t.state!!, 11.5f) else 0f
                val fixed = pad + icon + gap + pad + (if (sw > 0f) gap * 2f + sw else 0f)
                // 音乐行：岛的长度跟着歌词走，直到顶到窗口宽度
                t.cap = if (t.id == "music") (getWidth().toFloat() - dp(8f) - fixed).coerceAtLeast(textMax) else (getWidth().toFloat() - dp(8f) - fixed).coerceIn(dp(150f), dp(270f))
                val bw = min(measure(t.badge, 10f), t.cap)
                val mw = measure(fit(t.text, 12.5f, true, t.cap), 12.5f, true)
                t.rowW = fixed + max(bw, mw)
            }
            tRows = max(tRows, t.rowW)
        }
        val rowH = if (rows.size <= 1) dp(38f) else dp(34f)
        val tH = if (rows.isEmpty()) dp(38f) else rows.size * rowH + (if (rows.size > 1) dp(8f) else 0f)

        if (!islandInit) { islandW.set(tIdle); islandH.set(dp(38f)); islandInit = true }
        islandW.target = if (rows.isNotEmpty()) tRows else tIdle
        islandH.target = tH
        islandW.step(now); islandH.step(now)

        val w = max(islandW.value, dp(38f))
        val h = max(islandH.value, dp(38f))
        val x0 = (getWidth().toFloat() - w) / 2f
        val y0 = dp(16f)
        val rad = min(h / 2f, dp(26f))
        fxBehind(c, x0, y0, x0 + w, y0 + h, rad, Palette.accent, 1f)
        rrect(c, x0, y0, x0 + w, y0 + h, rad, islandGlass(blurOn))
        rim(c, x0, y0, x0 + w, y0 + h, rad)
        blurR = rad
        c.save()
        clipRound(c, x0, y0, x0 + w, y0 + h, rad)

        val ia = 1f - m
        if (ia > 0.01f) {
            val cy = y0 + dp(19f)
            val roll = -dp(5f) * m
            var x = x0 + (w - tIdle) / 2f + pad
            drawLogo(c, x, cy - icon / 2f, icon, ia)
            x += icon + gap
            text(c, "rlin client", x, cy - dp(3f) + roll, 12.5f, mulAlpha(Palette.ink, ia), bold = true)
            text(c, subText, x, cy + dp(11f) + roll, 10.5f, mulAlpha(Palette.ink2, ia))
            if (st.isNotEmpty()) {
                text(c, st, x0 + (w + tIdle) / 2f - pad, centerBaseline(cy, 11.5f) + roll, 11.5f, mulAlpha(Palette.ink2, ia),
                    shadow = false, align = Paint.Align.RIGHT)
            }
        }
        if (m > 0.01f || rows.isNotEmpty()) {
            val top0 = y0 + (if (rows.size > 1) dp(4f) else 0f)
            for ((i, t) in rows.withIndex()) {
                val a = t.enter.value.coerceIn(0f, 1f)
                if (a <= 0.01f) continue
                val rt = top0 + i * rowH + dp(6f) * (1f - t.enter.value)
                val cy = rt + rowH / 2f
                val col = kindColor(t.kind)
                var x = x0 + (w - t.rowW) / 2f + pad
                dotP.color = mulAlpha(col, a)
                c.drawCircle(x + icon / 2f, cy - dp(1f), icon / 2f, dotP)
                text(c, if (t.id == "blocks") "▦" else glyph(t.kind), x + icon / 2f, centerBaseline(cy - dp(1f), 11f, true), 11f,
                    mulAlpha(if (t.kind == 4) Palette.inkBlack else 0xFFFFFFFF.toInt(), a), bold = true, shadow = false, align = Paint.Align.CENTER)
                x += icon + gap
                text(c, fit(t.badge, 10f, false, t.cap), x, cy - dp(4f), 10f, mulAlpha(Palette.ink2, a))
                text(c, fit(t.text, 12.5f, true, t.cap), x, cy + dp(10f), 12.5f, mulAlpha(Palette.ink, a), bold = true)
                if (t.state != null) {
                    text(c, t.state!!, x0 + (w + t.rowW) / 2f - pad, centerBaseline(cy - dp(1f), 11.5f), 11.5f,
                        mulAlpha(if (t.kind == 4) Palette.ink2 else col, a), shadow = false, align = Paint.Align.RIGHT)
                }
                // 行底细进度条：时间型 = 剩余停留时间；值型 = 进度值
                val frac = if (t.progress != null) t.shown.value else if (t.sticky) 1f else (1f - (now - t.born).toFloat() / t.duration).coerceIn(0f, 1f)
                val bx0 = x0 + (w - t.rowW) / 2f + pad
                val bx1 = x0 + (w + t.rowW) / 2f - pad
                val by = rt + rowH - dp(5f)
                rrect(c, bx0, by, bx1, by + dp(2f), dp(1f), mulAlpha(Palette.ink, 0.14f * a))
                if (frac > 0f) rrect(c, bx0, by, bx0 + (bx1 - bx0) * frac, by + dp(2f), dp(1f), mulAlpha(col, a))
                if (i < rows.size - 1) rrect(c, x0 + pad, rt + rowH, x0 + w - pad, rt + rowH + 1f, 0f, mulAlpha(Palette.ink, 0.08f * a))
            }
        }
        c.restore()
        content.set(x0, y0, x0 + w, y0 + h)
        hasContent = true
        if (rows.isEmpty() && abs(islandW.value - islandW.target) < 0.5f && abs(islandH.value - islandH.target) < 0.5f && m < 0.01f) lazyMs = 100L
    }

    // ---------------- 样式：材质（参考 Material 风格的超圆角胶囊）----------------
    private fun splitState(s: String?): Pair<String, String> {
        if (s == null) return "" to ""
        val i = s.indexOf(' ')
        return if (i > 0) s.substring(0, i) to s.substring(i + 1) else s to ""
    }

    private fun matGlyphKind(t: Task): Int = when {
        t.id.startsWith("mod:") -> 4
        t.id == "blocks" -> 6
        t.kind == 1 -> 1
        t.kind == 2 -> 2
        t.kind == 3 -> 3
        t.kind == 5 -> 6
        else -> 0
    }

    private fun matColor(t: Task): Int = when {
        t.id.startsWith("mod:") -> if (t.kind == 1) Palette.accent else Palette.ink2
        t.id == "blocks" -> if (t.kind == 3) 0xFFE0705F.toInt() else Palette.accent
        t.kind == 2 -> 0xFFE9B04A.toInt()
        t.kind == 3 -> 0xFFE0705F.toInt()
        t.kind == 4 -> Palette.ink2
        else -> Palette.accent
    }

    private fun renderMaterial(c: Canvas, now: Long) {
        val iter = tasks.iterator()
        while (iter.hasNext()) {
            val t = iter.next()
            if (!t.leaving && !t.sticky) {
                val expired = if (t.progress == null) now - t.born >= t.duration else now - t.updated >= VALUE_TIMEOUT_MS
                if (expired) { t.leaving = true; t.enter.animate(0f, 200L, Ease.IN3, now) }
            }
            t.enter.tick(now); t.shown.tick(now)
            if (t.leaving && t.enter.value <= 0.01f && !t.enter.running) iter.remove()
        }
        val cur = tasks.firstOrNull { !it.leaving }
        mode.animate(if (cur != null) 1f else 0f, 220L, Ease.OUT3, now)
        val m = mode.tick(now)

        val pad = dp(16f); val icon = dp(22f); val gap = dp(12f)
        val swW = dp(44f); val swH = dp(26f)
        val st = stats()

        // ---- 目标尺寸（按当前最新任务的类型）
        var tw: Float
        val th: Float
        if (cur == null) {
            tw = pad + icon + gap + measure("rlin client", 15f, true) + (if (st.isEmpty()) 0f else gap * 2f + measure(st, 12f)) + pad
            th = dp(46f)
        } else if (cur.id.startsWith("mod:") && cur.progress == null) {
            tw = max(pad + icon + gap + measure(fit(cur.text, 15f, true, dp(170f)), 15f, true) + gap * 2f + swW + pad, dp(196f))
            th = dp(52f)
        } else if (cur.progress != null) {
            val sp = splitState(cur.state)
            val big = measure(sp.first, 20f, true) + (if (sp.second.isEmpty()) 0f else dp(4f) + measure(sp.second, 12f))
            val fixed = pad + icon + gap + gap * 2f + big + pad
            val isMusic = cur.id == "music"
            val cap = if (isMusic) (getWidth().toFloat() - dp(8f) - fixed).coerceAtLeast(dp(180f)) else dp(180f)
            val upper = if (isMusic) getWidth().toFloat() - dp(8f) else dp(340f)   // 音乐：长度跟着歌词走
            tw = (fixed + measure(fit(cur.text, 15f, true, cap), 15f, true)).coerceIn(dp(262f), max(dp(262f), upper))
            th = dp(84f)
        } else {
            val bw = if (cur.badge.isEmpty()) 0f else measure(fit(cur.badge, 11f, false, dp(170f)), 11f)
            val mw = measure(fit(cur.text, 14.5f, true, dp(170f)), 14.5f, true)
            val s2 = if (cur.state != null) measure(cur.state!!, 12f) else 0f
            tw = max(pad + icon + gap + max(bw, mw) + (if (s2 > 0f) gap * 2f + s2 else 0f) + pad, dp(180f))
            th = dp(54f)
        }
        tw = min(tw, getWidth().toFloat() - dp(8f))
        if (!islandInit) { islandW.set(tw); islandH.set(th); islandInit = true }
        islandW.target = tw; islandH.target = th
        islandW.step(now); islandH.step(now)

        val w = max(islandW.value, dp(38f))
        val h = max(islandH.value, dp(38f))
        val x0 = (getWidth().toFloat() - w) / 2f
        val y0 = dp(16f)
        val rad = min(h / 2f, dp(32f)) // 展开后仍是超圆角
        val plate = mixColor(Palette.inkBlack, Palette.accent, 0.14f)
        fxBehind(c, x0, y0, x0 + w, y0 + h, rad, Palette.accent, 1f)
        rrect(c, x0, y0, x0 + w, y0 + h, rad, withAlpha(plate, if (blurOn) 0.58f else 1f))
        rim(c, x0, y0, x0 + w, y0 + h, rad)
        blurR = rad
        c.save()
        clipRound(c, x0, y0, x0 + w, y0 + h, rad)

        val lx = x0 + pad
        val rx = x0 + w - pad
        val cy = y0 + h / 2f
        val ia = 1f - m
        if (ia > 0.01f) {
            drawLogo(c, lx, cy - icon / 2f, icon, ia)
            text(c, "rlin client", lx + icon + gap, centerBaseline(cy, 15f, true), 15f, mulAlpha(Palette.ink, ia), bold = true)
            if (st.isNotEmpty()) text(c, st, rx, centerBaseline(cy, 12f), 12f, mulAlpha(Palette.ink2, ia), shadow = false, align = Paint.Align.RIGHT)
        }
        val dt = cur ?: tasks.firstOrNull()
        if (dt != null) {
            val a = dt.enter.value.coerceIn(0f, 1f)
            if (a > 0.01f) {
                val col = matColor(dt)
                if (dt.id.startsWith("mod:")) drawLogo(c, lx, cy - icon / 2f, icon, a * (if (dt.kind == 1) 1f else 0.62f)) // 模块提示：左侧用客户端 LOGO，关闭时略暗
                else drawMatGlyph(c, matGlyphKind(dt), lx + icon / 2f, cy, icon / 2f, mulAlpha(col, a))
                val tx = lx + icon + gap
                if (dt.id.startsWith("mod:") && dt.progress == null) {
                    text(c, fit(dt.text, 15f, true, rx - swW - gap - tx), tx, centerBaseline(cy, 15f, true), 15f, mulAlpha(Palette.ink, a), bold = true)
                    val k = dt.shown.value.coerceIn(0f, 1f)
                    val sx = rx - swW
                    rrect(c, sx, cy - swH / 2f, sx + swW, cy + swH / 2f, swH / 2f, mulAlpha(mixColor(0xFF34303A.toInt(), Palette.accent, k), a))
                    val tr = dp(8f) + dp(2f) * k
                    val tcx = sx + swH / 2f + (swW - swH) * k
                    rrect(c, tcx - tr, cy - tr, tcx + tr, cy + tr, tr, mulAlpha(mixColor(0xFFE3DFE9.toInt(), 0xFFFFFFFF.toInt(), k), a))
                } else if (dt.progress != null) {
                    val frac = dt.shown.value.coerceIn(0f, 1f)
                    val sp = splitState(dt.state)
                    val rowY = y0 + dp(29f)
                    var rr = rx
                    if (sp.second.isNotEmpty()) {
                        text(c, sp.second, rr, rowY, 12f, mulAlpha(Palette.ink2, a), shadow = false, align = Paint.Align.RIGHT)
                        rr -= measure(sp.second, 12f) + dp(4f)
                    }
                    text(c, sp.first, rr, rowY, 20f, mulAlpha(Palette.ink, a), bold = true, shadow = false, align = Paint.Align.RIGHT)
                    text(c, fit(dt.text, 15f, true, rr - measure(sp.first, 20f) - gap - tx), tx, rowY - dp(1f), 15f, mulAlpha(Palette.ink, a), bold = true)
                    val by = y0 + dp(41f)
                    rrect(c, tx, by, rx, by + dp(3f), dp(1.5f), mulAlpha(Palette.ink, 0.16f * a))
                    if (frac > 0.01f) rrect(c, tx, by, tx + (rx - tx) * frac, by + dp(3f), dp(1.5f), mulAlpha(col, a))
                    val row2 = y0 + dp(68f)
                    text(c, fit(dt.badge, 11.5f, false, (rx - tx) * 0.7f), tx, row2, 11.5f, mulAlpha(Palette.ink2, a))
                    text(c, "${(frac * 100f).toInt()}%", rx, row2, 11.5f, mulAlpha(Palette.ink2, a), shadow = false, align = Paint.Align.RIGHT)
                } else {
                    val two = dt.badge.isNotEmpty()
                    val sw = if (dt.state != null) measure(dt.state!!, 12f) else 0f
                    val room = rx - tx - (if (sw > 0f) sw + gap * 2f else 0f)
                    if (two) {
                        text(c, fit(dt.badge, 11f, false, room), tx, cy - dp(4f), 11f, mulAlpha(Palette.ink2, a))
                        text(c, fit(dt.text, 14.5f, true, room), tx, cy + dp(13f), 14.5f, mulAlpha(Palette.ink, a), bold = true)
                    } else text(c, fit(dt.text, 14.5f, true, room), tx, centerBaseline(cy, 14.5f, true), 14.5f, mulAlpha(Palette.ink, a), bold = true)
                    if (dt.state != null) text(c, dt.state!!, rx, centerBaseline(cy, 12f), 12f, mulAlpha(col, a), shadow = false, align = Paint.Align.RIGHT)
                }
            }
        }
        c.restore()
        content.set(x0, y0, x0 + w, y0 + h)
        hasContent = true
        if (cur == null && tasks.isEmpty() && abs(islandW.value - islandW.target) < 0.5f && abs(islandH.value - islandH.target) < 0.5f && m < 0.01f) lazyMs = 100L
    }

    // ---------------- 样式：终末地 ----------------
    // 1:1 移植自 GlacierGlimmer/zmd-charge-plus(-for-android)（MIT）：设计单位 560×60 胶囊、同一份关键帧时间线、
    // 同一套配色 / 涟漪 / 图标目录 / 角标圆环。只把“数据”换成本客户端的内容，不改它的版式和动画。
    private class EfData(
        val tagline: String, val title: String,
        val primary: String, val secondary: String,
        val right: String, val suffix: String,
        val progress: Double, val leftIcon: String, val rightIcon: String, val accent: Int
    )

    private var efState: HudAnimationState = HudAnimationStates.persistentFinalState()
    private var efTimeline: HudTimeline? = null
    private var efBaseline: HudAnimationState = HudAnimationStates.initial()
    private var efSettle = false
    private var efStart = 0L
    private var efDone: (() -> Unit)? = null
    private var efStarted = false
    private var efProgShown = 0.0
    private var efProgFrom = 0.0
    private var efProgTo = 0.0
    private var efProgStart = 0L
    private var efProgInit = false
    private val efPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val efText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val efNum = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    private val efTitleTf = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    private val efIcons = HashMap<String, Path>()
    private val efPillPath = Path()
    private val efR1 = RectF()
    private val efR2 = RectF()
    private val efOpts = HudAnimationOptions(durationSeconds = 5.0)
    private val efIdleOpts = HudAnimationOptions(durationSeconds = 3.0)

    private fun efPlay(tl: HudTimeline, baseline: HudAnimationState, settle: Boolean, now: Long, done: (() -> Unit)? = null) {
        efTimeline = tl; efBaseline = baseline; efSettle = settle; efStart = now; efDone = done
        efState = baseline.overlay(tl.stateAt(0.0, baseline))
    }

    private fun efIdleReveal(now: Long) =
        efPlay(HudTimelines.simple(efIdleOpts, includeScaleOut = false), HudAnimationStates.simpleCState(), true, now)

    /** 事件卡片（终末地样式）：不再收起常驻胶囊重播整套动画，而是在它下面叠一张小卡片；同一事件再来只刷新内容 */
    private class EfEvt(var d: EfData, var born: Long) {
        var key = ""
        val enter = Tween(0f)
        val y = Tween(0f)
        val pop = Tween(1f)
        var exiting = false
        var exitStart = 0L
        var placed = false
        var ringStart = 0L
    }
    private val efEvts = ArrayList<EfEvt>()
    private val efR3 = RectF()

    // ---- 终末地：播放音乐时常驻胶囊显示歌词，胶囊宽度跟着歌词走 ----
    private val efPwT = Tween(-1f)
    private var efLyricCur = ""
    private var efLyricPrev = ""
    private val efLyricT = Tween(1f)
    private var efLyricMode = false

    private fun efWidthDp(): Float = when (cfg.i("efwidth")) { 1 -> 230f; 2 -> 260f; else -> 200f }

    private fun efTrigger(d: EfData) {
        val now = SystemClock.uptimeMillis()
        val key = d.tagline + "|" + d.title
        val ex = efEvts.firstOrNull { !it.exiting && it.key == key }
        if (ex != null) {
            ex.d = d; ex.born = now; ex.ringStart = now
            ex.pop.set(1.06f); ex.pop.animate(1f, 260L, Ease.OUT3, now)
        } else {
            val e = EfEvt(d, now)
            e.key = key; e.ringStart = now
            e.enter.animate(1f, 260L, Ease.OUT3, now)
            efEvts.add(0, e)
            var live = efEvts.count { !it.exiting }
            for (i in efEvts.indices.reversed()) {
                if (live <= EF_MAX_EVENTS) break
                val o = efEvts[i]
                if (!o.exiting) { o.exiting = true; o.exitStart = now; live-- }
            }
        }
        kick()
    }

    // 事件面板（灵动岛逻辑）：所有事件共用同一块底板。只有 1 条时是和常驻胶囊同款的药丸；
    // 来第 2、3 条时，底板向下长高、圆角从“药丸”平滑过渡成“圆角矩形”，新事件从底部长出来，第一条原地不动。
    // 事件退场后底板再收回去，只剩 1 条时圆角又回到药丸。
    private val efPanelH = Tween(0f)
    private val efPanelR = Tween(0f)
    private val efPanelA = Tween(0f)
    private val efR4 = RectF()
    private val efClip = Path()

    private fun efDrawEvents(c: Canvas, now: Long, cx: Float, idleBottom: Float, pw: Float, unit: Float): Float {
        val evH = 60f * unit
        val gap = dp(6f)
        val iter = efEvts.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (!e.exiting && now - e.born >= EF_HOLD_MS) { e.exiting = true; e.exitStart = now }
            if (e.exiting && now - e.exitStart >= 220L) iter.remove()
        }
        val live = efEvts.filter { !it.exiting }          // 最新的在最前
        val n = live.size
        val top = idleBottom + gap
        val rectR = min(24f * unit, evH / 2f)               // “完美圆角矩形”的圆角
        val ease = Ease.OUT3
        if (n > 0) {
            if (efPanelA.target <= 0f && efPanelA.value < 0.02f) { efPanelH.set(evH); efPanelR.set(evH / 2f) }
            efPanelA.animate(1f, 240L, ease, now)
            efPanelH.animate(n * evH, 340L, ease, now)
            efPanelR.animate(if (n == 1) evH / 2f else rectR, 340L, ease, now)
        } else efPanelA.animate(0f, 220L, Ease.IN3, now)
        // 行位置：最旧的在最上面，最新的在最下面（第一条不动）
        live.forEachIndexed { idx, e ->
            val slotTop = top + (n - 1 - idx) * evH
            if (!e.placed) { e.y.set(slotTop); e.placed = true }
            e.y.animate(slotTop, 340L, ease, now)
        }
        efPanelA.tick(now); efPanelH.tick(now); efPanelR.tick(now)
        val pa = efPanelA.value.coerceIn(0f, 1f)
        val ph = efPanelH.value
        if (pa < 0.01f || ph < 2f) {
            for (e in efEvts) { e.enter.tick(now); e.pop.tick(now); e.y.tick(now) }
            return idleBottom
        }
        efR4.set(cx - pw / 2f, top, cx + pw / 2f, top + ph)
        val rr = min(efPanelR.value, min(efR4.width(), efR4.height()) / 2f)
        c.save()
        val ps = 0.94f + 0.06f * pa
        c.scale(ps, ps, cx, top + ph / 2f)
        efPaint.style = Paint.Style.FILL
        efPaint.color = EF_PILL
        efPaint.alpha = efAlpha(pa * cfg.opacity)
        c.drawRoundRect(efR4, rr, rr, efPaint)
        c.save()
        efClip.reset(); efClip.addRoundRect(efR4, rr, rr, Path.Direction.CW)
        c.clipPath(efClip)
        for (e in efEvts) {
            e.enter.tick(now); e.pop.tick(now)
            val y = e.y.tick(now)
            var a = e.enter.value.coerceIn(0f, 1f)
            var yy = y
            if (e.exiting) {
                val q = ((now - e.exitStart) / 220f).coerceIn(0f, 1f)
                a *= 1f - q; yy -= dp(4f) * q
            }
            if (a > 0.01f) efDrawEvent(c, e, cx, yy, pw, evH, unit, a * pa, now)
        }
        // 行与行之间的细分隔线（跟着行一起移动 / 淡入）
        efPaint.style = Paint.Style.STROKE
        efPaint.strokeWidth = max(1f, d * 0.8f)
        efPaint.color = Color.WHITE
        live.forEachIndexed { idx, e ->
            val slot = n - 1 - idx
            if (slot >= 1) {
                efPaint.alpha = efAlpha(0.10f * e.enter.value.coerceIn(0f, 1f) * pa)
                val ly = e.y.value
                c.drawLine(efR4.left + 22f * unit, ly, efR4.right - 22f * unit, ly, efPaint)
            }
        }
        efPaint.style = Paint.Style.FILL
        c.restore()
        c.restore()
        return top + ph
    }

    private fun efDrawEvent(c: Canvas, e: EfEvt, cx: Float, top: Float, pw: Float, h: Float, unit: Float, a: Float, now: Long) {
        val d = e.d
        val sc = e.pop.value * (0.94f + 0.06f * e.enter.value.coerceIn(0f, 1f))
        val pl = cx - pw / 2f
        val pr = cx + pw / 2f
        val cy = top + h / 2f
        c.save()
        c.scale(sc, sc, cx, cy)
        efPaint.style = Paint.Style.FILL
        // 左：图标底板
        val px = pl + 34f * unit
        efPaint.color = EF_PLATE
        efPaint.alpha = efAlpha(a)
        c.drawCircle(px, cy, 16f * unit, efPaint)
        efGlyph(c, d.leftIcon, 18f * unit, px, cy, EF_DARK, efAlpha(a))
        // 右：角标圆环（进度 420ms 扫过去）
        val badge = 46f * unit
        val badgeLeft = pr - 51f * unit
        val bcx = badgeLeft + badge / 2f
        val r = badge / 2f
        efPaint.style = Paint.Style.FILL
        efPaint.color = EF_BADGE
        efPaint.alpha = efAlpha(a)
        c.drawCircle(bcx, cy, r, efPaint)
        val inset = 4.5f * unit / 2f
        efR2.set(bcx - r + inset, cy - r + inset, bcx + r - inset, cy + r - inset)
        efPaint.style = Paint.Style.STROKE
        efPaint.strokeWidth = unit
        efPaint.strokeCap = Paint.Cap.BUTT
        efPaint.color = d.accent
        efPaint.alpha = efAlpha(a * 0.18f)
        c.drawCircle(bcx, cy, r - inset, efPaint)
        val q = ((now - e.ringStart) / 420f).coerceIn(0f, 1f)
        val prog = d.progress * (1.0 - (1.0 - q) * (1.0 - q) * (1.0 - q))
        val sweep = (360.0 * prog.coerceIn(0.0, 1.0)).coerceIn(0.5, 359.5).toFloat()
        efPaint.strokeWidth = 4.5f * unit
        efPaint.strokeCap = Paint.Cap.ROUND
        efPaint.alpha = efAlpha(a)
        c.drawArc(efR2, -90f, sweep, false, efPaint)
        efPaint.strokeCap = Paint.Cap.BUTT
        efGlyph(c, d.rightIcon, 21f * unit, bcx, cy, d.accent, efAlpha(a))
        // 状态文字（靠右，紧挨角标）
        val sRight = badgeLeft - 12f * unit
        val statSize = 17f * unit
        val stat = efFit(d.primary, statSize, efNum, 110f * unit)
        efText.typeface = efNum; efText.textSize = statSize; efText.letterSpacing = 0f
        efText.textAlign = Paint.Align.RIGHT
        efText.color = Color.WHITE
        efText.alpha = efAlpha(a)
        c.drawText(stat, sRight, cy - (efText.fontMetrics.ascent + efText.fontMetrics.descent) / 2f, efText)
        val sw = efText.measureText(stat)
        // 标题块（靠左）：小标签 + 粗体标题
        val tx = pl + 66f * unit
        val maxW = (sRight - sw - 10f * unit - tx).coerceAtLeast(30f * unit)
        val tagSize = 11f * unit
        val titleSize = 20f * unit
        efText.textAlign = Paint.Align.LEFT
        efText.typeface = efNum; efText.textSize = tagSize; efText.letterSpacing = 1.5f / 11f
        val tm = efText.fontMetrics
        val tagH = tm.descent - tm.ascent
        efText.typeface = efTitleTf; efText.textSize = titleSize; efText.letterSpacing = 1f / 20f
        val im = efText.fontMetrics
        val titleH = im.descent - im.ascent
        val gap = 1.5f * unit
        val y0 = cy - (tagH + gap + titleH) / 2f
        val tag = efFit(d.tagline, tagSize, efNum, maxW)
        efText.typeface = efNum; efText.textSize = tagSize; efText.letterSpacing = 1.5f / 11f
        efText.color = Color.WHITE; efText.alpha = efAlpha(a * 0.42f)
        c.drawText(tag, tx, y0 - tm.ascent, efText)
        val ttl = efFit(d.title, titleSize, efTitleTf, maxW)
        efText.typeface = efTitleTf; efText.textSize = titleSize; efText.letterSpacing = 1f / 20f
        efText.alpha = efAlpha(a)
        c.drawText(ttl, tx, y0 + tagH + gap - im.ascent, efText)
        efText.letterSpacing = 0f
        c.restore()
    }

    private fun efCurrent(): EfData {
        val fps = HudInfo.fps
        val ping = HudInfo.ping
        if (musicOn()) {
            val d = MusicPlayer.durMs
            return EfData("", "", MusicPlayer.fmt(MusicPlayer.posMs), "", MusicPlayer.fmt(d), "",
                if (d > 0) (MusicPlayer.posMs.toDouble() / d).coerceIn(0.0, 1.0) else 0.0,
                "signal", "activity", 0xFF8E7CC3.toInt())
        }
        return EfData(
            "", "",
            if (fps >= 0) fps.toString() else "--", "fps",
            if (ping >= 0) ping.toString() else "rlin", if (ping >= 0) "ms" else HUD_VERSION,
            if (fps >= 0) (fps / 120.0).coerceIn(0.0, 1.0) else 0.0,
            "activity", "gauge", 0xFFC6CA4C.toInt()
        )
    }

    private fun efKindAccent(k: Int): Int = when (k) {
        2 -> 0xFFD9A441.toInt()
        3 -> 0xFFD0604F.toInt()
        4 -> 0xFF8E8A85.toInt()
        else -> 0xFFC6CA4C.toInt()
    }

    private fun efFit(s: String, px: Float, tf: android.graphics.Typeface, maxPx: Float): String {
        efText.typeface = tf; efText.textSize = px; efText.letterSpacing = 0f
        if (efText.measureText(s) <= maxPx) return s
        var n = s.length
        while (n > 1 && efText.measureText(s.substring(0, n) + "…") > maxPx) n--
        return s.substring(0, n) + "…"
    }

    private fun efAlpha(f: Float) = (f.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun efGlyph(c: Canvas, name: String, sizePx: Float, cx: Float, cy: Float, color: Int, alpha: Int) {
        if (alpha <= 0 || sizePx <= 0f) return
        efPaint.style = Paint.Style.FILL
        efPaint.color = color
        efPaint.alpha = alpha
        if (efIcons.size > 64) efIcons.clear()
        val path = efIcons.getOrPut(name.lowercase() + "@" + sizePx.toInt()) { HudIconCatalog.path(name, sizePx) }
        c.save(); c.translate(cx, cy); c.drawPath(path, efPaint); c.restore()
    }

    private fun renderEndfield(c: Canvas, now: Long) {
        needFrame = true
        if (!efStarted) { efStarted = true; efIdleReveal(now) }
        val tl = efTimeline
        if (tl != null) {
            val p = tl.progressAt(now - efStart)
            if (p >= 1.0) {
                val fin = if (efSettle) HudAnimationStates.persistentFinalState() else efBaseline.overlay(tl.stateAt(1.0, efBaseline))
                efState = fin
                efTimeline = null
                val dn = efDone; efDone = null
                dn?.invoke()
            } else {
                efState = efBaseline.overlay(tl.stateAt(p, efBaseline))
            }
        }
        val st = efState
        val rd = efCurrent()

        val unit = d * EF_UNIT
        val cx = width / 2f
        val cy = dp(8f) + 30f * unit
        // 进度环：首个值直接到位，变化 > 0.0005 时 320ms 三次缓出
        if (!efProgInit) { efProgInit = true; efProgShown = rd.progress; efProgFrom = rd.progress; efProgTo = rd.progress }
        if (abs(rd.progress - efProgTo) > 0.0005) { efProgFrom = efProgShown; efProgTo = rd.progress; efProgStart = now }
        val pf = ((now - efProgStart) / 320f).coerceIn(0f, 1f)
        efProgShown = if (pf >= 1f) efProgTo else efProgFrom + (efProgTo - efProgFrom) * (1f - (1f - pf) * (1f - pf) * (1f - pf))

        val sh = st[HudNode.SCALE_HOST]
        c.save()
        c.scale(sh.scaleX, sh.scaleY, cx, cy)

        val pill = st[HudNode.PILL]
        // 歌词：有歌词时用“歌手·歌名 + 当前歌词”的版式，胶囊宽度 = 歌词宽度（不小于设定宽度、不超过窗口）
        efLyricMode = musicOn() && MusicPlayer.lyrics.isNotEmpty()
        val line = if (efLyricMode) MusicPlayer.currentLine().trim() else ""
        if (line != efLyricCur) { efLyricPrev = efLyricCur; efLyricCur = line; efLyricT.set(0f); efLyricT.animate(1f, 260L, Ease.OUT3, now) }
        efLyricT.tick(now)
        val basePw = dp(efWidthDp())
        var pwTarget = basePw
        if (efLyricMode && line.isNotEmpty()) {
            efText.typeface = efTitleTf; efText.textSize = 20f * unit; efText.letterSpacing = 0f
            pwTarget = (efText.measureText(line) + 131f * unit).coerceIn(basePw, max(basePw, width.toFloat() - dp(8f)))
        }
        if (efPwT.value < 0f) efPwT.set(pwTarget) else efPwT.animate(pwTarget, 320L, Ease.OUT3, now)
        val pw = efPwT.tick(now)
        val ik = ((pw / unit) / 2f - 35f) / 245f // 窄一点时把图标 / 涟漪的位移按比例收回来
        val ph = pill.height * unit
        val pl = cx - pw / 2f
        val pt = cy - ph / 2f
        efR1.set(pl, pt, pl + pw, pt + ph)
        val rad = pill.cornerRadius * unit
        val po = pill.opacity.coerceIn(0f, 1f)
        if (po > 0.001f) {
            c.save()
            efPillPath.reset(); efPillPath.addRoundRect(efR1, rad, rad, Path.Direction.CW)
            c.clipPath(efPillPath)
            c.save()
            c.scale(pill.scaleX, pill.scaleY, efR1.centerX(), efR1.centerY())
            efPaint.style = Paint.Style.FILL
            efPaint.color = EF_PILL
            efPaint.alpha = efAlpha(po * cfg.opacity)
            c.drawRoundRect(efR1, rad, rad, efPaint)
            // 涟漪
            val host = st[HudNode.RIPPLE_HOST]
            val rise = st[HudNode.RIPPLE_RISE]
            val ox = efR1.centerX() + host.translateX * unit * ik
            val oy = efR1.centerY() + rise.translateY * unit
            efRipple(c, st[HudNode.RIPPLE_INNER], 160f, ox, oy, unit, po, 0f)
            efRipple(c, st[HudNode.RIPPLE_MID], 220f, ox, oy, unit, po, 5f)
            efRipple(c, st[HudNode.RIPPLE_OUTER], 280f, ox, oy, unit, po, 3.5f)
            c.restore()
            c.restore()
        }
        efTitle(c, st, rd, cx, cy, unit)
        efNumHost(c, st, rd, efR1, cy, unit)
        efBolt(c, st, rd, cx, cy, unit, ik)
        c.restore()
        val evBottom = efDrawEvents(c, now, cx, efR1.bottom, pw, unit)
        content.set(efR1.left, efR1.top, efR1.right, evBottom)
        hasContent = po > 0.05f
        if (efTimeline == null && efEvts.isEmpty() && pf >= 1f && !efLyricT.running && !efPanelA.running && abs(efPwT.value - efPwT.target) < 0.5f) lazyMs = 100L
    }

    private fun efRipple(c: Canvas, s: HudNodeState, diameter: Float, ox: Float, oy: Float, unit: Float, po: Float, stroke: Float) {
        val a = efAlpha(s.opacity * po)
        if (a <= 0) return
        val r = diameter * unit / 2f * s.scaleX
        if (r <= 0f) return
        efPaint.color = EF_RIPPLE
        efPaint.alpha = a
        if (stroke <= 0f) { efPaint.style = Paint.Style.FILL; c.drawCircle(ox, oy, r, efPaint) }
        else { efPaint.style = Paint.Style.STROKE; efPaint.strokeWidth = stroke * unit * s.scaleX; c.drawCircle(ox, oy, r, efPaint) }
    }

    private fun efTitle(c: Canvas, st: HudAnimationState, rd: EfData, cx: Float, cy: Float, unit: Float) {
        val host = st[HudNode.TITLE_HOST]
        val a = efAlpha(host.opacity)
        if (a <= 0) return
        efText.textAlign = Paint.Align.CENTER
        val tagSize = 9f * unit
        efText.typeface = efNum; efText.textSize = tagSize; efText.letterSpacing = 2f / 9f
        val tm = efText.fontMetrics
        val tagH = tm.descent - tm.ascent
        val titleSize = 26f * unit
        efText.typeface = efTitleTf; efText.textSize = titleSize; efText.letterSpacing = 2f / 26f
        val im = efText.fontMetrics
        val titleH = im.descent - im.ascent
        val gap = 2f * unit
        val top = cy - (tagH + gap + titleH) / 2f
        val tag = efFit(rd.tagline, tagSize, efNum, 440f * unit)
        efText.color = Color.WHITE
        efText.alpha = efAlpha(host.opacity * 0.40f)
        efText.typeface = efNum; efText.textSize = tagSize; efText.letterSpacing = 2f / 9f
        c.drawText(tag, cx, top - tm.ascent, efText)
        val ttl = efFit(rd.title, titleSize, efTitleTf, 440f * unit)
        efText.alpha = a
        efText.typeface = efTitleTf; efText.textSize = titleSize; efText.letterSpacing = 2f / 26f
        c.drawText(ttl, cx, top + tagH + gap - im.ascent, efText)
        efText.letterSpacing = 0f
    }

    private fun efNumHost(c: Canvas, st: HudAnimationState, rd: EfData, pillRect: RectF, cy: Float, unit: Float) {
        val host = st[HudNode.NUM_HOST]
        val a = efAlpha(host.opacity)
        if (a <= 0) return
        val wU = pillRect.width() / unit
        val shift = host.translateX * unit
        val pl = pillRect.left + shift
        val pr = pillRect.right + shift
        val badge = 46f * unit
        val badgeLeft = pr - (5f + 46f) * unit
        val bcx = badgeLeft + badge / 2f
        efText.typeface = efNum
        efText.letterSpacing = 0f
        efText.textAlign = Paint.Align.LEFT
        efText.color = Color.WHITE
        if (efLyricMode) efDrawLyric(c, host.opacity, pl, badgeLeft, cy, unit) else {
        val pri = efFit(rd.primary, 26f * unit, efNum, min(170f, wU * 0.30f) * unit)
        efText.textSize = 26f * unit
        efText.alpha = a
        val base1 = cy - (efText.fontMetrics.ascent + efText.fontMetrics.descent) / 2f
        val left = pl + 64f * unit
        c.drawText(pri, left, base1, efText)
        val priW = efText.measureText(pri)
        efText.textSize = 14f * unit
        efText.alpha = efAlpha(host.opacity * 0.55f)
        c.drawText(rd.secondary, left + priW + 2f * unit, cy - (efText.fontMetrics.ascent + efText.fontMetrics.descent) / 2f, efText)

        efText.textAlign = Paint.Align.RIGHT
        val suffixRight = badgeLeft - 14f * unit
        efText.textSize = 13f * unit
        efText.alpha = efAlpha(host.opacity * 0.55f)
        c.drawText(rd.suffix, suffixRight, cy - (efText.fontMetrics.ascent + efText.fontMetrics.descent) / 2f, efText)
        val sufW = efText.measureText(rd.suffix)
        val right = efFit(rd.right, 22f * unit, efNum, min(200f, wU * 0.28f) * unit)
        efText.typeface = efNum
        efText.textSize = 22f * unit
        efText.alpha = a
        c.drawText(right, suffixRight - sufW - 1f * unit, cy - (efText.fontMetrics.ascent + efText.fontMetrics.descent) / 2f, efText)
        efText.textAlign = Paint.Align.LEFT
        }

        // 角标：深色圆盘 + 极细底轨 + 进度圆弧（线宽 4.5，圆头，起点 −90°）+ 右图标
        val r = badge / 2f
        efPaint.style = Paint.Style.FILL
        efPaint.color = EF_BADGE
        efPaint.alpha = efAlpha(host.opacity)
        c.drawCircle(bcx, cy, r, efPaint)
        val inset = 4.5f * unit / 2f
        efR2.set(bcx - r + inset, cy - r + inset, bcx + r - inset, cy + r - inset)
        efPaint.style = Paint.Style.STROKE
        efPaint.strokeWidth = unit
        efPaint.strokeCap = Paint.Cap.BUTT
        efPaint.color = rd.accent
        efPaint.alpha = efAlpha(host.opacity * 0.18f)
        c.drawCircle(bcx, cy, r - inset, efPaint)
        val sweep = (360.0 * efProgShown.coerceIn(0.0, 1.0)).coerceIn(0.5, 359.5).toFloat()
        efPaint.strokeWidth = 4.5f * unit
        efPaint.strokeCap = Paint.Cap.ROUND
        efPaint.alpha = efAlpha(host.opacity)
        c.drawArc(efR2, -90f, sweep, false, efPaint)
        efPaint.strokeCap = Paint.Cap.BUTT
        if (rd.rightIcon.equals("battery", true) || rd.rightIcon.equals("laptop", true)) {
            efLaptop(c, bcx, cy, unit, rd.accent, host.opacity)
        } else {
            efGlyph(c, rd.rightIcon, 21f * unit, bcx, cy, rd.accent, efAlpha(host.opacity))
        }
    }

    /** 音乐歌词版式：小标签“歌手 · 歌名”+ 一行歌词（换行时上滑淡入淡出），文字区在图标和进度环之间 */
    private fun efDrawLyric(c: Canvas, op: Float, pl: Float, badgeLeft: Float, cy: Float, unit: Float) {
        val tx = pl + 66f * unit
        val maxW = (badgeLeft - 14f * unit - tx).coerceAtLeast(20f * unit)
        val tagSize = 11f * unit
        val titleSize = 20f * unit
        efText.textAlign = Paint.Align.LEFT
        efText.typeface = efNum; efText.textSize = tagSize; efText.letterSpacing = 0f
        val tm = efText.fontMetrics
        val tagH = tm.descent - tm.ascent
        efText.typeface = efTitleTf; efText.textSize = titleSize
        val im = efText.fontMetrics
        val titleH = im.descent - im.ascent
        val gap = 1.5f * unit
        val y0 = cy - (tagH + gap + titleH) / 2f
        val t = MusicPlayer.track
        val tagStr = listOfNotNull(t?.artist?.takeIf { it.isNotEmpty() }, t?.title?.takeIf { it.isNotEmpty() }).joinToString(" · ")
        val tag = efFit(tagStr, tagSize, efNum, maxW)
        efText.typeface = efNum; efText.textSize = tagSize; efText.letterSpacing = 0f
        efText.color = Color.WHITE; efText.alpha = efAlpha(op * 0.42f)
        c.drawText(tag, tx, y0 - tm.ascent, efText)
        val q = efLyricT.value.coerceIn(0f, 1f)
        fun title(s: String, a: Float, dy: Float) {
            if (s.isEmpty() || a <= 0.01f) return
            val f = efFit(s, titleSize, efTitleTf, maxW)
            efText.typeface = efTitleTf; efText.textSize = titleSize; efText.letterSpacing = 0f
            efText.color = Color.WHITE; efText.alpha = efAlpha(op * a)
            c.drawText(f, tx, y0 + tagH + gap - im.ascent + dy, efText)
        }
        if (q < 1f) title(efLyricPrev, 1f - q, -6f * unit * q)
        title(efLyricCur, q, 6f * unit * (1f - q))
        efText.letterSpacing = 0f
    }

    private fun efLaptop(c: Canvas, cx: Float, cy: Float, unit: Float, accent: Int, opacity: Float) {
        efPaint.color = accent
        efPaint.alpha = efAlpha(opacity)
        val sw = 17f * unit; val shh = 11.5f * unit; val ch = 17f * unit
        val top = cy - ch / 2f
        efPaint.style = Paint.Style.STROKE
        efPaint.strokeWidth = 2f * unit
        efR2.set(cx - sw / 2f, top, cx + sw / 2f, top + shh)
        c.drawRoundRect(efR2, 1.5f * unit, 1.5f * unit, efPaint)
        val bw = 24f * unit; val bh = 3f * unit
        efPaint.style = Paint.Style.FILL
        efR2.set(cx - bw / 2f, top + ch - bh, cx + bw / 2f, top + ch)
        c.drawRoundRect(efR2, 1.5f * unit, 1.5f * unit, efPaint)
        val ew = 9f * unit; val eh = 3f * unit
        val ecy = cy - 14f * unit
        efR2.set(cx - ew / 2f, ecy - eh / 2f, cx + ew / 2f, ecy + eh / 2f)
        c.drawRoundRect(efR2, unit, unit, efPaint)
    }

    private fun efBolt(c: Canvas, st: HudAnimationState, rd: EfData, cx: Float, cy: Float, unit: Float, ik: Float) {
        val icon = st[HudNode.BOLT_ICON]
        if (efAlpha(icon.opacity) <= 0) return
        val ix = cx + icon.translateX * unit * ik
        c.save()
        c.scale(icon.scaleX, icon.scaleY, ix, cy)
        val ca = efAlpha(st[HudNode.CIRCLE_FORM].opacity * icon.opacity)
        if (ca > 0) {
            efPaint.style = Paint.Style.FILL
            efPaint.color = EF_PLATE
            efPaint.alpha = ca
            c.drawCircle(ix, cy, 32f * unit / 2f, efPaint)
            efGlyph(c, rd.leftIcon, 18f * unit, ix, cy, EF_DARK, ca)
        }
        val sa = efAlpha(st[HudNode.SQUARE_FORM].opacity * icon.opacity)
        if (sa > 0) {
            val pl = 18f * unit
            efR2.set(ix - pl / 2f, cy - pl / 2f, ix + pl / 2f, cy + pl / 2f)
            efPaint.style = Paint.Style.FILL
            efPaint.color = EF_PLATE
            efPaint.alpha = sa
            c.drawRoundRect(efR2, 4.5f * unit, 4.5f * unit, efPaint)
            efGlyph(c, rd.leftIcon, 12f * unit, ix, cy, EF_DARK, sa)
        }
        c.restore()
    }

    private fun glyph(k: Int) = when (k) { 1 -> "✓"; 2 -> "!"; 3 -> "✕"; 4 -> "–"; 5 -> "♪"; else -> "i" }

    private companion object {
        const val VALUE_TIMEOUT_MS = 5000L
        const val MAX_ROWS = 3
        const val EF_UNIT = 0.6f                  // 1 个设计单位 = 0.6dp（胶囊高 36dp）；宽度由“终末地宽度”选项决定，可用 HUD 的“大小”再缩放
        const val EF_HOLD_MS = 2500L              // 事件卡片停留
        const val EF_MAX_EVENTS = 3
        val EF_PILL = 0xFF312F30.toInt()
        val EF_PLATE = 0xFFE9E7E4.toInt()
        val EF_DARK = 0xFF141313.toInt()
        val EF_BADGE = 0xFF262425.toInt()
        val EF_RIPPLE = 0xFF656363.toInt()
    }
}

/**
 * 编辑 HUD 时的设置面板：从灵动岛的位置弹性展开（过冲回弹），里面调整选中 HUD 的大小 / 透明度 /
 * 背景模糊 / 各项选项。窗口固定为“最高的那个 HUD 的面板”大小，卡片在窗口里用裁剪矩形做展开动画，
 * 所以动画期间不用改窗口尺寸。切换选中的 HUD 时卡片高度平滑过渡，行内容依次淡入。
 */
class IslandPanelView(
    ctx: Context,
    private val repo: ModuleRepo,
    private val onClose: () -> Unit
) : FrameLayout(ctx) {
    val margin = Ui.u(22f)
    val fullW = Ui.u(330f)
    private var maxH = 0
    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.u(16f), Ui.u(10f), Ui.u(16f), Ui.u(14f))
    }
    private val start = RectF()
    private var startR = 0f
    var p = 0f
        private set
    var closing = false
        private set
    private var curH = 0f
    private var pAnim: ValueAnimator? = null
    private var hAnim: ValueAnimator? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.line; strokeWidth = Ui.f(1f) }
    private val path = Path()
    private val rr = RectF()

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(body, LayoutParams(fullW, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = margin; topMargin = margin })
    }

    /** 逐个 HUD 把面板内容量一遍，取最高的作为窗口高度（面板内容部分，不含边距） */
    fun measureMax(screenH: Int): Int {
        scrollMax = (screenH * 0.42f).toInt()
        var mx = 0
        for (t in HudType.values()) {
            build(t)
            body.measure(MeasureSpec.makeMeasureSpec(fullW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(screenH, MeasureSpec.AT_MOST))
            mx = max(mx, body.measuredHeight)
        }
        maxH = mx
        return mx
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        body.measure(MeasureSpec.makeMeasureSpec(fullW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        setMeasuredDimension(fullW + 2 * margin, maxH + 2 * margin)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = p < 0.85f || closing

    /** 当前卡片矩形（本 View 坐标）+ 圆角半径，宿主拿去做背景模糊 */
    fun currentRect(out: RectF): Float {
        cardRect(out)
        return Ui.f(26f) * min(p, 1f) + startR * (1f - min(p, 1f))
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun cardRect(out: RectF) {
        out.set(
            lerp(start.left, margin.toFloat(), p), lerp(start.top, margin.toFloat(), p),
            lerp(start.right, (margin + fullW).toFloat(), p), lerp(start.bottom, margin + curH, p)
        )
    }

    override fun dispatchDraw(c: Canvas) {
        cardRect(rr)
        val rad = Ui.f(26f) * min(p, 1f) + startR * (1f - min(p, 1f))
        bg.color = islandGlass(HudCanvasView.blurAvailable)
        c.drawRoundRect(rr, rad, rad, bg)
        c.drawRoundRect(rr, rad, rad, stroke)
        body.alpha = ((p - 0.25f) / 0.5f).coerceIn(0f, 1f)
        path.reset()
        path.addRoundRect(rr, rad, rad, Path.Direction.CW)
        c.save()
        c.clipPath(path)
        super.dispatchDraw(c)
        c.restore()
    }

    /** from = 灵动岛（或默认位置）在本窗口坐标里的矩形 */
    fun expand(from: RectF, radius: Float) {
        start.set(from); startR = radius
        pAnim?.cancel()
        pAnim = ValueAnimator.ofFloat(p, 1f).apply {
            duration = 560
            interpolator = OvershootInterpolator(1.5f)
            addUpdateListener { p = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun collapse(done: () -> Unit) {
        if (closing) return
        closing = true
        pAnim?.cancel()
        pAnim = ValueAnimator.ofFloat(p, 0f).apply {
            duration = 220
            interpolator = AccelerateInterpolator(1.4f)
            addUpdateListener { p = (it.animatedValue as Float).coerceAtLeast(0f); invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { done() }
            })
            start()
        }
    }

    /** 显示某个 HUD 的设置。animate = true：卡片高度平滑过渡、行内容依次淡入（切换选中时） */
    fun bind(t: HudType, animate: Boolean) {
        build(t)
        body.measure(MeasureSpec.makeMeasureSpec(fullW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        val nh = body.measuredHeight.toFloat()
        hAnim?.cancel()
        if (!animate || curH <= 0f) {
            curH = nh
        } else {
            hAnim = ValueAnimator.ofFloat(curH, nh).apply {
                duration = 340
                interpolator = OvershootInterpolator(0.9f)
                addUpdateListener { curH = it.animatedValue as Float; invalidate() }
                start()
            }
            for (i in 0 until body.childCount) {
                val v = body.getChildAt(i)
                v.alpha = 0f
                v.animate().alpha(1f).setStartDelay(i * 28L).setDuration(180).start()
            }
        }
        requestLayout(); invalidate()
    }

    // ---------------- 内容 ----------------
    private var scrollMax = 0

    private fun section(text: String) = TextView(context).apply {
        this.text = text; px(10.5f); bold(); setTextColor(Palette.ink2); letterSpacing = 0.08f
        setPadding(Ui.u(4f), Ui.u(12f), 0, Ui.u(6f))
    }

    /** 一组设置：小标题 + 圆角卡片（和 ClickGUI 里的设置卡片同一个样子），卡片内行与行之间有分隔线 */
    private fun group(into: LinearLayout, title: String?, rows: List<View>) {
        if (rows.isEmpty()) return
        if (title != null) into.addView(section(title))
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rr(withAlpha(Palette.panelD, 0.4f), Ui.f(16f), Palette.line, Ui.u(1f))
            setPadding(Ui.u(14f), 0, Ui.u(14f), 0)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setColor(Palette.line); setSize(1, Ui.u(1f)) }
        }
        rows.forEach { card.addView(it) }
        into.addView(card, LinearLayout.LayoutParams(-1, -2))
    }

    private fun choiceRow(t: HudType, o: HudOpt.Choice): View {
        val ctx = context
        val sel = repo.hudInt(t, o.key, o.def).coerceIn(0, o.options.size - 1)
        val seg = SegmentedView(ctx, o.options.map { tr(it) }, sel) { i -> repo.setHudInt(t, o.key, i) }
        if (o.options.size <= 2) {
            val row = rowBase(tr(o.label), null)
            row.addView(seg, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(14f) })
            return row
        }
        // 选项多：名称在上，分段选择器在下（放不下可横向滑动）——所有选项一眼可见，不用“点一下循环”
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.u(9f), 0, Ui.u(9f))
            addView(TextView(ctx).apply { text = tr(o.label); px(13f); setTextColor(Palette.ink) })
            addView(android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
                addView(seg)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.u(6f) })
        }
        return col
    }

    private fun build(t: HudType) {
        body.removeAllViews()
        val ctx = context
        // 标题行：名称 + 关闭
        val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.u(4f), Ui.u(6f), 0, Ui.u(2f)) }
        head.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply { text = tr(t.label); px(16f); bold(); setTextColor(Palette.ink) })
            addView(TextView(ctx).apply { text = tr("调整此 HUD"); px(11f); setTextColor(Palette.ink2) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(Palette.ink2)
            setPadding(Ui.u(6f), Ui.u(6f), Ui.u(6f), Ui.u(6f))
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(Ui.u(30f), Ui.u(30f)))
        body.addView(head)

        // 设置部分可滚动（选项多的 HUD，比如模块列表，不会把面板撑出屏幕）
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        group(inner, tr("通用"), listOf(
            percentRow(tr("大小"), 50f, 200f, repo.hudScale(t)) { repo.setHudScale(t, it) },
            percentRow(tr("透明度"), 20f, 100f, repo.hudOpacity(t)) { repo.setHudOpacity(t, it) },
            switchRow(tr("背景模糊"),
                if (HudCanvasView.blurAvailable) null else tr("总开关已关闭（ClickGUI → 界面模糊）"),
                repo.hudBool(t, "blur", true)) { repo.setHudBool(t, "blur", it) }
        ))
        val optRows = t.opts.map { o ->
            when (o) {
                is HudOpt.Toggle -> switchRow(tr(o.label), null, repo.hudBool(t, o.key, o.def)) { repo.setHudBool(t, o.key, it) }
                is HudOpt.Choice -> choiceRow(t, o)
            }
        }
        group(inner, tr("选项"), optRows)
        // 配色：所有 HUD 共用
        val colors = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.u(9f), 0, Ui.u(9f))
            addView(ThemeDotsView(ctx, repo.hudTheme()) { repo.setHudTheme(it) }, LinearLayout.LayoutParams(-1, Ui.u(34f)))
        }
        group(inner, tr("HUD 配色（所有 HUD 共用）"), listOf(colors))
        inner.addView(LinearLayout(ctx).apply {
            gravity = Gravity.END
            setPadding(0, Ui.u(12f), 0, Ui.u(2f))
            addView(pillButton(ctx, tr("重置此 HUD"), 1) { repo.resetHud(t); bind(t, false) })
        })
        val sc = MaxHeightScrollView(ctx, if (scrollMax > 0) scrollMax else Int.MAX_VALUE / 4).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(inner)
        }
        body.addView(sc, LinearLayout.LayoutParams(-1, -2))
    }

    private fun rowBase(label: String, desc: String?): LinearLayout {
        val ctx = context
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.u(9f), 0, Ui.u(9f))
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply { text = label; px(13f); setTextColor(Palette.ink); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            if (desc != null) addView(TextView(ctx).apply { text = desc; px(10.5f); setTextColor(Palette.ink2); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun percentRow(label: String, min: Float, max: Float, cur: Int, onValue: (Int) -> Unit): View {
        val ctx = context
        val row = rowBase(label, null)
        val value = TextView(ctx).apply {
            px(12f); bold(); setTextColor(Palette.accent); gravity = Gravity.END
            minWidth = Ui.u(40f); text = "$cur%"
        }
        val slider = FlatSlider(ctx, min, max, 5f, cur.toFloat(), { v ->
            value.text = "${v.toInt()}%"; onValue(v.toInt())
        }, {})
        row.addView(slider, LinearLayout.LayoutParams(Ui.u(130f), Ui.u(34f)).apply { leftMargin = Ui.u(14f) })
        row.addView(value, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(8f) })
        return row
    }

    private fun switchRow(label: String, desc: String?, on: Boolean, onChange: (Boolean) -> Unit): View {
        val row = rowBase(label, desc)
        row.addView(Md3Switch(context).apply { setChecked(on, false); onToggle = onChange },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(14f) })
        return row
    }
}
