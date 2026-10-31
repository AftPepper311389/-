package com.nemoclient.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nemoclient.app.R
import com.nemoclient.app.i18n.tf
import com.nemoclient.app.i18n.tr
import com.nemoclient.app.data.AccountStore
import com.nemoclient.app.data.Category
import com.nemoclient.app.data.HudOpt
import com.nemoclient.app.data.HudType
import com.nemoclient.app.data.Module
import com.nemoclient.app.data.ModuleRepo
import com.nemoclient.app.data.SampleData
import com.nemoclient.app.data.Setting

/**
 * 主面板：左侧分类栏 + 右侧 2 列模块卡片 + 底部状态条；
 * 点击卡片右侧箭头弹出居中的设置卡片（弹出时整个面板模糊）。
 */
class ClickGuiView(
    ctx: Context,
    private val repo: ModuleRepo,
    private val accounts: AccountStore,
    blurBehind: Boolean,
    private val onClose: () -> Unit,
    private val onAppearance: () -> Unit,
    private val onSetupDone: () -> Unit,
    private val onEditHud: () -> Unit,
    private val onEasterEgg: () -> Unit = {},
    startCat: Category = Category.COMBAT,
    restoreModuleId: String? = null,
    startAccount: Boolean = false
) : FrameLayout(ctx), SheetHost {

    var currentCat = startCat
        private set
    var openedId: String? = null
        private set

    private var query = ""
    private val content = FrameLayout(ctx)
    private val catBox = FrameLayout(ctx)
    private val catRows = mutableListOf<CatRow>()
    private val highlight = View(ctx)
    private val argb = ArgbEvaluator()
    private var catAnim: ValueAnimator? = null
    private var suppressSearch = false
    var accountMode = false
    var musicMode = false
        private set
    private var sheetOpen = false
    private var animating = false
    private var sweepT = 0f
    private var sweepAnim: ValueAnimator? = null
    private val railBg = View(ctx)
    private val divider = View(ctx)
    private val toastView = TextView(ctx)
    private val userAvatar = TextView(ctx)
    private val userName = TextView(ctx)
    private val userSub = TextView(ctx)
    private lateinit var userCard: LinearLayout
    private lateinit var catGroup: View
    private lateinit var mainContent: View
    private lateinit var pageHost: FrameLayout
    // 账号页 / 音乐页第一次用到时才创建：打开 GUI 时少建两整页视图树，首帧更快
    private val accountPage: AccountPageView by lazy {
        AccountPageView(context, accounts, this).also { it.visibility = GONE; pageHost.addView(it, 0, FrameLayout.LayoutParams(-1, -1)) }
    }
    private val musicPage: MusicPageView by lazy {
        MusicPageView(context, this).also { it.visibility = GONE; pageHost.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }
    private var wizard: SetupWizardView? = null
    private val rowH = Ui.u(44f)
    private val rowGap = Ui.u(2f)
    private val sheetLayer = FrameLayout(ctx)
    private val title = TextView(ctx)
    private val count = TextView(ctx)
    private val statusOn = TextView(ctx)
    private val search = EditText(ctx)
    private val list = RecyclerView(ctx)
    private val adapter = CardAdapter()
    private val repoListener: () -> Unit = {
        refreshStatus()
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    private val accountListener: () -> Unit = { refreshUser() }

    init {
        isClickable = true // 吃掉面板上的点击，避免穿透到外层“点空白处关闭”
        background = rr(withAlpha(Palette.inkBlack, if (blurBehind) 0.5f else 1f), Ui.f(24f), Palette.line, Ui.u(1f))
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, Ui.f(24f))
        }

        val railW = Ui.u(212f)
        // 左栏底色独立成一层：用 scaleX 铺开，分隔线跟着滑动
        railBg.setBackgroundColor(withAlpha(Palette.inkBlack, 0.35f))
        railBg.pivotX = 0f
        railBg.pivotY = 0f
        content.addView(railBg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        mainContent = buildMain(ctx)
        pageHost = FrameLayout(ctx).apply { visibility = GONE }
        val mainWrap = FrameLayout(ctx).apply {
            addView(mainContent, FrameLayout.LayoutParams(-1, -1))
            addView(pageHost, FrameLayout.LayoutParams(-1, -1))
        }
        content.addView(mainWrap, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = railW + Ui.u(1f)
        })
        content.addView(buildRail(ctx), LayoutParams(railW, LayoutParams.MATCH_PARENT))
        divider.setBackgroundColor(Palette.line)
        content.addView(divider, LayoutParams(Ui.u(1f), LayoutParams.MATCH_PARENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        sheetLayer.visibility = GONE
        addView(sheetLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        toastView.apply {
            px(12.5f); setTextColor(Palette.ink); gravity = Gravity.CENTER; visibility = GONE
            setPadding(Ui.u(18f), Ui.u(10f), Ui.u(18f), Ui.u(10f))
            background = rr(Palette.toastBg, Ui.f(999f), Palette.line, Ui.u(1f))
        }
        addView(toastView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = Ui.u(56f) })

        buildCats()
        refreshList()
        refreshStatus()
        restoreModuleId?.let { id -> repo.modules.firstOrNull { it.id == id }?.let { openSheet(it, false) } }
        applySweep(0f)
        refreshUser()
        alpha = repo.uiAlpha()
        if (startAccount) enterAccount(false)

        // 首次启动：全屏向导盖在最上层（欢迎 → 登录 → 语言 → 进入）
        if (!accounts.setupDone) {
            val w = SetupWizardView(ctx, accounts, repo) { onSetupDone() }
            wizard = w
            addView(w, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); repo.addListener(repoListener); accounts.addListener(accountListener) }
    override fun onDetachedFromWindow() { catAnim?.cancel(); sweepAnim?.cancel(); accounts.removeListener(accountListener); repo.removeListener(repoListener); super.onDetachedFromWindow() }

    /** 返回键：先关设置卡片，再关面板 */
    fun handleBack() { if (wizard?.handleBack() == true) return; if (sheetOpen) closeSheet() else if (accountMode) exitAccount(true) else onClose() }

    /** 动画期间吞掉触摸，避免中途误触 */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = animating || super.onInterceptTouchEvent(ev)

    // ===================== 左栏 =====================
    private fun buildRail(ctx: Context): View {
        val rail = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        // 品牌（R logo）
        val mark = ImageView(ctx).apply { setImageResource(R.drawable.ic_logo_r) }
        val brandText = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply { text = "rlin client"; px(15f); bold(); setTextColor(Palette.ink) })
            addView(TextView(ctx).apply { text = "v0.1 · Dark Glass"; px(11f); setTextColor(Palette.ink2) })
        }
        val brand = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.u(20f), Ui.u(18f), Ui.u(20f), Ui.u(14f))
            addView(mark, LinearLayout.LayoutParams(Ui.u(34f), Ui.u(34f)))
            addView(brandText, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(10f) })
        }
        rail.addView(brand)

        // 搜索
        search.apply {
            hint = tr("搜索模块"); px(13f)
            setTextColor(Palette.ink); setHintTextColor(Palette.ink2)
            background = null; isSingleLine = true; setPadding(0, 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (suppressSearch) return; query = s.toString().lowercase(); if (accountMode) { if (!musicMode) accountPage.setFilter(query) } else refreshList() }
            })
        }
        val searchBar = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rr(withAlpha(Palette.sage, 0.35f), Ui.f(999f), Palette.line, Ui.u(1f))
            setPadding(Ui.u(14f), Ui.u(10f), Ui.u(14f), Ui.u(10f))
            addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_search); setColorFilter(Palette.ink2) },
                LinearLayout.LayoutParams(Ui.u(15f), Ui.u(15f)))
            addView(search, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.u(8f) })
        }
        rail.addView(searchBar, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(Ui.u(14f), Ui.u(2f), Ui.u(14f), Ui.u(10f))
        })

        // 分类分组
        val group = groupCard(ctx).apply {
            addView(TextView(ctx).apply {
                text = tr("模块分类"); px(10.5f); bold(); setTextColor(Palette.ink2); letterSpacing = 0.08f
                setPadding(Ui.u(14f), Ui.u(8f), Ui.u(14f), Ui.u(4f))
            })
            addView(catBox)
        }
        // HUD 分组：添加 HUD / 编辑 HUD（与分类卡片一起包在容器里，账号页进出时同步收起 / 展开）
        val hudGroup = groupCard(ctx).apply {
            addView(TextView(ctx).apply {
                text = tr("HUD"); px(10.5f); bold(); setTextColor(Palette.ink2); letterSpacing = 0.08f
                setPadding(Ui.u(14f), Ui.u(8f), Ui.u(14f), Ui.u(4f))
            })
            addView(hudRow(ctx, R.drawable.ic_plus, tr("添加 HUD")) { openHudSheet() })
            addView(hudRow(ctx, R.drawable.ic_edit, tr("编辑 HUD")) {
                if (repo.anyHudOn()) onEditHud() else toast(tr("请先添加 HUD"))
            }, LinearLayout.LayoutParams(-1, Ui.u(40f)).apply { topMargin = rowGap })
            addView(hudRow(ctx, R.drawable.ic_music, tr("音乐"), onLong = { onEasterEgg() }) { enterAccount(true, true) }, LinearLayout.LayoutParams(-1, Ui.u(40f)).apply { topMargin = rowGap })
        }
        val groups = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(group, groupLp())
            addView(hudGroup, groupLp())
        }
        catGroup = groups
        rail.addView(groups, LinearLayout.LayoutParams(-1, -2))
        rail.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))

        // 用户卡片：点击进入 / 返回账号页
        userAvatar.apply { px(11f); bold(); setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = rr(Palette.brown, Ui.f(99f)) }
        userName.apply { px(13f); bold(); setTextColor(Palette.ink) }
        userSub.apply { px(10.5f); setTextColor(Palette.ink2) }
        val who = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(userName); addView(userSub)
        }
        val userRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.u(14f), Ui.u(10f), Ui.u(14f), Ui.u(10f))
            background = ripple(null, Ui.f(16f))
            setOnClickListener { toggleAccount() }
            addView(userAvatar, LinearLayout.LayoutParams(Ui.u(28f), Ui.u(28f)))
            addView(who, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(12f) })
        }
        userCard = groupCard(ctx).apply { addView(userRow) }
        rail.addView(userCard, groupLp().apply { bottomMargin = Ui.u(12f) })
        return rail
    }

    /** 左栏 HUD 分组里的一行：图标 + 文字，点按触发 onClick（无选中态） */
    private fun hudRow(ctx: Context, iconRes: Int, label: String, onLong: (() -> Unit)? = null, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.u(14f), 0, Ui.u(14f), 0)
            background = ripple(null, Ui.f(14f))
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onLong(); true }
            addView(ImageView(ctx).apply { setImageResource(iconRes); setColorFilter(Palette.ink) },
                LinearLayout.LayoutParams(Ui.u(19f), Ui.u(19f)))
            addView(TextView(ctx).apply { text = label; px(13.5f); setTextColor(Palette.ink) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.u(12f) })
        }.also { it.layoutParams = LinearLayout.LayoutParams(-1, Ui.u(40f)) }

    private fun groupCard(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rr(withAlpha(Palette.panelD, 0.4f), Ui.f(20f), Palette.line, Ui.u(1f))
        setPadding(Ui.u(6f), Ui.u(6f), Ui.u(6f), Ui.u(6f))
    }

    private fun groupLp() = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(Ui.u(12f), Ui.u(6f), Ui.u(12f), Ui.u(6f))
    }

    /** 分类行：level 0=未选中，1=选中；颜色随 level 平滑过渡 */
    private inner class CatRow(val cat: Category, val icon: ImageView, val label: TextView, val num: TextView) {
        var level = 0f
            set(l) {
                field = l
                val c = argb.evaluate(l, Palette.ink, Color.WHITE) as Int
                icon.setColorFilter(c)
                label.setTextColor(c)
                num.setTextColor(argb.evaluate(l, Palette.ink2, withAlpha(Color.WHITE, 0.75f)) as Int)
            }
    }

    private fun buildCats() {
        val ctx = context
        catBox.removeAllViews()
        catRows.clear()
        highlight.background = rr(Palette.accent, Ui.f(999f))
        catBox.addView(highlight, LayoutParams(LayoutParams.MATCH_PARENT, rowH))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        Category.values().forEachIndexed { i, cat ->
            val icon = ImageView(ctx).apply { setImageResource(cat.iconRes) }
            val label = TextView(ctx).apply { text = tr(cat.label); px(13.5f) }
            val num = TextView(ctx).apply { text = repo.modules.count { it.category == cat }.toString(); px(11f) }
            val row = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.u(14f), 0, Ui.u(14f), 0)
                setOnClickListener { selectCat(cat) }
                addView(icon, LinearLayout.LayoutParams(Ui.u(19f), Ui.u(19f)))
                addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.u(12f) })
                addView(num)
            }
            col.addView(row, LinearLayout.LayoutParams(-1, rowH).apply { topMargin = if (i == 0) 0 else rowGap })
            catRows.add(CatRow(cat, icon, label, num).also { it.level = if (cat == currentCat) 1f else 0f })
        }
        catBox.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        highlight.translationY = (Category.values().indexOf(currentCat) * (rowH + rowGap)).toFloat()
    }

    private fun selectCat(cat: Category) {
        if (cat == currentCat) return
        closeSheet(false)
        currentCat = cat
        suppressSearch = true; search.setText(""); suppressSearch = false; query = ""
        animateCats()
        swapList()
    }

    /** 高亮胶囊平滑滑到新行，同时图标/文字颜色渐变 */
    private fun animateCats() {
        catAnim?.cancel()
        val startY = highlight.translationY
        val endY = (Category.values().indexOf(currentCat) * (rowH + rowGap)).toFloat()
        val l0 = catRows.map { it.level }
        val goal = catRows.map { if (it.cat == currentCat) 1f else 0f }
        val changed = catRows.indices.filter { l0[it] != goal[it] }
        catAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                highlight.translationY = startY + (endY - startY) * t
                // 只更新状态真正变化的行（通常只有 2 行），其余 8+ 行每帧不再重设 ColorFilter / 文字颜色
                for (i in changed) catRows[i].level = l0[i] + (goal[i] - l0[i]) * t
            }
            start()
        }
    }

    /** 右侧内容：淡出 → 换数据 → 轻微上浮淡入 */
    private fun swapList() {
        listOf(list, title, count).forEach { it.animate().cancel() }
        list.setLayerType(View.LAYER_TYPE_HARDWARE, null)   // 淡入淡出期间整块缓存成纹理，只改 alpha / 位移
        title.animate().alpha(0f).setDuration(110).start()
        count.animate().alpha(0f).setDuration(110).start()
        list.animate().alpha(0f).translationY(-Ui.f(6f)).setDuration(110).withEndAction {
            refreshList()
            list.scrollToPosition(0)
            list.translationY = Ui.f(14f)
            val ip = DecelerateInterpolator(1.5f)
            title.animate().alpha(1f).setDuration(220).setInterpolator(ip).start()
            count.animate().alpha(1f).setDuration(220).setInterpolator(ip).start()
            list.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(ip).withEndAction { list.setLayerType(View.LAYER_TYPE_NONE, null) }.start()
        }.start()
    }

    // ===================== 右侧 =====================
    private fun buildMain(ctx: Context): View {
        val main = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        title.apply { px(22f); bold(); setTextColor(Palette.ink) }
        count.apply { px(12f); setTextColor(Palette.ink2) }
        main.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.u(26f), Ui.u(18f), Ui.u(26f), Ui.u(14f))
            addView(title)
            addView(count, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(14f) })
        })
        main.addView(hline(ctx))

        list.apply {
            layoutManager = GridLayoutManager(ctx, 2)
            adapter = this@ClickGuiView.adapter
            itemAnimator = null
            setHasFixedSize(true)
            setItemViewCacheSize(8)
            recycledViewPool.setMaxRecycledViews(0, 14)
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(Ui.u(21f), Ui.u(13f), Ui.u(21f), Ui.u(13f))
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(out: Rect, v: View, p: RecyclerView, s: RecyclerView.State) {
                    val g = Ui.u(5f); out.set(g, g, g, g)
                }
            })
        }
        main.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        main.addView(hline(ctx))

        statusOn.apply { px(11.5f); setTextColor(Palette.ink2) }
        main.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.u(26f), Ui.u(10f), Ui.u(26f), Ui.u(10f))
            addView(View(ctx).apply { background = rr(Palette.accent, Ui.f(99f)) }, LinearLayout.LayoutParams(Ui.u(7f), Ui.u(7f)))
            addView(statusOn, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.u(8f) })
            addView(TextView(ctx).apply { text = "rlin client 0.6"; px(11.5f); setTextColor(Palette.ink2) })
        })
        return main
    }

    private fun hline(ctx: Context) = View(ctx).apply {
        setBackgroundColor(Palette.line)
        layoutParams = LinearLayout.LayoutParams(-1, Ui.u(1f))
    }

    private fun vline(ctx: Context) = View(ctx).apply { setBackgroundColor(Palette.line) }

    private fun refreshList() {
        val l = repo.modules.filter {
            it.category == currentCat && (query.isEmpty() || it.name.lowercase().contains(query) || tr(it.name).lowercase().contains(query) || it.desc.contains(query) || tr(it.desc).lowercase().contains(query))
        }
        adapter.submit(l)
        title.text = tr(currentCat.label)
        count.text = tf("%d 个模块", repo.modules.count { it.category == currentCat })
    }

    private fun refreshStatus() { statusOn.text = tf("已启用 %d 个模块", repo.enabledCount()) }

    private inner class CardAdapter : RecyclerView.Adapter<CardAdapter.VH>() {
        private var items: List<Module> = emptyList()
        inner class VH(val card: ModuleCardView) : RecyclerView.ViewHolder(card)

        fun submit(l: List<Module>) { items = l; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(ModuleCardView(p.context).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        })
        override fun onBindViewHolder(h: VH, i: Int) {
            val m = items[i]
            h.card.bind(m, repo.isOn(m), { repo.setOn(m, it) }, { openSheet(m, true) })
        }
    }

    // ===================== 账号页：分隔线滑动铺开 =====================
    private fun refreshUser() {
        val p = accounts.profile
        userAvatar.text = p.nickname.take(1).uppercase()
        userName.text = p.nickname
        userSub.text = if (accountMode) tr("点击返回模块") else tr(p.tier.label)
    }

    private fun setUserActive(active: Boolean) {
        userCard.background = rr(
            withAlpha(Palette.panelD, 0.4f), Ui.f(20f),
            if (active) Palette.accent else Palette.line, if (active) Ui.u(1.5f) else Ui.u(1f)
        )
        refreshUser()
    }

    private fun toggleAccount() { if (accountMode) exitAccount(true) else enterAccount(true) }

    private fun catRowViews(): List<View> {
        val col = catBox.getChildAt(1) as LinearLayout
        return (0 until col.childCount).map { col.getChildAt(it) }
    }

    /** t=0：左栏宽 212u、显示模块；t=1：分隔线滑到最右，左栏底色铺满整个面板，显示账号页 */
    private fun applySweep(t: Float) {
        sweepT = t
        val w = Ui.panelW.toFloat()
        val h = Ui.panelH
        val x0 = Ui.u(212f).toFloat()
        val lineX = x0 + (w - x0) * t
        railBg.scaleX = lineX / w
        divider.translationX = lineX
        val mainLeft = Ui.u(213f).toFloat()
        val mainW = (w - mainLeft).toInt()
        val vis = (lineX - mainLeft).coerceIn(0f, mainW.toFloat()).toInt()
        // 模块区：被分隔线一路“擦掉”；账号页：在分隔线扫过的地方显露
        mainContent.clipBounds = if (t <= 0f) null else Rect(vis, 0, mainW, h)
        pageHost.clipBounds = if (t >= 1f) null else Rect(0, 0, vis, h)
        pageHost.translationX = -Ui.f(36f) * (1f - t)
    }

    private fun sweepTo(target: Float, animate: Boolean, onEnd: () -> Unit) {
        sweepAnim?.cancel()
        if (!animate) { applySweep(target); onEnd(); return }
        animating = true
        val from = sweepT
        var cancelled = false
        sweepAnim = ValueAnimator.ofFloat(from, target).apply {
            duration = 540
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { applySweep(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { animating = false; if (!cancelled) onEnd() }
            })
            start()
        }
    }

    private fun resetSearch(hint: String) {
        suppressSearch = true; search.setText(""); suppressSearch = false
        query = ""; search.hint = hint
    }

    private fun enterAccount(animate: Boolean, music: Boolean = false) {
        if (accountMode) return
        closeSheet(false)
        accountMode = true
        musicMode = music
        resetSearch(tr(if (music) "音乐页内搜索歌曲" else "搜索游戏账号"))
        if (!music) {
            accountPage.reset()
            accountPage.refreshProfile()
            accountPage.refreshGames()
        }
        accountPage.visibility = if (music) GONE else VISIBLE
        musicPage.visibility = if (music) VISIBLE else GONE
        pageHost.visibility = VISIBLE
        mainContent.visibility = VISIBLE
        setUserActive(!music)

        val rows = catRowViews()
        if (animate) {
            // 分类依次向右退场
            rows.forEachIndexed { i, v ->
                v.animate().translationX(Ui.f(28f)).alpha(0f).setStartDelay(i * 35L).setDuration(200)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            highlight.animate().alpha(0f).setStartDelay(0).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        } else {
            rows.forEach { it.alpha = 0f; it.translationX = Ui.f(28f) }
            highlight.alpha = 0f
        }
        sweepTo(1f, animate) {
            mainContent.visibility = INVISIBLE
            if (animate) collapseCatGroup() else { catGroup.visibility = INVISIBLE }
        }
    }


    /** 账号页进入：分类卡片高度向上收缩（顶边不动、底边上移），替代瞬间消失 */
    private var catGroupH = 0

    private fun collapseCatGroup() {
        if (catGroupH == 0) catGroupH = catGroup.measuredHeight
        val h0 = if (catGroup.layoutParams.height > 0) catGroup.layoutParams.height else catGroup.measuredHeight
        sweepAnim?.cancel()
        ValueAnimator.ofInt(h0, 0).apply {
            duration = 230
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                catGroup.layoutParams.height = it.animatedValue as Int
                catGroup.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    catGroup.layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
                    catGroup.visibility = INVISIBLE
                    catGroup.requestLayout()
                }
            })
            start()
        }
    }

    /** 账号页退出：分类卡片高度向下展开（与内容入场同步） */
    private fun expandCatGroup() {
        if (catGroupH == 0) catGroupH = catGroup.measuredHeight
        catGroup.visibility = VISIBLE
        catGroup.layoutParams.height = 1
        ValueAnimator.ofInt(1, catGroupH).apply {
            duration = 240
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                catGroup.layoutParams.height = it.animatedValue as Int
                catGroup.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    catGroup.layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
                    catGroup.requestLayout()
                }
            })
            start()
        }
    }
    private fun exitAccount(animate: Boolean) {
        if (!accountMode) return
        closeSheet(false)
        accountMode = false
        musicMode = false
        resetSearch(tr("搜索模块"))
        setUserActive(false)
        refreshList()
        mainContent.visibility = VISIBLE
        if (animate) expandCatGroup() else catGroup.visibility = VISIBLE

        val rows = catRowViews()
        if (animate) {
            rows.forEachIndexed { i, v ->
                v.alpha = 0f; v.translationX = Ui.f(28f)
                v.animate().translationX(0f).alpha(1f).setStartDelay(220L + i * 35L).setDuration(240)
                    .setInterpolator(DecelerateInterpolator(1.5f)).start()
            }
            highlight.alpha = 0f
            highlight.animate().alpha(1f).setStartDelay(260).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
        } else {
            rows.forEach { it.alpha = 1f; it.translationX = 0f }
            highlight.alpha = 1f
        }
        sweepTo(0f, animate) {
            pageHost.visibility = GONE
            pageHost.clipBounds = null
            mainContent.clipBounds = null
        }
    }

    // ===================== SheetHost：账号页用的弹出卡片 / 轻提示 =====================
    override fun showSheet(
        title: String, subtitle: String?, body: View?, primary: String,
        danger: Boolean, secondary: String?, onPrimary: () -> Boolean
    ) {
        val ctx = context
        sheetOpen = true
        openedId = null
        sheetLayer.animate().cancel()
        sheetLayer.alpha = 1f
        sheetLayer.removeAllViews()

        val scrim = View(ctx).apply {
            setBackgroundColor(Palette.scrim)
            setOnClickListener { closeSheet() }
        }
        sheetLayer.addView(scrim, LayoutParams(-1, -1))

        val closeBtn = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(Palette.ink2)
            val pd = Ui.u(8f); setPadding(pd, pd, pd, pd)
            setOnClickListener { closeSheet() }
        }
        val head = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.u(10f), 0, Ui.u(6f))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply { text = title; px(16f); bold(); setTextColor(Palette.ink) })
                if (subtitle != null) addView(TextView(ctx).apply { text = subtitle; px(11.5f); setTextColor(Palette.ink2) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(closeBtn, LinearLayout.LayoutParams(Ui.u(32f), Ui.u(32f)))
        }
        val actions = LinearLayout(ctx).apply {
            gravity = Gravity.END
            if (secondary != null) addView(pillButton(ctx, secondary, 1) { closeSheet() })
            addView(pillButton(ctx, primary, if (danger) 3 else 0) { if (onPrimary()) closeSheet() },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(10f) })
        }
        val grab = View(ctx).apply { background = rr(withAlpha(Palette.sage, 0.8f), Ui.f(99f)) }
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = rr(Palette.sheetBg, Ui.f(24f), Palette.line, Ui.u(1f))
            setPadding(Ui.u(22f), 0, Ui.u(22f), Ui.u(20f))
            addView(grab, LinearLayout.LayoutParams(Ui.u(38f), Ui.u(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.u(10f)
            })
            addView(head)
            if (body != null) addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.u(8f) })
            addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.u(18f) })
        }
        sheetLayer.addView(sheet, LayoutParams(minOf(Ui.u(440f), (Ui.panelW * 0.92f).toInt()), -2, Gravity.CENTER))
        sheetLayer.visibility = VISIBLE
        content.setRenderEffect(RenderEffect.createBlurEffect(Ui.f(14f), Ui.f(14f), Shader.TileMode.CLAMP))

        scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(240).start()
        sheet.alpha = 0f; sheet.scaleX = 0.94f; sheet.scaleY = 0.94f
        sheet.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
            .setInterpolator(DecelerateInterpolator()).start()

        findEdit(body)?.let { et ->
            et.postDelayed({
                et.requestFocus()
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }, 300)
        }
    }

    private fun findEdit(v: View?): EditText? {
        if (v is EditText) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findEdit(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun hideIme() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
    }

    override fun toast(msg: String) {
        toastView.animate().cancel()
        toastView.text = msg
        toastView.visibility = VISIBLE
        toastView.alpha = 0f
        toastView.translationY = Ui.f(10f)
        toastView.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(180).withEndAction {
            toastView.animate().alpha(0f).setStartDelay(1600).setDuration(240)
                .withEndAction { toastView.visibility = GONE }.start()
        }.start()
    }

    // ===================== 设置弹出卡片 =====================
    private fun openSheet(m: Module, animate: Boolean) {
        val ctx = context
        openedId = m.id
        sheetOpen = true
        sheetLayer.animate().cancel()
        sheetLayer.alpha = 1f
        sheetLayer.removeAllViews()

        val scrim = View(ctx).apply {
            setBackgroundColor(Palette.scrim)
            setOnClickListener { closeSheet() }
        }
        sheetLayer.addView(scrim, LayoutParams(-1, -1))

        val rows = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setColor(Palette.line); setSize(1, Ui.u(1f)) }
        }
        if (m.settings.isEmpty()) rows.addView(plainRow(ctx, tr("该模块没有额外设置"), null))
        m.settings.forEach { s ->
            when (s) {
                is Setting.Slider -> rows.addView(sliderRow(ctx, m, s))
                is Setting.Toggle -> rows.addView(switchRow(ctx, tr(s.key), s.desc?.let { tr(it) }, repo.getBool(m.id, s.key, s.def)) { v ->
                    repo.setBool(m, s.key, v); if (m.id == SampleData.GUI_ID) onAppearance()
                })
                is Setting.Choice -> rows.addView(choiceRow(ctx, m, s))
                is Setting.Color -> rows.addView(colorRow(ctx, m, s))
            }
        }
        // 悬浮快捷键：开启后屏幕上出现该模块的悬浮开关
        rows.addView(switchRow(ctx, tr("悬浮快捷键"), tr("开启后在屏幕上显示该模块的悬浮开关"), repo.hasShortcut(m)) { repo.setShortcut(m, it) })

        val closeBtn = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(Palette.ink2)
            val pd = Ui.u(8f); setPadding(pd, pd, pd, pd)
            setOnClickListener { closeSheet() }
        }
        val head = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.u(10f), 0, Ui.u(6f))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply { text = tr(m.name); px(16f); bold(); setTextColor(Palette.ink) })
                addView(TextView(ctx).apply { text = tr(m.desc); px(11.5f); setTextColor(Palette.ink2) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(closeBtn, LinearLayout.LayoutParams(Ui.u(32f), Ui.u(32f)))
        }
        val grab = View(ctx).apply { background = rr(withAlpha(Palette.sage, 0.8f), Ui.f(99f)) }

        val maxH = (Ui.panelH * 0.66f).toInt()
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = rr(Palette.sheetBg, Ui.f(24f), Palette.line, Ui.u(1f))
            setPadding(Ui.u(22f), 0, Ui.u(22f), Ui.u(22f))
            addView(grab, LinearLayout.LayoutParams(Ui.u(38f), Ui.u(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.u(10f)
            })
            addView(head)
            addView(MaxHeightScrollView(ctx, maxH).apply { isVerticalScrollBarEnabled = false; addView(rows) })
        }
        sheetLayer.addView(sheet, LayoutParams(minOf(Ui.u(470f), (Ui.panelW * 0.92f).toInt()), -2, Gravity.CENTER))

        sheetLayer.visibility = VISIBLE
        content.setRenderEffect(RenderEffect.createBlurEffect(Ui.f(14f), Ui.f(14f), Shader.TileMode.CLAMP))
        if (animate) {
            scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(240).start()
            sheet.alpha = 0f; sheet.scaleX = 0.94f; sheet.scaleY = 0.94f
            sheet.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    // ===================== 添加 HUD 弹出卡片 =====================
    private fun openHudSheet() {
        val ctx = context
        openedId = null
        sheetOpen = true
        sheetLayer.animate().cancel()
        sheetLayer.alpha = 1f
        sheetLayer.removeAllViews()

        val scrim = View(ctx).apply {
            setBackgroundColor(Palette.scrim)
            setOnClickListener { closeSheet() }
        }
        sheetLayer.addView(scrim, LayoutParams(-1, -1))

        val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val expanded = HashSet<HudType>()
        fillHudRows(rows, expanded)

        val closeBtn = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(Palette.ink2)
            val pd = Ui.u(8f); setPadding(pd, pd, pd, pd)
            setOnClickListener { closeSheet() }
        }
        val head = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.u(10f), 0, Ui.u(6f))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply { text = tr("添加 HUD"); px(16f); bold(); setTextColor(Palette.ink) })
                addView(TextView(ctx).apply { text = tr("开关控制是否显示；调整请用“编辑 HUD”，点选 HUD 即可弹出设置"); px(11.5f); setTextColor(Palette.ink2) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(closeBtn, LinearLayout.LayoutParams(Ui.u(32f), Ui.u(32f)))
        }
        val grab = View(ctx).apply { background = rr(withAlpha(Palette.sage, 0.8f), Ui.f(99f)) }
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = rr(Palette.sheetBg, Ui.f(24f), Palette.line, Ui.u(1f))
            setPadding(Ui.u(22f), 0, Ui.u(22f), Ui.u(22f))
            addView(grab, LinearLayout.LayoutParams(Ui.u(38f), Ui.u(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.u(10f)
            })
            addView(head)
            addView(MaxHeightScrollView(ctx, (Ui.panelH * 0.72f).toInt()).apply { isVerticalScrollBarEnabled = false; addView(rows) })
        }
        sheetLayer.addView(sheet, LayoutParams(minOf(Ui.u(470f), (Ui.panelW * 0.92f).toInt()), -2, Gravity.CENTER))
        sheetLayer.visibility = VISIBLE
        content.setRenderEffect(RenderEffect.createBlurEffect(Ui.f(14f), Ui.f(14f), Shader.TileMode.CLAMP))
        scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(240).start()
        sheet.alpha = 0f; sheet.scaleX = 0.94f; sheet.scaleY = 0.94f
        sheet.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
    }

    // ---- 添加 HUD：内容区 ----
    private fun sectionLabel(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; px(10.5f); bold(); setTextColor(Palette.ink2); letterSpacing = 0.08f
        setPadding(Ui.u(2f), Ui.u(12f), 0, Ui.u(6f))
    }

    private fun cardBg() = rr(withAlpha(Palette.panelD, 0.4f), Ui.f(16f), Palette.line, Ui.u(1f))

    /** 重新生成整个内容区（载入方案 / 重置之后选项值全变了，直接重建最省事） */
    private fun fillHudRows(rows: LinearLayout, expanded: MutableSet<HudType>) {
        val ctx = rows.context
        rows.removeAllViews()

        // 布局方案：3 个槽位。空槽点按 = 保存当前布局；有内容的槽点按 = 载入，长按 = 用当前布局覆盖
        rows.addView(sectionLabel(ctx, tr("布局方案")))
        val slots = LinearLayout(ctx)
        for (n in 1..3) {
            val saved = repo.hasPreset(n)
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = cardBg()
                setPadding(0, Ui.u(9f), 0, Ui.u(9f))
                addView(TextView(ctx).apply { text = tr("方案") + " $n"; px(13f); bold(); setTextColor(Palette.ink); gravity = Gravity.CENTER })
                addView(TextView(ctx).apply {
                    text = tr(if (saved) "点按载入 · 长按覆盖" else "空 · 点按保存当前")
                    px(10.5f); setTextColor(if (saved) Palette.accent else Palette.ink2); gravity = Gravity.CENTER
                })
                setOnClickListener {
                    if (saved) {
                        repo.loadPreset(n); toast(tr("已载入方案") + " $n")
                        fillHudRows(rows, expanded)
                    } else {
                        repo.savePreset(n); toast(tr("已保存为方案") + " $n")
                        fillHudRows(rows, expanded)
                    }
                }
                setOnLongClickListener {
                    repo.savePreset(n); toast(tr("已覆盖方案") + " $n")
                    fillHudRows(rows, expanded)
                    true
                }
            }
            slots.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { if (n > 1) leftMargin = Ui.u(8f) })
        }
        rows.addView(slots, LinearLayout.LayoutParams(-1, -2))

        rows.addView(sectionLabel(ctx, tr("HUD")))
        HudType.values().forEach { t -> rows.addView(hudCard(ctx, rows, t, expanded), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.u(8f) }) }
    }

    /** 一个 HUD 的卡片：名称 + 说明 + 开关。设置（大小 / 透明度 / 模糊 / 选项）在“编辑 HUD”里点选 HUD 调整 */
    private fun hudCard(ctx: Context, rows: LinearLayout, t: HudType, expanded: MutableSet<HudType>): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(Ui.u(14f), 0, Ui.u(14f), 0)
        }
        val head = rowBase(ctx, tr(t.label), tr(t.desc))
        head.addView(Md3Switch(ctx).apply {
            setChecked(repo.hudOn(t), false)
            onToggle = { on -> repo.setHudOn(t, on) }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(14f) })
        card.addView(head)
        return card
    }

    private fun percentRow(ctx: Context, label: String, min: Float, max: Float, cur: Int, onValue: (Int) -> Unit): View {
        val row = rowBase(ctx, label, null)
        val value = TextView(ctx).apply {
            px(12f); bold(); setTextColor(Palette.accent); gravity = Gravity.END
            minWidth = Ui.u(40f); text = "$cur%"
        }
        val slider = FlatSlider(ctx, min, max, 5f, cur.toFloat(), { v ->
            value.text = "${v.toInt()}%"; onValue(v.toInt())
        }, {})
        row.addView(slider, LinearLayout.LayoutParams(Ui.u(150f), Ui.u(34f)).apply { leftMargin = Ui.u(14f) })
        row.addView(value, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(8f) })
        return row
    }

    private fun closeSheet(animate: Boolean = true) {
        if (!sheetOpen) return
        sheetOpen = false
        openedId = null
        hideIme()
        content.setRenderEffect(null)
        if (!animate) { sheetLayer.visibility = GONE; sheetLayer.removeAllViews(); return }
        sheetLayer.animate().alpha(0f).setDuration(160).withEndAction {
            sheetLayer.visibility = GONE; sheetLayer.removeAllViews(); sheetLayer.alpha = 1f
        }.start()
    }

    // ---- 设置行 ----
    private fun rowBase(ctx: Context, label: String, desc: String?): LinearLayout {
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.u(12f), 0, Ui.u(12f))
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply { text = label; px(13f); setTextColor(Palette.ink); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            if (desc != null) addView(TextView(ctx).apply { text = desc; px(11f); setTextColor(Palette.ink2); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun plainRow(ctx: Context, label: String, desc: String?) =
        rowBase(ctx, label, desc).also { (it.getChildAt(0) as LinearLayout).getChildAt(0).let { t -> (t as TextView).setTextColor(Palette.ink2) } }

    private fun fmt(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun sliderRow(ctx: Context, m: Module, s: Setting.Slider): View {
        val row = rowBase(ctx, tr(s.key), null)
        val value = TextView(ctx).apply {
            px(12f); bold(); setTextColor(Palette.accent); gravity = Gravity.END
            minWidth = Ui.u(40f)
        }
        val cur = repo.getFloat(m.id, s.key, s.def)
        value.text = fmt(cur)
        val slider = FlatSlider(ctx, s.min, s.max, s.step, cur, { v ->
            value.text = fmt(v); repo.setFloat(m, s.key, v)
            // 透明度拖动时实时生效（不重建 GUI，sheet 保持打开）
            if (m.id == SampleData.GUI_ID && s.key == SampleData.KEY_ALPHA) alpha = v / 100f
        }, { if (m.id == SampleData.GUI_ID && s.key != SampleData.KEY_ALPHA) onAppearance() })
        row.addView(slider, LinearLayout.LayoutParams(Ui.u(150f), Ui.u(34f)).apply { leftMargin = Ui.u(14f) })
        row.addView(value, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(8f) })
        return row
    }

    private fun switchRow(ctx: Context, label: String, desc: String?, on: Boolean, onChange: (Boolean) -> Unit): View {
        val row = rowBase(ctx, label, desc)
        val sw = Md3Switch(ctx).apply { setChecked(on, false); onToggle = onChange }
        row.addView(sw, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(14f) })
        return row
    }

    private fun choiceRow(ctx: Context, m: Module, s: Setting.Choice): View {
        val row = rowBase(ctx, tr(s.key), null)
        val sel = repo.getInt(m.id, s.key, s.def).coerceIn(0, s.options.size - 1)
        row.addView(SegmentedView(ctx, s.options.map { tr(it) }, sel) { i ->
            repo.setInt(m, s.key, i); if (m.id == SampleData.GUI_ID) onAppearance()
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(14f) })
        return row
    }

    private fun colorRow(ctx: Context, m: Module, s: Setting.Color): View {
        val cur = repo.getInt(m.id, s.key, s.def)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val hex = TextView(ctx).apply { px(12f); bold(); setTextColor(Palette.ink2); text = "#%06X".format(cur and 0xFFFFFF) }
        val swatch = View(ctx).apply { background = rr(cur or (0xFF shl 24), Ui.f(8f), Palette.line, Ui.u(1f)) }
        val head = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.u(8f), 0, Ui.u(6f))
            addView(TextView(ctx).apply { px(13f); setTextColor(Palette.ink); text = tr(s.key) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(hex)
            addView(swatch, LinearLayout.LayoutParams(Ui.u(28f), Ui.u(20f)).apply { leftMargin = Ui.u(8f) })
        }
        box.addView(head)
        val picker = ColorPickerView(ctx, cur, { c ->
            hex.text = "#%06X".format(c and 0xFFFFFF)
            swatch.background = rr(c or (0xFF shl 24), Ui.f(8f), Palette.line, Ui.u(1f))
        }, { c ->
            repo.setInt(m, s.key, c or (0xFF shl 24))
            // 取色即切到“自定义”主题
            val themeIdx = SampleData.themeNames.size - 1
            if (repo.getInt(m.id, SampleData.KEY_THEME, 0) != themeIdx) repo.setInt(m, SampleData.KEY_THEME, themeIdx)
            if (m.id == SampleData.GUI_ID) onAppearance()
        })
        box.addView(picker, LinearLayout.LayoutParams(-1, Ui.u(150f)).apply { bottomMargin = Ui.u(10f) })
        box.addView(TextView(ctx).apply { px(11f); setTextColor(Palette.ink2); text = tr("强调色由你选，其余颜色会按它自动配色（类似 Monet）"); setPadding(0, 0, 0, Ui.u(8f)) })
        return box
    }
}
