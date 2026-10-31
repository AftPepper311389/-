package com.rlin.inject

import com.nemoclient.app.i18n.tr
import android.graphics.PixelFormat
import android.graphics.RectF
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.Choreographer
import android.view.WindowInsets
import android.view.WindowInsetsController
import com.nemoclient.app.data.HudNotify
import com.nemoclient.app.data.NotifyKind
import com.nemoclient.app.ui.BlocksHudView
import com.nemoclient.app.ui.CoordsHudView
import com.nemoclient.app.ui.RadarHudView
import com.nemoclient.app.ui.IslandHudView
import com.nemoclient.app.ui.PotionHudView
import com.nemoclient.app.ui.SpeedHudView
import com.nemoclient.app.ui.IslandPanelView
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import com.nemoclient.app.data.Category
import com.nemoclient.app.data.HudInfo
import com.nemoclient.app.data.HudModuleRow
import com.nemoclient.app.data.HudType
import com.nemoclient.app.data.Setting
import com.nemoclient.app.data.TargetFeed
import com.nemoclient.app.data.TargetInfo
import com.nemoclient.app.data.SampleData
import com.nemoclient.app.data.AccountStore
import com.nemoclient.app.data.Module
import com.nemoclient.app.i18n.I18n
import com.nemoclient.app.data.ModuleRepo
import com.nemoclient.app.ui.ClickGuiView
import com.nemoclient.app.ui.FloatingBallView
import com.nemoclient.app.ui.HudCanvasView
import com.nemoclient.app.ui.HudGuideView
import com.nemoclient.app.ui.ModuleListHudView
import com.nemoclient.app.ui.NotifyHudView
import com.nemoclient.app.ui.TargetHudView
import com.nemoclient.app.ui.Palette
import com.nemoclient.app.ui.WatermarkHudView
import com.nemoclient.app.ui.rr
import com.nemoclient.app.ui.withAlpha
import com.nemoclient.app.ui.ShortcutKeyView
import com.nemoclient.app.ui.Ui
import com.nemoclient.app.ui.dp
import com.nemoclient.app.ui.makeDraggable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * 游戏内宿主（替代原来的 OverlayService）：悬浮球 + GUI + 各 HUD 都是游戏 Activity 自己的子窗口，
 * 不需要悬浮窗权限。毛玻璃由原生层在游戏画面里实时画（见 Native.nativeSetRects），
 * 这里每帧把“HUD 内容框 / GUI 面板”的位置告诉它。
 *
 * demo = true（桌面 DemoActivity）时用同一套逻辑：悬浮球 / GUI / HUD / 编辑模式全部照旧，
 * 只是没有游戏画面 —— 原生调用全部跳过，毛玻璃矩形改交给 demoBlurSink（2D 预模糊背景图）。
 */
class InGameHost(private val act: Activity, private val ctx: Context, private val demo: Boolean = false) {

    /** demo 模式：毛玻璃矩形出口（每 6 个 float 一组 x,y,w,h,radius,tintAlpha，屏幕像素，原点左上） */
    var demoBlurSink: ((FloatArray) -> Unit)? = null

    private companion object {
        const val WIZARD_W = 500f
        const val WIZARD_H = 440f
        val DEMO_NAMES = listOf("Steve", "Alex_2077", "Notch", "DragonSlayer")
        const val TAG = "rlin"
    }

    private fun dp(x: Int) = ctx.dp(x)
    private val resources get() = ctx.resources
    private var started = false
    private var lastRects = FloatArray(0)
    private var rectLogs = 0
    private var panel: IslandPanelView? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var catcher: View? = null
    private val tmpRect = RectF()

    private lateinit var wm: WindowManager
    private lateinit var repo: ModuleRepo
    private lateinit var accounts: AccountStore
    private var guiOpenedAt = 0L
    private var ball: FloatingBallView? = null
    private var guiRoot: GuiRoot? = null
    private var guiView: ClickGuiView? = null
    private val shortcuts = mutableMapOf<String, ShortcutKeyView>()
    private val huds = mutableMapOf<HudType, HudCanvasView>()
    private var moduleRows: List<HudModuleRow> = emptyList()
    private val hudLps = mutableMapOf<HudType, WindowManager.LayoutParams>()
    private var hudEditing = false
    private val main = Handler(Looper.getMainLooper())
    private val feedListener: () -> Unit = { main.post { huds[HudType.TARGET]?.kick() } }
    // 演示用目标：每 1.1s 掉一点血，打空后换一个新目标（接入真实数据后在 HUD 添加页关掉“演示数据”）
    private val notifyListener: (String, String, NotifyKind) -> Unit = { title, msg, kind ->
        main.post {
            val k = kind.ordinal
            (huds[HudType.NOTIFY] as? NotifyHudView)?.pushMessage(title, msg, k)
            (huds[HudType.ISLAND] as? IslandHudView)?.let { if (repo.hudBool(HudType.ISLAND, "notify", true)) it.pushMessage(title, msg, k) }
        }
    }
    private val toggleListener: (Module, Boolean) -> Unit = { m, on -> main.post { onModuleToggled(m, on) } }
    private var demoRunning = false
    private var demoIdx = 0
    private var demoHp = 20f
    private var demoPause = 0
    private var editBar: View? = null
    private var guide: HudGuideView? = null
    private var layoutSeen = 0
    private val repoListener: () -> Unit = { syncShortcuts(); syncHuds() }

