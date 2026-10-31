package com.nemoclient.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Outline
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.ImageView
import com.nemoclient.app.music.GdRate
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nemoclient.app.i18n.tr
import com.nemoclient.app.i18n.tf
import com.nemoclient.app.music.MusicListener
import com.nemoclient.app.music.MusicPlayer
import com.nemoclient.app.music.Track

/** 内置音乐面板（放在卡片里）：音源 / 搜索 / 结果 / 正在播放 + 歌词 + 进度 + 控制 */
@SuppressLint("ViewConstructor")
class MusicPanelView(ctx: Context, private val host: SheetHost, private val page: Boolean = false) : LinearLayout(ctx) {
    private val chips = ArrayList<TextView>()
    private val chipIdx = ArrayList<Int>()
    private val gdBox = LinearLayout(ctx)
    private val platChips = ArrayList<TextView>()
    private val brChips = ArrayList<TextView>()
    private val quota = TextView(ctx)
    private val credit = TextView(ctx)
    private val cover = ImageView(ctx)
    private val settingsBox = LinearLayout(ctx)
    private val input = EditText(ctx)
    private val results = LinearLayout(ctx)
    private val hint = TextView(ctx)
    private val title = TextView(ctx)
    private val sub = TextView(ctx)
    private val lyric = TextView(ctx)
    private val time = TextView(ctx)
    private val playBtn = MusicIconButton(ctx, MusicIconButton.PLAY) { MusicPlayer.toggle() }
    private val modeBtn = MusicIconButton(ctx, MusicIconButton.LOOP) { MusicPlayer.cycleMode() }
    private val bar = ProgressBar(ctx)
    private var found: List<Track> = emptyList()

    private val listener = MusicListener { changed -> refresh(changed) }

