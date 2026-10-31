package com.rlin.inject

import android.app.Activity
import android.content.Context
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.nemoclient.app.music.MusicPlayer
import java.io.File
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 彩蛋「邦友の酒」：长按音乐入口触发。
 *  - 一个带声音的小视频窗口在整个屏幕上像早期 DVD 待机画面那样来回弹（撞到屏幕边缘就反弹）；
 *  - 同时，属于本客户端的所有控件（各个 HUD、悬浮球、悬浮快捷键、ClickGUI 面板、视频本身）跟着音乐的鼓点上下 Q 弹：
 *    鼓点来的一瞬间被压扁，然后弹起、落地、再抖几下，每个控件错开一点点时间，看起来像一排果冻在依次弹跳；
 *  - 长按视频即可结束，所有控件恢复原样。
 * 鼓点时间表是离线从音频里检测出来的（assets/bangyou_beats.txt），播放时对着 MediaPlayer 的当前位置触发，
 * 所以不需要录音权限，也不需要实时分析音频。
 *
 * 视频窗口只有视频那么大，窗口外的触摸照常交给游戏。
 * 控件是 WindowManager 里的独立窗口时用“移动窗口”来跳（窗口里的缩放只用来压扁 / 拉长），
 * 不是独立窗口的 View（比如桌面 Demo 里的控件）直接用 translationY。
 */