    /** GUI 窗口根：点面板外关闭、返回键处理 */
    private class GuiRoot(ctx: Context) : FrameLayout(ctx) {
        var onBack: (() -> Unit)? = null

        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                if (e.action == KeyEvent.ACTION_UP) onBack?.invoke()
                return true
            }
            return super.dispatchKeyEvent(e)
        }
    }

    fun start() {
        if (started) return
        started = true
        I18n.load(ctx)
        wm = act.windowManager
        repo = ModuleRepo(ctx)
        accounts = AccountStore(ctx)
        repo.applyAppearance()
        layoutSeen = repo.layoutVersion
        applyUnit()
        addBall()
        syncShortcuts()
        TargetFeed.addListener(feedListener)
        repo.addToggleListener(toggleListener)
        HudNotify.addListener(notifyListener)
        syncHuds()
        repo.addListener(repoListener)
        main.post(fpsTick)
        Choreographer.getInstance().postFrameCallback(blurTick)
        Log.i(TAG, "InGameHost started")
    }

    /** 返回键：GUI 开着时由宿主接管（Activity 的按键分发被钩住后调用）。返回 true = 已处理 */
    fun wantsBack() = hudEditing

    /** 设计稿单位（和 openGui 里同一套算法）：灵动岛设置面板不开 GUI 也要用到 */
    private fun applyUnit() {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()
        val aspect = if (sh > sw) 0.78f else 1120f / 680f
        var w = min(sw * 0.94f, sh * 0.92f * aspect) * repo.uiScale()
        w = min(w, sw * 0.98f)
        val h = w / aspect
        if (h > sh * 0.98f) w = sh * 0.98f * aspect
        Ui.unit = w / Ui.DESIGN_W
    }

    fun onBack(): Boolean {
        if (hudEditing) { exitHudEdit(); return true }
        val g = guiView ?: return false
        g.handleBack()
        return true
    }

    private val fpsTick = object : Runnable {
        override fun run() {
            if (demo) {
                // 独立 App 没有游戏帧率可读：给一个缓慢游走的演示值，让水印别停在 0
                fakeFps += listOf(-1, 0, 0, 1).random()
                fakeFps = fakeFps.coerceIn(114, 122)
                HudInfo.fps = fakeFps
            } else {
                val f = Native.nativeFps()
                if (f > 0) HudInfo.fps = f
            }
            main.postDelayed(this, 500)
        }
    }
    private var fakeFps = 119

    /** 每帧把需要模糊的区域交给原生层（和上一帧一样就不调用） */
    private val blurTick = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!started) return
            runCatching { pushBlurRects() }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val rectBuf = ArrayList<Float>(64)
    private val locBuf = IntArray(2)

    private var lightSent = false

    private fun pushBlurRects() {
        val wantLight = if (guiView != null) Palette.light else Palette.hudLight
        if (lightSent != wantLight) { lightSent = wantLight; if (!demo) Native.nativeSetLight(lightSent) }
        val out = rectBuf
        out.clear()
        if (repo.blurEnabled()) {
            // 全部用屏幕坐标（getLocationOnScreen），和原生层 / demo 的 BlurLayer 同一坐标系——
            // 子窗口坐标（lp.x/y）在有挖孔 / 裁切的屏上可能与屏幕坐标有出入，之前 demo 里模糊层错位就是它
            huds.forEach { (t, v) ->
                if (v.visibility != View.VISIBLE) return@forEach
                v.getLocationOnScreen(locBuf)
                v.collectBlur(out, locBuf[0].toFloat(), locBuf[1].toFloat())
            }
            val pv = panel
            if (pv != null && pv.p > 0.02f) {
                pv.getLocationOnScreen(locBuf)
                val r = pv.currentRect(tmpRect)
                out.add((locBuf[0] + tmpRect.left).toFloat()); out.add((locBuf[1] + tmpRect.top).toFloat())
                out.add(tmpRect.width()); out.add(tmpRect.height()); out.add(r); out.add(0.34f)
            }
            val gv = guiView
            val gr = guiRoot
            if (gv != null && gr != null && gv.isAttachedToWindow && gv.width > 0) {
                // 布局位置 + 打开动画的 translationY：面板从下方滑入时模糊层跟着走（不对齐就变成“先到位再跟上”），
                // 3D 倾斜不管（620ms 内，且倾斜下取投影 bounds 会跳）；只随淡入淡出的 alpha 出现
                val loc = locBuf
                gr.getLocationOnScreen(loc)
                val a = ((gv.alpha - 0.35f) / 0.65f).coerceIn(0f, 1f)
                if (a > 0.02f) {
                    out.add((loc[0] + gv.left).toFloat()); out.add((loc[1] + gv.top + gv.translationY).toFloat())
                    out.add(gv.width.toFloat()); out.add(gv.height.toFloat()); out.add(Ui.f(24f)); out.add(0.34f * a)
                }
            }
        }
        val arr = out.toFloatArray()
        if (!arr.contentEquals(lastRects)) {
            lastRects = arr
            if (demo) demoBlurSink?.invoke(arr) else Native.nativeSetRects(arr)
            if (rectLogs++ < 6) Log.i(TAG, "blur rects n=${arr.size / 6} first=${arr.take(6)}")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        if (!demo) Native.nativeSetRects(FloatArray(0))
        if (::repo.isInitialized) repo.removeListener(repoListener)
        bounce?.stop()
        closeGui(false)
        shortcuts.values.forEach { runCatching { wm.removeView(it) } }
        shortcuts.clear()
        TargetFeed.removeListener(feedListener)
        HudNotify.removeListener(notifyListener)
        if (::repo.isInitialized) repo.removeToggleListener(toggleListener)
        main.removeCallbacksAndMessages(null)
        demoRunning = false
        huds.values.forEach { runCatching { wm.removeView(it) } }
        huds.clear(); hudLps.clear()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        catcher?.let { runCatching { wm.removeView(it) } }
        catcher = null
        editBar?.let { runCatching { wm.removeView(it) } }
        editBar = null
        guide?.let { runCatching { wm.removeView(it) } }
        guide = null
        ball?.let { runCatching { wm.removeView(it) } }
        ball = null
    }

    // ---------------- 悬浮窗参数 ----------------
    private fun overlayLp(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    // ---------------- 悬浮球 ----------------
    private fun addBall() {
        val v = FloatingBallView(ctx)
        val size = dp(40)
        val lp = overlayLp(size, size).apply {
            x = repo.posX("ball", dp(12)); y = repo.posY("ball", dp(200))
        }
        v.makeDraggable(wm, lp, onTap = { toggleGui() }, onDrop = { x, y -> repo.setPos("ball", x, y) }, onPress = { v.pressFx(it) })
        wm.addView(v, lp)
        ball = v
    }

    // ---------------- 悬浮快捷键 ----------------
    private fun syncShortcuts() {
        val want = repo.modules.filter { repo.hasShortcut(it) }
        shortcuts.keys.filter { id -> want.none { it.id == id } }.forEach { id ->
            shortcuts.remove(id)?.let { runCatching { wm.removeView(it) } }
        }
        want.forEachIndexed { i, m ->
            val v = shortcuts[m.id]
            if (v == null) addShortcut(m, i) else v.bind(m.name, repo.isOn(m))
        }
    }

    private fun addShortcut(m: Module, index: Int) {
        val v = ShortcutKeyView(ctx).apply { bind(m.name, repo.isOn(m)) }
        val key = "sc.${m.id}"
        val lp = overlayLp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = repo.posX(key, dp(72)); y = repo.posY(key, dp(120) + index * dp(38))
        }
        v.makeDraggable(wm, lp, onTap = { repo.setOn(m, !repo.isOn(m)) }, onDrop = { x, y -> repo.setPos(key, x, y) }, onPress = { v.pressFx(it) })
        runCatching { wm.addView(v, lp); shortcuts[m.id] = v }
    }

    // ---------------- GUI 窗口 ----------------
    private fun toggleGui() {
        if (hudEditing) exitHudEdit()
        if (guiRoot == null) openGui(true) else closeGui(true)
    }

    // ---------------- HUD（水印 / 模块列表）----------------
    private fun hudKey(t: HudType) = "hud2.${t.id}"

    /** 按开关增删 HUD 窗口，并刷新内容 */
    private fun syncHuds() {
        HudInfo.user = accounts.profile.nickname
        HudCanvasView.blurAvailable = repo.blurEnabled()
        moduleRows = repo.modules.filter { repo.isOn(it) && it.id != SampleData.GUI_ID }.map { m ->
            val ch = m.settings.filterIsInstance<Setting.Choice>().firstOrNull()
            val suffix = ch?.let { tr(it.options[repo.getInt(m.id, it.key, it.def).coerceIn(0, it.options.size - 1)]) }
            HudModuleRow(tr(m.name), suffix)
        }
        // 载入布局方案 / 重置 HUD 之后，已显示的 HUD 要按仓库里的位置重新摆放
        val reload = repo.layoutVersion != layoutSeen
        layoutSeen = repo.layoutVersion
        HudType.values().forEach { t ->
            val v = huds[t]
            if (!repo.hudOn(t)) {
                if (v != null) { runCatching { wm.removeView(v) }; huds.remove(t); hudLps.remove(t) }
            } else if (v == null) addHud(t) else bindHud(t, v, reload)
        }
        if (hudEditing && huds.isEmpty()) exitHudEdit()
        updateDemo()
    }

    /** 模块被开关：GUI 打开时不提示（本来就看得到）；ClickGUI 管理自身的开关也不提示 */
    private fun onModuleToggled(m: Module, on: Boolean) {
        if (guiRoot != null || m.id == SampleData.GUI_ID) return
        (huds[HudType.NOTIFY] as? NotifyHudView)?.push(tr(m.name), on)
        val isl = huds[HudType.ISLAND] as? IslandHudView
        if (isl != null && repo.hudBool(HudType.ISLAND, "notify", true) && (on || repo.hudBool(HudType.ISLAND, "off", true))) isl.push(tr(m.name), on)
    }

    private fun demoInfo() = TargetInfo(DEMO_NAMES[demoIdx % DEMO_NAMES.size], demoHp, 20f, 3f + (0..30).random() / 10f)

    private fun updateDemo() {
        val want = repo.hudOn(HudType.TARGET) && repo.hudBool(HudType.TARGET, "demo", true)
        if (want && !demoRunning) {
            demoRunning = true; demoHp = 20f; demoPause = 0
            TargetFeed.push(demoInfo())
            main.postDelayed(demoTick, 1100)
        } else if (!want && demoRunning) {
            demoRunning = false
            main.removeCallbacks(demoTick)
            TargetFeed.push(null)
        }
    }

    private val demoTick = object : Runnable {
        override fun run() {
            if (!demoRunning) return
            if (demoPause > 0) {
                demoPause--
                if (demoPause == 0) { demoIdx++; demoHp = 20f; TargetFeed.push(demoInfo()) }
            } else {
                demoHp -= listOf(1f, 2f, 3f, 4f).random()
                if (demoHp <= 0f) { TargetFeed.push(null); demoPause = 2 } else TargetFeed.push(demoInfo())
            }
            main.postDelayed(this, 1100)
        }
    }

    private fun defaultX(t: HudType, v: HudCanvasView, ww: Int): Int {
        val dm = resources.displayMetrics
        return when (t) {
            HudType.WATERMARK, HudType.TARGET, HudType.ISLAND, HudType.BLOCKS -> (dm.widthPixels - ww) / 2
            HudType.MODULE_LIST -> dm.widthPixels - ww - dp(4)
            HudType.POTION -> dp(6)
            HudType.FPS -> dp(4)
            HudType.SPEED -> dp(4)
            HudType.COORDS -> dp(4)
            HudType.RADAR -> dm.widthPixels - ww - dp(6)
            HudType.NOTIFY -> dm.widthPixels - ww + (v as NotifyHudView).restInset() - dp(8)
        }
    }

    private fun defaultY(t: HudType, wh: Int): Int {
        val dm = resources.displayMetrics
        return when (t) {
            HudType.WATERMARK -> dp(52)
            HudType.ISLAND -> dp(4)
            HudType.POTION -> dm.heightPixels / 3
            HudType.FPS -> dp(40)
            HudType.SPEED -> dp(40) + dp(84)
            HudType.COORDS -> dp(190)
            HudType.RADAR -> dp(40)
            HudType.BLOCKS -> dm.heightPixels / 2 + dp(64)
            HudType.MODULE_LIST -> dp(8)
            HudType.TARGET -> dm.heightPixels / 2 + dp(24)
            HudType.NOTIFY -> dm.heightPixels - wh - dp(8)
        }
    }

    /** 默认位置：水印顶部居中；模块列表靠右上；目标信息在准星下方；开关提示贴屏幕右下角 */
    private fun placeFromRepo(t: HudType, v: HudCanvasView, lp: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        lp.x = repo.posX(hudKey(t), defaultX(t, v, lp.width)).coerceIn(-lp.width / 2, (dm.widthPixels - dp(48)).coerceAtLeast(0))
        lp.y = repo.posY(hudKey(t), defaultY(t, lp.height)).coerceIn(0, (dm.heightPixels - dp(48)).coerceAtLeast(0))
    }

    private fun addHud(t: HudType) {
        val v: HudCanvasView = when (t) {
            HudType.WATERMARK -> WatermarkHudView(ctx)
            HudType.ISLAND -> IslandHudView(ctx)
            HudType.POTION -> PotionHudView(ctx)
            HudType.FPS -> SpeedHudView(ctx, 0)
            HudType.SPEED -> SpeedHudView(ctx, 1)
            HudType.COORDS -> CoordsHudView(ctx)
            HudType.RADAR -> RadarHudView(ctx)
            HudType.BLOCKS -> BlocksHudView(ctx)
            HudType.MODULE_LIST -> ModuleListHudView(ctx).also { it.source = { moduleRows } }
            HudType.TARGET -> TargetHudView(ctx)
            HudType.NOTIFY -> NotifyHudView(ctx)
        }
        v.cfg = repo.hudCfg(t) // 先带上缩放，窗口大小才对
        val lp = overlayLp(v.windowW(), v.windowH()).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE // 平时不拦截触摸，游戏照常操作
        }
        placeFromRepo(t, v, lp)
        runCatching { wm.addView(v, lp) }.onSuccess {
            huds[t] = v; hudLps[t] = lp
            bindHud(t, v)
            if (hudEditing) setHudEditable(t, v, true)
        }
    }

    /**
     * 把最新的选项值 / 配色 / 缩放交给 HUD 视图，并唤醒它重绘。
     * 大小变了就调整窗口：靠右的 HUD 保持右边缘不动，居中的保持中心不动，其余保持左边缘不动。
     * reposition = true（载入方案 / 重置后）：位置直接按仓库里存的来，不做锚点补偿。
     */
    private fun bindHud(t: HudType, v: HudCanvasView, reposition: Boolean = false) {
        v.cfg = repo.hudCfg(t)
        val lp = hudLps[t]
        if (lp != null) {
            val ww = v.windowW()
            val wh = v.windowH()
            val oldW = lp.width
            val resized = lp.width != ww || lp.height != wh
            lp.width = ww
            lp.height = wh
            if (reposition) {
                placeFromRepo(t, v, lp)
                runCatching { wm.updateViewLayout(v, lp) }
            } else if (resized) {
                val sw = resources.displayMetrics.widthPixels
                val cx = lp.x + oldW / 2f
                lp.x += when {
                    abs(cx - sw / 2f) < sw * 0.08f -> (oldW - ww) / 2
                    cx > sw / 2f -> oldW - ww
                    else -> 0
                }
                runCatching { wm.updateViewLayout(v, lp) }
                repo.setPos(hudKey(t), lp.x, lp.y)
            }
        }
        v.kick()
    }

    /** 编辑模式：HUD 变为可触摸、可拖动（主题色描边）；退出后恢复穿透 */
    private fun setHudEditable(t: HudType, v: HudCanvasView, editable: Boolean) {
        val lp = hudLps[t] ?: return
        v.editing = editable
        if (editable) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            makeHudDraggable(t, v, lp)
        } else {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            v.setOnTouchListener(null)
        }
        runCatching { wm.updateViewLayout(v, lp) }
    }

    /** 某个 HUD 当前内容在屏幕上的范围（取不到就用整个窗口） */
    private fun contentOnScreen(v: HudCanvasView, lp: WindowManager.LayoutParams, out: RectF) {
        if (!v.contentRect(out)) out.set(0f, 0f, lp.width.toFloat(), lp.height.toFloat())
        out.offset(lp.x.toFloat(), lp.y.toFloat())
    }

    /**
     * 编辑模式的拖动：内容的左 / 中 / 右（上 / 中 / 下）靠近屏幕边缘、屏幕中线、
     * 或别的 HUD 的对应位置时自动吸附（8dp 内），并画出对齐辅助线。
     */
    private fun makeHudDraggable(t: HudType, v: HudCanvasView, lp: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()
        val thr = dp(8).toFloat()
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var relL = 0f
        var relT = 0f
        var relR = 0f
        var relB = 0f
        val tx = ArrayList<Float>()
        val ty = ArrayList<Float>()
        val r = RectF()
        val inner = View.OnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    contentOnScreen(v, lp, r)
                    relL = r.left - lp.x; relT = r.top - lp.y; relR = r.right - lp.x; relB = r.bottom - lp.y
                    tx.clear(); ty.clear()
                    tx.add(0f); tx.add(sw / 2f); tx.add(sw); tx.add(dp(8).toFloat()); tx.add(sw - dp(8))
                    ty.add(0f); ty.add(sh / 2f); ty.add(sh); ty.add(dp(8).toFloat()); ty.add(sh - dp(8))
                    huds.forEach { (ot, ov) ->
                        if (ot == t) return@forEach
                        val olp = hudLps[ot] ?: return@forEach
                        contentOnScreen(ov, olp, r)
                        tx.add(r.left); tx.add(r.centerX()); tx.add(r.right)
                        ty.add(r.top); ty.add(r.centerY()); ty.add(r.bottom)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) moved = true
                    if (moved) {
                        var nx = startX + dx
                        var ny = startY + dy
                        val gx = ArrayList<Float>()
                        val gy = ArrayList<Float>()
                        var bestX = thr + 1f
                        var snapX = 0f
                        for (edge in floatArrayOf(nx + relL, nx + (relL + relR) / 2f, nx + relR)) {
                            for (target in tx) {
                                val diff = target - edge
                                if (abs(diff) < abs(bestX)) { bestX = diff; snapX = target }
                            }
                        }
                        if (abs(bestX) <= thr) { nx += bestX; gx.add(snapX) }
                        var bestY = thr + 1f
                        var snapY = 0f
                        for (edge in floatArrayOf(ny + relT, ny + (relT + relB) / 2f, ny + relB)) {
                            for (target in ty) {
                                val diff = target - edge
                                if (abs(diff) < abs(bestY)) { bestY = diff; snapY = target }
                            }
                        }
                        if (abs(bestY) <= thr) { ny += bestY; gy.add(snapY) }
                        lp.x = nx.toInt().coerceIn(-(lp.width - dp(48)), (sw.toInt() - dp(48)).coerceAtLeast(0))
                        lp.y = ny.toInt().coerceIn(0, (sh.toInt() - dp(48)).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(view, lp) }
                        guide?.show(gx, gy)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) repo.setPos(hudKey(t), lp.x, lp.y)
                    else if (e.actionMasked == MotionEvent.ACTION_UP) selectHud(t)
                    guide?.show(emptyList(), emptyList())
                    true
                }
                else -> false
            }
        }
        // HUD 窗口比实际内容大（透明部分也会吃掉触摸）：按下点不在自己的内容上时，把整段手势转给真正点到的那个 HUD；
        // 谁都没点到就当作点空白处（收起设置面板）
        var fwd: Pair<HudCanvasView, WindowManager.LayoutParams>? = null
        var fwdNone = false
        v.setOnTouchListener { view, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                fwd = null; fwdNone = false
                val rr = RectF()
                val pad = dp(6).toFloat()
                contentOnScreen(v, lp, rr)
                val hasOwn = v.contentRect(RectF())
                val mine = hasOwn && rr.left - pad <= e.rawX && e.rawX <= rr.right + pad && rr.top - pad <= e.rawY && e.rawY <= rr.bottom + pad
                if (!mine) {
                    var best: Pair<HudCanvasView, WindowManager.LayoutParams>? = null
                    var bestArea = Float.MAX_VALUE
                    huds.forEach { (ot, ov) ->
                        if (ov === v || ov.visibility != View.VISIBLE) return@forEach
                        val olp = hudLps[ot] ?: return@forEach
                        if (!ov.contentRect(RectF())) return@forEach
                        contentOnScreen(ov, olp, rr)
                        if (rr.left - pad <= e.rawX && e.rawX <= rr.right + pad && rr.top - pad <= e.rawY && e.rawY <= rr.bottom + pad) {
                            val area = rr.width() * rr.height()
                            if (area < bestArea) { bestArea = area; best = ov to olp }
                        }
                    }
                    if (best != null) fwd = best else fwdNone = true
                }
            }
            val target = fwd
            when {
                target != null -> {
                    val ev = MotionEvent.obtain(e)
                    ev.setLocation(e.rawX - target.second.x, e.rawY - target.second.y)
                    target.first.dispatchTouchEvent(ev)
                    ev.recycle()
                    if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) fwd = null
                    true
                }
                fwdNone -> {
                    if (e.actionMasked == MotionEvent.ACTION_UP) { collapsePanel(true); fwdNone = false }
                    else if (e.actionMasked == MotionEvent.ACTION_CANCEL) fwdNone = false
                    true
                }
                else -> inner.onTouch(view, e)
            }
        }
    }

    private fun enterHudEdit() {
        if (hudEditing || huds.isEmpty()) return
        closeGui(true)
        hudEditing = true
        addCatcher()
        // 重新挂一遍 HUD 窗口，保证它们在“空白处点击”拦截层的上面
        huds.forEach { (t, v) ->
            val lp = hudLps[t] ?: return@forEach
            runCatching { wm.removeViewImmediate(v) }
            runCatching { wm.addView(v, lp) }
        }
        addGuide()
        huds.forEach { (t, v) -> setHudEditable(t, v, true); bindHud(t, v) }
        addEditBar()
    }

    private fun exitHudEdit() {
        if (!hudEditing) return
        collapsePanel(false)
        catcher?.let { runCatching { wm.removeView(it) } }
        catcher = null
        hudEditing = false
        huds.forEach { (t, v) -> setHudEditable(t, v, false); bindHud(t, v) }
        editBar?.let { runCatching { wm.removeView(it) } }
        editBar = null
        guide?.let { runCatching { wm.removeView(it) } }
        guide = null
    }

    /** 编辑模式下铺满屏幕的透明层：点空白处收起设置面板；同时避免点击穿到游戏里 */
    private fun addCatcher() {
        val dm = resources.displayMetrics
        val v = View(ctx).apply { setOnClickListener { collapsePanel(true) } }
        val lp = overlayLp(dm.widthPixels, dm.heightPixels)
        runCatching { wm.addView(v, lp); catcher = v }
    }

    /** 选中一个 HUD：灵动岛位置弹性展开成它的设置面板；已展开时就地切换内容 */
    private fun selectHud(t: HudType) {
        if (!hudEditing) return
        applyUnit()
        val old = panel
        if (old != null) {
            if (old.closing) return
            huds.forEach { (ht, hv) -> hv.selected = ht == t }
            old.bind(t, true)
            return
        }
        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val anchor = RectF()
        val iv = huds[HudType.ISLAND]
        val ilp = hudLps[HudType.ISLAND]
        if (iv != null && ilp != null) contentOnScreen(iv, ilp, anchor)
        else { val w = dp(210).toFloat(); anchor.set((sw - w) / 2f, dp(12).toFloat(), (sw + w) / 2f, dp(52).toFloat()) }

        val p = IslandPanelView(ctx, repo) { collapsePanel(true) }
        val maxH = p.measureMax((sh * 0.9f).toInt())
        val pw = p.fullW + 2 * p.margin
        val ph = maxH + 2 * p.margin
        val wx = (anchor.centerX() - pw / 2f).toInt().coerceIn(0, (sw - pw).coerceAtLeast(0))
        val wy = (anchor.top - p.margin).toInt().coerceIn(0, (sh - ph).coerceAtLeast(0))
        val lp = overlayLp(pw, ph).apply { x = wx; y = wy }
        p.bind(t, false)
        if (runCatching { wm.addView(p, lp) }.isFailure) return
        panel = p; panelLp = lp
        huds.forEach { (ht, hv) -> hv.selected = ht == t }
        iv?.visibility = View.INVISIBLE
        anchor.offset(-wx.toFloat(), -wy.toFloat())
        p.expand(anchor, anchor.height() / 2.2f)
    }

    private fun collapsePanel(animate: Boolean) {
        val p = panel ?: return
        if (p.closing) return
        huds.values.forEach { it.selected = false }
        val done = {
            runCatching { wm.removeView(p) }
            if (panel === p) { panel = null; panelLp = null }
            huds[HudType.ISLAND]?.visibility = View.VISIBLE
        }
        if (animate) p.collapse(done) else done()
    }

    private fun addGuide() {
        val dm = resources.displayMetrics
        val g = HudGuideView(ctx)
        val lp = overlayLp(dm.widthPixels, dm.heightPixels).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.addView(g, lp); guide = g }
    }

    /** 编辑模式的底部提示条：提示 + “完成” */
    private fun addEditBar() {
        val d = resources.displayMetrics.density
        val bar = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rr(withAlpha(Palette.inkBlack, 0.80f), 999f * d, withAlpha(Palette.ink, 0.22f), dp(1))
            setPadding(dp(12), dp(3), dp(3), dp(3))
            addView(TextView(context).apply {
                text = tr("拖动调整位置"); textSize = 11f; setTextColor(Palette.ink2)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-2, -2))
            addView(TextView(context).apply {
                text = tr("完成"); textSize = 11.5f; setTextColor(Palette.onAccent)
                typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                maxLines = 1
                background = rr(Palette.accent, 999f * d)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                minWidth = dp(48)
                setOnClickListener { exitHudEdit() }
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        }
        val lp = overlayLp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(10)
        }
        runCatching { wm.addView(bar, lp); editBar = bar }
    }

    /** 旋转屏幕后把 HUD 拉回屏幕内 */
    private fun clampHuds() {
        if (!::wm.isInitialized) return
        val dm = resources.displayMetrics
        hudLps.forEach { (t, lp) ->
            val v = huds[t] ?: return@forEach
            lp.x = lp.x.coerceIn(0, (dm.widthPixels - dp(48)).coerceAtLeast(0))
            lp.y = lp.y.coerceIn(0, (dm.heightPixels - dp(48)).coerceAtLeast(0))
            runCatching { wm.updateViewLayout(v, lp) }
        }
    }

    private fun openGui(animate: Boolean, cat: Category? = null, moduleId: String? = null, account: Boolean = false) {
        if (guiRoot != null) return
        repo.applyAppearance()
        ball?.refreshTheme()

        val root = GuiRoot(ctx)
        root.onBack = { guiView?.handleBack() }
        root.setOnClickListener { closeGui(true) } // 点面板外关闭
        val view = makePanel(root, cat, moduleId, account)

        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION, flags, PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.22f
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        @Suppress("DEPRECATION")
        root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        wm.addView(root, lp)
        // 这个窗口会拿走焦点，系统会把游戏的沉浸模式撤掉、下拉栏冒出来；这里重新隐藏系统栏
        root.post {
            root.windowInsetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        guiRoot = root
        guiOpenedAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "gui window + panel ${Ui.panelW}x${Ui.panelH} wizard=${!accounts.setupDone}")

        if (animate) animatePanel(view, true, null)
    }

    /** 按当前设置（缩放 / 模糊 / 主题）构建面板并放进 root；首次打开和原位重建共用 */
    private fun makePanel(root: GuiRoot, cat: Category?, moduleId: String?, account: Boolean): ClickGuiView {
        val blurOk = repo.blurEnabled()
        // 同步构建面板（稳定路径）：一次成型后 wm.addView，避免窗口首帧后
        // 在 layout 过程中追加内容导致的部分机型帧调度卡死（黑屏只剩 dim）
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()
        val aspect = if (sh > sw) 0.78f else 1120f / 680f // 竖屏更高的比例
        var w = min(sw * 0.94f, sh * 0.92f * aspect) * repo.uiScale()
        w = min(w, sw * 0.98f)
        var h = w / aspect
        if (h > sh * 0.98f) { h = sh * 0.98f; w = h * aspect }
        Ui.unit = w / Ui.DESIGN_W
        // 首次启动向导：控件尺寸不变，只把面板缩成一张紧凑卡片（不再铺满整块 GUI 面板）。
        // 向导结束后 rebuildGui 会按正常尺寸重建。
        if (!accounts.setupDone) {
            w = min(w, Ui.f(WIZARD_W))
            h = min(h, Ui.f(WIZARD_H))
        }
        Ui.panelW = w.toInt()
        Ui.panelH = h.toInt()

        val view = ClickGuiView(
            ctx, repo, accounts, blurOk,
            onClose = { closeGui(true) },
            onAppearance = { root.post { rebuildGui() } },
            onSetupDone = { root.post { rebuildGui(true) } },
            onEditHud = { root.post { enterHudEdit() } },
            onEasterEgg = { root.post { startBounce() } },
            startCat = cat ?: Category.COMBAT,
            restoreModuleId = moduleId,
            startAccount = account
        )
        root.addView(view, FrameLayout.LayoutParams(w.toInt(), h.toInt(), Gravity.CENTER))
        guiView = view
        return view

    }

    /**
     * 打开 / 关闭 GUI 的动画：像一层磨砂玻璃盖板被翻开 —— 清晰的“前沿”从左扫到右，前沿左边已经清楚，右边还糊着；
     * 关闭时前沿从左扫到右，把画面重新糊上并淡出。再带一点从下往上的位移。
     * Android 13+ 用 AGSL 做“随 x 变化的模糊半径”；Android 12 退化成整体模糊渐变；更旧的系统只有位移 + 淡入。
     */
    private fun animatePanel(v: View, opening: Boolean, end: (() -> Unit)?) {
        val h = Ui.panelH.toFloat().coerceAtLeast(1f)
        val w = Ui.panelW.toFloat().coerceAtLeast(1f)
        val maxBlur = dp(24).toFloat()
        val feather = h * 0.6f
        v.pivotX = w / 2f; v.pivotY = h
        v.cameraDistance = 9000f * resources.displayMetrics.density
        val sdk = android.os.Build.VERSION.SDK_INT
        val shader: android.graphics.RuntimeShader? = if (sdk >= 33) runCatching { android.graphics.RuntimeShader(SWEEP_AGSL) }.getOrNull() else null
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = if (opening) 620L else 380L
        a.interpolator = if (opening) android.view.animation.PathInterpolator(0.22f, 0.9f, 0.3f, 1f) else android.view.animation.AccelerateDecelerateInterpolator()
        // t: 0..1 动画进度；打开时 u = t，关闭时 u = 1 - t（完全倒放）。u 越大越清楚：下方先清楚，上方后清楚
        fun apply(t: Float) {
            val u = if (opening) t else 1f - t
            v.translationY = (1f - u) * h * 0.12f
            // 3D 倾斜：以底边为铰链，上边从往后倒的斜面翻到平面（方向反了就把 TILT_X 改成负数）
            v.rotationX = (1f - u) * TILT_X
            v.rotationY = (1f - u) * TILT_Y
            v.alpha = (u * 1.8f).coerceIn(0f, 1f)
            if (sdk >= 33 && shader != null) {
                val front = -feather + u * (h + feather) // 前沿离底边的距离：以下清楚
                shader.setFloatUniform("size", w, h)
                shader.setFloatUniform("front", front)
                shader.setFloatUniform("feather", feather)
                shader.setFloatUniform("maxR", maxBlur)
                v.setRenderEffect(if (u >= 0.999f) null else android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
            } else if (sdk >= 31) {
                val r = (1f - u) * maxBlur
                v.setRenderEffect(if (r < 0.5f) null else android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP))
            }
        }
        apply(0f)
        a.addUpdateListener { apply(it.animatedValue as Float) }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(an: android.animation.Animator) {
                if (opening) { v.translationY = 0f; v.alpha = 1f; v.rotationX = 0f; v.rotationY = 0f; if (sdk >= 31) v.setRenderEffect(null) }
                end?.invoke()
            }
        })
        v.post { a.start() }
    }

    // ---------------- 彩蛋：邦友の酒（长按音乐入口）----------------
    private var bounce: BounceShow? = null

    /** 关掉 GUI，放出 DVD 式弹跳的视频，所有控件跟着鼓点 Q 弹；长按视频结束 */
    private fun startBounce() {
        if (bounce?.active == true) return
        if (guiRoot != null) closeGui(true)
        val b = BounceShow(act, ctx, wm,
            targets = { huds.values.toList<View>() + shortcuts.values.toList<View>() + listOfNotNull<View>(ball, guiView) },
            onStop = { bounce = null })
        bounce = b
        b.start()
    }

    private fun closeGui(animate: Boolean) {
        val root = guiRoot ?: return
        val view = guiView
        guiRoot = null
        guiView = null
        if (::accounts.isInitialized) accounts.addUsage((SystemClock.elapsedRealtime() - guiOpenedAt) / 1000)
        if (animate && view != null) {
            animatePanel(view, false) { runCatching { wm.removeView(root) } }
        } else {
            runCatching { wm.removeView(root) }
        }
        // 主题色可能刚改过：让 HUD 跟上
        if (::repo.isInitialized && ::wm.isInitialized) { repo.applyAppearance(); syncHuds() }
    }

    /** “界面模糊 / 缩放 / 主题色”改变后，原位重建 GUI 并恢复当前分类与设置卡片 */
    private fun rebuildGui(animate: Boolean = false) {
        val root = guiRoot
        if (root == null) return
        val old = guiView
        val cat = old?.currentCat
        val id = old?.openedId
        val acc = old?.accountMode ?: false
        // 原位替换：不拆窗口，避免窗口重建带来的闪烁 / 空白一段时间
        repo.applyAppearance()
        ball?.refreshTheme()
        root.removeAllViews()
        guiView = null
        val v = makePanel(root, cat, id, acc)
        if (animate) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(180).start()
        }
        syncHuds()
    }
}

/** 打开动画的 3D 倾斜（以底边为铰链翻开） */
private const val TILT_Y = 0f
private const val TILT_X = 32f

/** 随离底边距离变化的圆盘模糊：前沿以下清晰，以上逐渐变糊 */
private const val SWEEP_AGSL = """
uniform shader content;
uniform float2 size;
uniform float front;
uniform float feather;
uniform float maxR;
half4 main(float2 p) {
    float d = size.y - p.y;               // 离底边的距离
    float k = clamp((d - front) / feather, 0.0, 1.0);
    float r = k * maxR;
    if (r < 0.5) return content.eval(p);
    half4 acc = half4(0.0);
    for (int i = 0; i < 28; i++) {
        float fi = float(i) + 0.5;
        float rad = sqrt(fi / 28.0) * r;
        float ang = fi * 2.39996;
        acc += content.eval(p + float2(cos(ang), sin(ang)) * rad);
    }
    return acc / 28.0;
}
"""