    init {
        MusicPlayer.init(ctx)
        orientation = VERTICAL

        // 音源（显示顺序：GD 音乐台 / Jamendo / Internet Archive / 网易云 API / 自定义 API）
        val srcRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val names = mapOf(
            MusicPlayer.SOURCE_GD to "GD 音乐台", MusicPlayer.SOURCE_JAMENDO to "Jamendo",
            MusicPlayer.SOURCE_ARCHIVE to "Internet Archive", MusicPlayer.SOURCE_NETEASE to tr("网易云 API"),
            MusicPlayer.SOURCE_CUSTOM to tr("自定义 API")
        )
        for (i in intArrayOf(MusicPlayer.SOURCE_GD, MusicPlayer.SOURCE_JAMENDO, MusicPlayer.SOURCE_ARCHIVE, MusicPlayer.SOURCE_NETEASE, MusicPlayer.SOURCE_CUSTOM)) {
            val c = TextView(ctx).apply {
                text = names[i]; px(11.5f); setSingleLine(); setPadding(Ui.u(12f), Ui.u(6f), Ui.u(12f), Ui.u(6f))
                setOnClickListener { MusicPlayer.sourceIdx = i; styleChips() }
            }
            chips.add(c); chipIdx.add(i)
            srcRow.addView(c, LayoutParams(-2, -2).apply { rightMargin = Ui.u(6f) })
        }
        addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(srcRow)
        }, LayoutParams(-1, -2))
        addView(TextView(ctx).apply {
            text = tr("设置"); px(11.5f); setTextColor(Palette.ink2); setPadding(Ui.u(4f), Ui.u(6f), Ui.u(8f), Ui.u(2f))
            setOnClickListener { settingsBox.visibility = if (settingsBox.visibility == GONE) VISIBLE else GONE }
        }, LayoutParams(-2, -2).apply { gravity = Gravity.END })

        // GD 音乐台选项：平台 + 音质 + 请求额度
        gdBox.orientation = VERTICAL
        fun chipRow(label: String, items: List<String>, store: ArrayList<TextView>, pick: (Int) -> Unit) = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply { text = label; px(11f); setTextColor(Palette.ink2); minWidth = Ui.u(40f) })
            items.forEachIndexed { i, t ->
                val c = TextView(ctx).apply {
                    text = t; px(11f); setSingleLine(); setPadding(Ui.u(10f), Ui.u(4f), Ui.u(10f), Ui.u(4f))
                    setOnClickListener { pick(i) }
                }
                store.add(c); addView(c, LayoutParams(-2, -2).apply { rightMargin = Ui.u(5f) })
            }
        }
        gdBox.addView(chipRow(tr("平台"), listOf("netease", "joox", "bilibili"), platChips) { MusicPlayer.gdPlatform = MusicPlayer.GD_PLATFORMS[it]; styleChips() },
            LayoutParams(-1, -2).apply { topMargin = Ui.u(6f) })
        gdBox.addView(chipRow(tr("音质"), listOf("128", "192", "320", tr("740 无损"), tr("999 Hi-Res")), brChips) { MusicPlayer.gdBr = MusicPlayer.GD_BRS[it]; styleChips() },
            LayoutParams(-1, -2).apply { topMargin = Ui.u(4f) })
        quota.apply { px(10.5f); setTextColor(Palette.ink2) }
        gdBox.addView(quota, LayoutParams(-2, -2).apply { topMargin = Ui.u(4f) })
        addView(gdBox, LayoutParams(-1, -2))

        settingsBox.orientation = VERTICAL
        settingsBox.visibility = GONE
        settingsBox.addView(field("Jamendo client_id", MusicPlayer.jamendoId, false) { MusicPlayer.jamendoId = it })
        settingsBox.addView(field(tr("网易云 API 地址（你自己的服务）"), MusicPlayer.neteaseBase, true) { MusicPlayer.neteaseBase = it })
        settingsBox.addView(field(tr("网易云 Cookie（可选，MUSIC_U=…）"), MusicPlayer.neteaseCookie, false) { MusicPlayer.neteaseCookie = it })
        settingsBox.addView(field(tr("自定义 API 地址（https://…）"), MusicPlayer.customBase, true) { MusicPlayer.customBase = it })

        // 搜索
        input.apply {
            hint = tr("搜索歌曲 / 歌手"); px(13f); setTextColor(Palette.ink); setHintTextColor(Palette.ink2)
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = rr(Palette.glass, Ui.f(14f), Palette.line, 1)
            setPadding(Ui.u(14f), Ui.u(9f), Ui.u(14f), Ui.u(9f))
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false }
        }
        val searchRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        searchRow.addView(input, LayoutParams(0, -2, 1f))
        searchRow.addView(pillButton(ctx, tr("搜索"), 0) { doSearch() }, LayoutParams(-2, -2).apply { leftMargin = Ui.u(8f) })
        addView(searchRow, LayoutParams(-1, -2).apply { topMargin = Ui.u(8f) })
        addView(settingsBox, LayoutParams(-1, -2).apply { topMargin = Ui.u(6f) })

        // 结果
        results.orientation = VERTICAL
        hint.apply { px(12f); setTextColor(Palette.ink2); text = tr("在上面搜索，点一首歌开始播放") }
        results.addView(hint)
        addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
            addView(results)
        }, if (page) LayoutParams(-1, 0, 1f).apply { topMargin = Ui.u(8f) } else LayoutParams(-1, Ui.u(170f)).apply { topMargin = Ui.u(8f) })

        addView(View(ctx).apply { setBackgroundColor(Palette.line) }, LayoutParams(-1, Ui.u(1f)).apply { topMargin = Ui.u(8f) })

        // 正在播放：封面 + 歌名 / 歌手 / 当前歌词
        title.apply { px(14f); bold(); setTextColor(Palette.ink); setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        sub.apply { px(11.5f); setTextColor(Palette.ink2); setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        lyric.apply { px(12.5f); setTextColor(Palette.brown); setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        time.apply { px(11f); setTextColor(Palette.ink2) }
        cover.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rr(Palette.glass, Ui.f(12f), Palette.line, 1)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, Ui.f(12f))
            }
        }
        val info = LinearLayout(ctx).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(title, LayoutParams(-1, -2)); addView(sub, LayoutParams(-1, -2))
            addView(lyric, LayoutParams(-1, -2).apply { topMargin = Ui.u(3f) })
        }
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(cover, LayoutParams(Ui.u(56f), Ui.u(56f)))
            addView(info, LayoutParams(0, -2, 1f).apply { leftMargin = Ui.u(12f) })
        }, LayoutParams(-1, -2).apply { topMargin = Ui.u(10f) })
        addView(bar, LayoutParams(-1, Ui.u(22f)).apply { topMargin = Ui.u(6f) })
        addView(time, LayoutParams(-2, -2).apply { gravity = Gravity.END })

        val ctl = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
        ctl.addView(modeBtn, LayoutParams(Ui.u(46f), Ui.u(40f)))
        ctl.addView(MusicIconButton(ctx, MusicIconButton.PREV) { MusicPlayer.prev() }, LayoutParams(Ui.u(46f), Ui.u(40f)))
        ctl.addView(playBtn, LayoutParams(Ui.u(56f), Ui.u(40f)))
        ctl.addView(MusicIconButton(ctx, MusicIconButton.NEXT) { MusicPlayer.next() }, LayoutParams(Ui.u(46f), Ui.u(40f)))
        ctl.addView(MusicIconButton(ctx, MusicIconButton.STOP) { MusicPlayer.stop() }, LayoutParams(Ui.u(46f), Ui.u(40f)))
        addView(ctl, LayoutParams(-1, -2).apply { topMargin = Ui.u(2f) })

        credit.apply { px(10f); setTextColor(Palette.ink2); text = tr("音源：GD音乐台(music.gdstudio.xyz) · 仅供学习，禁止商用") }
        addView(credit, LayoutParams(-2, -2).apply { topMargin = Ui.u(4f) })

        styleChips()
        refresh(true)
    }

    private fun field(hintText: String, value: String, url: Boolean, save: (String) -> Unit) = EditText(context).apply {
        hint = hintText; setText(value); px(12f); setTextColor(Palette.ink); setHintTextColor(Palette.ink2)
        setSingleLine(); inputType = if (url) InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_CLASS_TEXT
        background = rr(Palette.glass, Ui.f(12f), Palette.line, 1)
        setPadding(Ui.u(12f), Ui.u(7f), Ui.u(12f), Ui.u(7f))
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { save(s.toString()) }
        })
        layoutParams = LayoutParams(-1, -2).apply { topMargin = Ui.u(4f) }
    }

    private fun chipStyle(c: TextView, on: Boolean) {
        c.background = rr(if (on) Palette.brown else Palette.glass, Ui.f(99f), Palette.line, if (on) 0 else 1)
        c.setTextColor(if (on) 0xFFFFFFFF.toInt() else Palette.ink)
    }

    private fun styleChips() {
        for (i in chips.indices) chipStyle(chips[i], chipIdx[i] == MusicPlayer.sourceIdx)
        val gd = MusicPlayer.sourceIdx == MusicPlayer.SOURCE_GD
        gdBox.visibility = if (gd) VISIBLE else GONE
        credit.visibility = if (gd) VISIBLE else GONE
        for (i in platChips.indices) chipStyle(platChips[i], MusicPlayer.GD_PLATFORMS[i] == MusicPlayer.gdPlatform)
        for (i in brChips.indices) chipStyle(brChips[i], MusicPlayer.GD_BRS[i] == MusicPlayer.gdBr)
        updateQuota()
    }

    private fun updateQuota() {
        quota.text = tf("近 5 分钟已请求 %d / %d 次（搜索 1 次，播放一首约 3 次）", GdRate.used(), GdRate.MAX)
    }

    private fun doSearch() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        results.removeAllViews(); hint.text = tr("搜索中…"); results.addView(hint)
        MusicPlayer.search(q) { list, err ->
            updateQuota()
            results.removeAllViews()
            if (list == null) { hint.text = tr("搜索失败：") + (err ?: ""); results.addView(hint); return@search }
            found = list
            if (list.isEmpty()) { hint.text = tr("没有结果"); results.addView(hint); return@search }
            list.forEachIndexed { i, t -> results.addView(row(t, i), LayoutParams(-1, -2)) }
        }
    }

    private fun row(t: Track, i: Int) = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(Ui.u(10f), Ui.u(7f), Ui.u(10f), Ui.u(7f))
        background = ripple(null, Ui.f(12f))
        setOnClickListener { MusicPlayer.playList(found, i) }
        addView(TextView(context).apply { text = t.title; px(13f); setTextColor(Palette.ink); setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
        val d = if (t.durationMs > 0) "  ·  " + MusicPlayer.fmt(t.durationMs) else ""
        addView(TextView(context).apply { text = t.artist.ifEmpty { "—" } + d; px(11f); setTextColor(Palette.ink2); setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
    }

    /** 只在内容真的变了才 setText，避免播放时每 250ms 触发整页重新排版 */
    private fun TextView.setIf(v: String) { if (text.toString() != v) text = v }

    private var shownCover: android.graphics.Bitmap? = null

    private fun refresh(trackChanged: Boolean) {
        val t = MusicPlayer.track
        title.setIf(t?.title ?: tr("未在播放"))
        sub.setIf(when {
            MusicPlayer.error != null -> tr("出错：") + MusicPlayer.error
            MusicPlayer.loading -> tr("加载中…")
            t != null -> t.artist
            else -> ""
        })
        lyric.setIf(MusicPlayer.currentLine())
        playBtn.icon = if (MusicPlayer.playing) MusicIconButton.PAUSE else MusicIconButton.PLAY
        modeBtn.icon = intArrayOf(MusicIconButton.LOOP, MusicIconButton.LOOP_ONE, MusicIconButton.SHUFFLE)[MusicPlayer.mode]
        time.setIf(MusicPlayer.fmt(MusicPlayer.posMs) + " / " + MusicPlayer.fmt(MusicPlayer.durMs))
        val cb = MusicPlayer.coverBmp
        if (cb !== shownCover) { shownCover = cb; cover.setImageBitmap(cb) }
        if (trackChanged) updateQuota()
        bar.invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); MusicPlayer.addListener(listener); refresh(true) }
    override fun onDetachedFromWindow() { MusicPlayer.removeListener(listener); super.onDetachedFromWindow() }

    /** 可拖动的播放进度条 */
    private class ProgressBar(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val h = Ui.f(4f); val cy = height / 2f; val pad = Ui.f(2f)
            val f = if (MusicPlayer.durMs > 0) (MusicPlayer.posMs.toFloat() / MusicPlayer.durMs).coerceIn(0f, 1f) else 0f
            r.set(pad, cy - h / 2, width - pad, cy + h / 2); p.color = Palette.line; c.drawRoundRect(r, h, h, p)
            r.right = pad + (width - 2 * pad) * f; p.color = Palette.brown; c.drawRoundRect(r, h, h, p)
            c.drawCircle(r.right, cy, Ui.f(6f), p)
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (MusicPlayer.durMs <= 0) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                MusicPlayer.seekTo((MusicPlayer.durMs * (e.x / width).coerceIn(0f, 1f)).toLong())
            }
            return true
        }
    }
}

/** 音乐整页（和账号页一样占满右侧主区域）：标题栏 + 面板 */
@SuppressLint("ViewConstructor")
class MusicPageView(ctx: Context, host: SheetHost) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.u(26f), Ui.u(18f), Ui.u(26f), Ui.u(14f))
            addView(TextView(ctx).apply { text = tr("音乐"); px(22f); bold(); setTextColor(Palette.ink) })
            addView(TextView(ctx).apply { text = tr("内置播放器 · 联网搜索播放"); px(12f); setTextColor(Palette.ink2) },
                LayoutParams(-2, -2).apply { leftMargin = Ui.u(14f) })
        })
        addView(View(ctx).apply { setBackgroundColor(Palette.line) }, LayoutParams(-1, Ui.u(1f)))
        addView(MusicPanelView(ctx, host, true).apply { setPadding(Ui.u(26f), Ui.u(14f), Ui.u(26f), Ui.u(18f)) }, LayoutParams(-1, 0, 1f))
    }
}