class BounceShow(
    private val act: Activity,
    private val ctx: Context,
    private val wm: WindowManager,
    private val targets: () -> List<View>,
    private val onStop: () -> Unit
) {
    private val dm = ctx.resources.displayMetrics
    private val d = dm.density
    private var win: FrameLayout? = null
    private var card: FrameLayout? = null
    private var winLp: WindowManager.LayoutParams? = null
    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    private var running = false
    private var pausedMusic = false

    // DVD 运动
    private var px = 0f
    private var py = 0f
    private var vx = 0f
    private var vy = 0f
    private var lastFrame = 0L

    // 鼓点
    private var beats = FloatArray(0)
    private var beatIdx = 0
    private var lastPos = 0
    private var lastBeatAt = -10f          // 上一次鼓点的时间（秒，单调时钟）
    private var kick = 1f                  // 上一次的力度（鼓点 1.0，撞边 0.7）

    private val baseY = WeakHashMap<View, Int>()
    private val lastY = WeakHashMap<View, Int>()
    private val pad = (22 * d).toInt()

    val active: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        loadBeats()
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val vw = (min(sw, sh) * 0.80f).toInt().coerceAtMost((sw * 0.55f).toInt()).coerceAtLeast((200 * d).toInt())
        val vh = vw * 9 / 16
        val w = vw + 2 * pad
        val h = vh + 2 * pad

        val tv = TextureView(ctx)
        val cardView = FrameLayout(ctx).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 14 * d)
            }
            setBackgroundColor(0xFF000000.toInt())
            addView(tv, FrameLayout.LayoutParams(-1, -1))
        }
        val root = FrameLayout(ctx)
        root.addView(cardView, FrameLayout.LayoutParams(vw, vh, Gravity.CENTER))
        cardView.pivotX = vw / 2f
        cardView.pivotY = vh.toFloat()

        val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) { stop() }
        })
        root.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }

        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        px = (sw - w) * 0.3f; py = (sh - h) * 0.25f
        vx = sw * 0.17f; vy = sw * 0.12f
        lp.x = px.toInt(); lp.y = py.toInt()
        runCatching { wm.addView(root, lp) }.onFailure { running = false; return }
        win = root; card = cardView; winLp = lp

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st); startPlayer() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { runCatching { surface?.release() }; surface = null; return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
        runCatching { mp?.stop() }; runCatching { mp?.release() }; mp = null
        runCatching { surface?.release() }; surface = null
        win?.let { runCatching { wm.removeView(it) } }
        win = null; card = null; winLp = null
        if (pausedMusic && !MusicPlayer.playing) runCatching { MusicPlayer.toggle() }
        pausedMusic = false
        // 复位所有控件
        for (v in baseY.keys.toList()) restore(v)
        baseY.clear(); lastY.clear()
        onStop()
    }

    private fun restore(v: View) {
        v.scaleX = 1f; v.scaleY = 1f; v.translationY = 0f
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        baseY[v]?.let { lp.y = it; runCatching { wm.updateViewLayout(v, lp) } }
    }

    // ---------------- 播放 ----------------
    private fun loadBeats() {
        beats = runCatching {
            ctx.assets.open("bangyou_beats.txt").bufferedReader().use { it.readText() }
                .trim().split(' ').filter { it.isNotEmpty() }.map { it.toFloat() }.toFloatArray()
        }.getOrElse { FloatArray(0) }
        beatIdx = 0; lastPos = 0
    }

    private fun startPlayer() {
        val s = surface ?: return
        if (MusicPlayer.playing) { runCatching { MusicPlayer.toggle() }; pausedMusic = true }
        val p = MediaPlayer()
        mp = p
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            var ok = false
            runCatching {
                ctx.assets.openFd("bangyou.mp4").use { afd -> p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length) }
                ok = true
            }
            if (!ok) {   // 资源被压缩 / 取不到 fd：拷到缓存文件再放
                val f = File(ctx.cacheDir, "bangyou.mp4")
                if (!f.exists() || f.length() < 1000L) ctx.assets.open("bangyou.mp4").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                p.setDataSource(f.absolutePath)
            }
            p.setSurface(s)
            p.isLooping = true
            p.setOnPreparedListener { it.start() }
            p.setOnErrorListener { _, _, _ -> fail("视频播放失败"); true }
            p.prepareAsync()
        } catch (e: Throwable) {
            fail("找不到视频文件")
        }
    }

    private fun fail(msg: String) {
        act.runOnUiThread { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); stop() }
    }

    // ---------------- 每帧：DVD 运动 + 鼓点 + 弹跳 ----------------
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceAtMost(0.05f)
            lastFrame = now
            val t = now / 1000f
            stepDvd(dt, t)
            stepBeats(t)
            applyBounce(t)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stepDvd(dt: Float, t: Float) {
        val lp = winLp ?: return
        val maxX = (dm.widthPixels - lp.width).coerceAtLeast(0).toFloat()
        val maxY = (dm.heightPixels - lp.height).coerceAtLeast(0).toFloat()
        px += vx * dt; py += vy * dt
        var hit = false
        if (px < 0f) { px = 0f; vx = kotlin.math.abs(vx); hit = true }
        if (px > maxX) { px = maxX; vx = -kotlin.math.abs(vx); hit = true }
        if (py < 0f) { py = 0f; vy = kotlin.math.abs(vy); hit = true }
        if (py > maxY) { py = maxY; vy = -kotlin.math.abs(vy); hit = true }
        if (hit) { lastBeatAt = t; kick = 0.7f }   // 撞边也算一次弹跳
        winBaseX = px; winBaseY = py
    }

    private var winBaseX = 0f
    private var winBaseY = 0f

    private fun stepBeats(t: Float) {
        val p = mp ?: return
        val pos = runCatching { p.currentPosition }.getOrDefault(0)
        if (pos < lastPos - 500) beatIdx = 0          // 循环回到开头
        lastPos = pos
        val sec = pos / 1000f
        var fired = false
        while (beatIdx < beats.size && beats[beatIdx] <= sec) {
            // 落后太多（比如拖动 / 卡顿）的鼓点直接跳过，只对最新的一个起反应
            if (sec - beats[beatIdx] < 0.12f) fired = true
            beatIdx++
        }
        if (fired) { lastBeatAt = t; kick = 1f }
        if (beats.isEmpty() && t - lastBeatAt > 0.48f) { lastBeatAt = t; kick = 1f }   // 没有鼓点表：固定 125 BPM
    }

    /** 一个控件在某个时刻的弹跳：返回 (纵向缩放, 横向缩放, 向上跳起的像素) */
    private fun bounceOf(t: Float, delay: Float, out: FloatArray) {
        val p = t - lastBeatAt - delay
        if (p < 0f || p > 1.2f) { out[0] = 1f; out[1] = 1f; out[2] = 0f; return }
        val q = exp(-p * 6.5f) * cos(p * 21f)                 // 1 = 压到最扁，随后过冲、回弹、衰减
        out[0] = 1f - 0.17f * kick * q
        out[1] = 1f + 0.10f * kick * q
        val jump = if (p < 0.34f) sin(p / 0.34f * PI.toFloat()) else 0f
        out[2] = jump * 15f * d * kick
    }

    private val tmp = FloatArray(3)

    private fun applyBounce(t: Float) {
        // 视频窗口：位置 = DVD 位置 − 跳起高度；卡片压扁
        val lp = winLp
        val w = win
        val c = card
        if (lp != null && w != null && c != null) {
            bounceOf(t, 0f, tmp)
            c.scaleY = tmp[0]; c.scaleX = tmp[1]
            lp.x = winBaseX.toInt()
            lp.y = (winBaseY - tmp[2]).toInt().coerceAtLeast(0)
            runCatching { wm.updateViewLayout(w, lp) }
        }
        // 其它控件：按顺序错开 18ms，像一排果冻依次弹
        val list = targets()
        for ((i, v) in list.withIndex()) {
            if (v.width == 0) continue
            bounceOf(t, 0.018f * (i + 1), tmp)
            v.pivotX = v.width / 2f; v.pivotY = v.height.toFloat()
            v.scaleY = tmp[0]; v.scaleX = tmp[1]
            val wlp = v.layoutParams as? WindowManager.LayoutParams
            if (wlp != null) {
                val base = baseY.getOrPut(v) { wlp.y }
                val y = base - tmp[2].toInt()
                if (lastY[v] != y) { lastY[v] = y; wlp.y = y; runCatching { wm.updateViewLayout(v, wlp) } }
            } else {
                baseY.getOrPut(v) { 0 }
                v.translationY = -tmp[2]
            }
        }
    }
}
