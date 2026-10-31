package com.nemoclient.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nemoclient.app.R
import com.nemoclient.app.data.AccountStore
import com.nemoclient.app.data.ModuleRepo
import com.nemoclient.app.data.SampleData
import com.nemoclient.app.i18n.I18n
import com.nemoclient.app.i18n.Lang
import com.nemoclient.app.i18n.tr

/**
 * 首次启动向导（在 GUI 面板内全屏显示）：
 *  欢迎 → 登录（账户名 + 密码，不可跳过）→ 选择语言 → 准备完成并进入
 * 样式：磨砂玻璃胶囊输入框 + 左侧圆形图标徽章 + 浅色宽按钮；所有颜色取自当前主题色，自适应。
 */
class SetupWizardView(
    ctx: Context,
    private val accounts: AccountStore,
    private val repo: ModuleRepo,
    private val onFinished: () -> Unit
) : FrameLayout(ctx) {

    private val backdrop = SoftBackdropView(ctx)
    private val pages = FrameLayout(ctx)
    private val bar = FrameLayout(ctx)
    private val handler = Handler(Looper.getMainLooper())
    private var pageView: View? = null
    private var step = 0                    // 0 欢迎  1 登录  2 语言  3 完成
    private var transitioning = false       // 切页动画中
    private var loginBusy = false           // 登录请求中
    private var remember = true
    private var loginName = accounts.rememberedName.orEmpty()
    private var msgView: TextView? = null
    private var ctaView: TextView? = null
    private var nameField: EditText? = null
    private var passwordField: EditText? = null
    private var greetRunnable: Runnable? = null
    private var progressAnim: ValueAnimator? = null

    init {
        isClickable = true // 吃掉触摸，不让下面的 GUI 响应
        addView(backdrop, LayoutParams(-1, -1))
        addView(pages, LayoutParams(-1, -1).apply { bottomMargin = Ui.u(64f) })
        addView(bar, LayoutParams(-1, Ui.u(64f), Gravity.BOTTOM))
        showPage(0, false)
        android.util.Log.d("rlinclient", "wizard created, setupDone=${accounts.setupDone}")
        pageView?.let { // 首页轻柔入场（attach 后启动，避免时序问题）
            it.alpha = 0f
            it.translationY = Ui.f(16f)
            it.post {
                it.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(420)
                    .setInterpolator(DecelerateInterpolator(1.6f)).start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        stopGreet(); progressAnim?.cancel()
        super.onDetachedFromWindow()
    }

    /** 返回键：登录页回到欢迎页；其余页面不响应（不能绕过登录）。返回 true 表示已消费 */
    fun handleBack(): Boolean {
        if (loginBusy || transitioning) return true
        if (step == 1) { go(0); return true }
        return step != 0
    }

    // ===================== 页面切换 =====================
    private fun go(to: Int) {
        if (transitioning || loginBusy || to == step) return
        val from = pageView
        val dir = if (to > step) 1 else -1
        showPage(to, false)
        val next = pageView!!
        transitioning = true
        next.alpha = 0f
        next.translationX = dir * Ui.f(60f)
        next.animate().alpha(1f).translationX(0f).setStartDelay(60).setDuration(300)
            .setInterpolator(DecelerateInterpolator(1.6f)).withEndAction { transitioning = false }.start()
        from?.animate()?.alpha(0f)?.translationX(-dir * Ui.f(60f))?.setStartDelay(0)?.setDuration(200)
            ?.withEndAction { pages.removeView(from) }?.start()
    }

    /** replace=true：先移除旧页（换语言后原地刷新） */
    private fun showPage(to: Int, replace: Boolean) {
        stopGreet()
        if (replace) pageView?.let { pages.removeView(it) }
        step = to
        ctaView = null; msgView = null; nameField = null; passwordField = null
        val page = scrollable(when (to) {
            0 -> pageWelcome()
            1 -> pageLogin()
            2 -> pageLanguage()
            else -> pageReady()
        })
        pages.addView(page, LayoutParams(-1, -1))
        pageView = page
        refreshBar()
    }

    // ===================== 底栏：返回 + 步骤点 =====================
    private fun refreshBar() {
        val c = context
        bar.removeAllViews()
        if (step == 3) return
        if (step == 1) {
            bar.addView(TextView(c).apply {
                text = "‹  ${tr("返回")}"; px(13.5f); setTextColor(Palette.ink2)
                setPadding(Ui.u(32f), Ui.u(14f), Ui.u(24f), Ui.u(14f))
                setOnClickListener { handleBack() }
            }, LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        }
        val dots = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        for (i in 0..2) {
            val active = i == step
            dots.addView(View(c).apply {
                background = rr(if (active) Palette.ink else withAlpha(Palette.ink, 0.28f), Ui.f(99f))
            }, LinearLayout.LayoutParams(Ui.u(if (active) 20f else 7f), Ui.u(7f)).apply {
                if (i > 0) leftMargin = Ui.u(7f)
            })
        }
        bar.addView(dots, LayoutParams(-2, -2, Gravity.CENTER))
    }

    // ===================== 通用组件（磨砂玻璃风格） =====================
    private fun colW() = minOf(Ui.u(340f), (Ui.panelW * 0.8f).toInt())

    private fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = LayoutParams(colW(), -2, Gravity.CENTER_HORIZONTAL)
    }

    /** 徽章里的图标色：由主题色压暗得到，随主题自适应 */
    private fun badgeTint() = mixColor(Palette.accent, Color.BLACK, 0.5f)

    private fun badgeFill() = withAlpha(Palette.ink, 0.84f)

    private fun badgeIcon(res: Int, iconSize: Float): View = FrameLayout(context).apply {
        background = rr(badgeFill(), Ui.f(999f))
        addView(ImageView(context).apply { setImageResource(res); setColorFilter(badgeTint()) },
            LayoutParams(Ui.u(iconSize), Ui.u(iconSize), Gravity.CENTER))
    }

    private fun badgeText(t: String, textPx: Float): View = FrameLayout(context).apply {
        background = rr(badgeFill(), Ui.f(999f))
        addView(TextView(context).apply {
            text = t; px(textPx); bold(); setTextColor(badgeTint()); gravity = Gravity.CENTER
        }, LayoutParams(-2, -2, Gravity.CENTER))
    }

    /** 磨砂玻璃胶囊容器 */
    private fun glassPill(selected: Boolean = false): LinearLayout = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = if (selected)
            rr(withAlpha(Palette.ink, 0.18f), Ui.f(999f), Palette.accent, Ui.u(1.5f))
        else
            rr(withAlpha(Palette.ink, 0.10f), Ui.f(999f), withAlpha(Palette.ink, 0.18f), Ui.u(1f))
        setPadding(Ui.u(4f), Ui.u(4f), Ui.u(18f), Ui.u(4f))
    }

    private fun styleInput(et: EditText) = et.apply {
        px(14.5f); setTextColor(Palette.ink); setHintTextColor(withAlpha(Palette.ink, 0.5f))
        background = null; setPadding(0, 0, 0, 0)
    }

    /** 浅色宽按钮（像参考图的 Sign In） */
    private fun cta(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text; px(15f); bold(); gravity = Gravity.CENTER; setTextColor(Palette.inkBlack)
        background = ripple(rr(withAlpha(Palette.ink, 0.88f), Ui.f(999f)), Ui.f(999f))
        setOnClickListener { onClick() }
    }

    private fun title(t: String) = TextView(context).apply {
        text = t; px(26f); bold(); setTextColor(Palette.ink); gravity = Gravity.CENTER
    }

    private fun subtitle(t: String) = TextView(context).apply {
        text = t; px(13f); setTextColor(Palette.ink2); gravity = Gravity.CENTER
        setLineSpacing(Ui.f(3f), 1f)
    }

    private fun hideIme() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
    }

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    /** 内容放进可滚动容器：空间够时垂直居中，不够（小屏 / 键盘弹出）时可以滚动，永远不会被截断 */
    private fun scrollable(content: View): View = android.widget.ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(content)
    }

    // ===================== ① 欢迎 =====================
    private fun pageWelcome(): View {
        val c = context
        val greet = TextView(c).apply {
            text = GREETINGS[0]; px(38f); bold(); setTextColor(Palette.ink); gravity = Gravity.CENTER
        }
        startGreet(greet)
        return column().apply {
            addView(badgeText("N", 40f), lp(Ui.u(92f), Ui.u(92f)))
            addView(greet, lp(-2, -2).apply { topMargin = Ui.u(26f) })
            addView(subtitle(tr("登录并完成几个简单的设置，即可开始使用")), lp(-2, -2).apply { topMargin = Ui.u(10f) })
            addView(cta(tr("开始")) { go(1) }.also { ctaView = it }, lp(-1, Ui.u(52f)).apply { topMargin = Ui.u(34f) })
        }
    }

    private fun startGreet(tv: TextView) {
        var i = 0
        val r = object : Runnable {
            override fun run() {
                tv.animate().alpha(0f).setStartDelay(0).setDuration(300).withEndAction {
                    i = (i + 1) % GREETINGS.size
                    tv.text = GREETINGS[i]
                    tv.animate().alpha(1f).setStartDelay(0).setDuration(300).start()
                }.start()
                handler.postDelayed(this, 2600)
            }
        }
        greetRunnable = r
        handler.postDelayed(r, 2400)
    }

    private fun stopGreet() {
        greetRunnable?.let { handler.removeCallbacks(it) }
        greetRunnable = null
    }

    // ===================== ② 登录（账户名 + 密码） =====================
    private fun pageLogin(): View {
        val c = context
        val name = styleInput(EditText(c)).apply {
            hint = tr("账户名")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setText(loginName)
        }
        // 密码：普通文本输入 + 圆点遮挡（不使用安全键盘 / 密码输入类型）
        val pw = styleInput(EditText(c)).apply {
            hint = tr("密码")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            transformationMethod = PasswordTransformationMethod.getInstance()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE) { doLogin(); true } else false
            }
        }
        nameField = name; passwordField = pw

        var shown = false
        val eye = ImageView(c).apply {
            setImageResource(R.drawable.ic_eye); setColorFilter(withAlpha(Palette.ink, 0.6f))
            val pd = Ui.u(6f); setPadding(pd, pd, pd, pd)
            background = circleRipple()
            setOnClickListener {
                shown = !shown
                pw.transformationMethod = if (shown) null else PasswordTransformationMethod.getInstance()
                pw.setSelection(pw.text.length)
                setColorFilter(if (shown) Palette.ink else withAlpha(Palette.ink, 0.6f))
            }
        }
        val pillName = glassPill().apply {
            addView(badgeIcon(R.drawable.ic_user, 22f), lp(Ui.u(48f), Ui.u(48f)))
            addView(name, lp(0, -2).apply { weight = 1f; leftMargin = Ui.u(16f) })
        }
        val pillPw = glassPill().apply {
            addView(badgeIcon(R.drawable.ic_lock, 22f), lp(Ui.u(48f), Ui.u(48f)))
            addView(pw, lp(0, -2).apply { weight = 1f; leftMargin = Ui.u(16f) })
            addView(eye, lp(Ui.u(30f), Ui.u(30f)))
        }
        val msg = TextView(c).apply { px(12.5f); text = " "; visibility = View.INVISIBLE; gravity = Gravity.CENTER }
        msgView = msg

        // “记住我”（只记账户名） + “忘记密码？”
        val check = CheckBoxView(c).apply { checked = remember }
        val remRow = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { remember = !remember; check.checked = remember }
            addView(check, lp(Ui.u(22f), Ui.u(22f)))
            addView(TextView(c).apply { text = tr("记住我"); px(12.5f); setTextColor(Palette.ink2) },
                lp(-2, -2).apply { leftMargin = Ui.u(8f) })
        }
        val forgot = TextView(c).apply {
            text = tr("忘记密码？"); px(12.5f); setTextColor(Palette.ink2)
            setTypeface(typeface, Typeface.ITALIC)
            setPadding(Ui.u(6f), Ui.u(4f), Ui.u(4f), Ui.u(4f))
            setOnClickListener { showMsg(tr("暂未接入密码找回"), false) }
        }
        val under = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.u(10f), 0, Ui.u(10f), 0)
            addView(remRow)
            addView(View(c), lp(0, 1).apply { weight = 1f })
            addView(forgot)
        }
        return column().apply {
            addView(title(tr("登录账号")))
            addView(subtitle(tr("输入账户名和密码以激活 rlin client")), lp(-2, -2).apply { topMargin = Ui.u(8f); bottomMargin = Ui.u(24f) })
            addView(pillName, lp(-1, Ui.u(56f)))
            addView(pillPw, lp(-1, Ui.u(56f)).apply { topMargin = Ui.u(14f) })
            addView(msg, lp(-1, -2).apply { topMargin = Ui.u(10f) })
            addView(cta(tr("登录")) { doLogin() }.also { ctaView = it }, lp(-1, Ui.u(52f)).apply { topMargin = Ui.u(8f) })
            addView(under, lp(-1, -2).apply { topMargin = Ui.u(14f) })
        }
    }

    private fun showMsg(msg: String, error: Boolean) {
        msgView?.apply {
            text = msg
            setTextColor(if (error) 0xFFE9917F.toInt() else Palette.ink2)
            visibility = View.VISIBLE
        }
    }

    private fun setLoginBusy(b: Boolean) {
        loginBusy = b
        ctaView?.apply {
            text = if (b) tr("正在登录…") else tr("登录")
            alpha = if (b) 0.6f else 1f
        }
    }

    private fun doLogin() {
        if (loginBusy || transitioning || step != 1) return
        val n = nameField?.text?.toString()?.trim().orEmpty()
        val p = passwordField?.text?.toString().orEmpty()
        if (n.isEmpty()) { showMsg(tr("请输入账户名"), true); return }
        if (p.isEmpty()) { showMsg(tr("请输入密码"), true); return }
        msgView?.visibility = View.INVISIBLE
        hideIme()
        setLoginBusy(true)
        accounts.login(n, p) { r ->
            setLoginBusy(false)
            if (r.ok) {
                loginName = n
                accounts.setRememberedName(if (remember) n else null)
                passwordField?.setText("")
                go(2)
            } else {
                showMsg(r.message ?: tr("登录失败，请重试"), true)
            }
        }
    }

    // ===================== ③ 选择语言 =====================
    private fun pageLanguage(): View {
        val c = context
        val col = column()
        col.addView(title(tr("选择语言")))
        col.addView(subtitle(tr("之后可在 ClickGUI 管理 中修改")), lp(-2, -2).apply { topMargin = Ui.u(8f); bottomMargin = Ui.u(24f) })
        Lang.values().forEach { l ->
            val sel = I18n.lang == l
            val texts = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(c).apply { text = l.label; px(15.5f); bold(); setTextColor(Palette.ink) })
                addView(TextView(c).apply {
                    text = if (l == Lang.ZH) "界面使用简体中文" else "Use English for the interface"
                    px(11.5f); setTextColor(Palette.ink2)
                })
            }
            val pill = glassPill(sel).apply {
                setOnClickListener { chooseLang(l) }
                addView(badgeText(if (l == Lang.ZH) "文" else "A", 18f), lp(Ui.u(52f), Ui.u(52f)))
                addView(texts, lp(0, -2).apply { weight = 1f; leftMargin = Ui.u(16f) })
                addView(RadioView(c).apply { checked = sel }, lp(Ui.u(22f), Ui.u(22f)))
            }
            col.addView(pill, lp(-1, Ui.u(60f)).apply { if (l.ordinal > 0) topMargin = Ui.u(12f) })
        }
        col.addView(cta(tr("继续")) {
            if (transitioning) return@cta
            repo.applyAppearance(); go(3)
        }.also { ctaView = it }, lp(-1, Ui.u(52f)).apply { topMargin = Ui.u(22f) })
        return col
    }

    /** 选中即生效：整套向导立即切换到所选语言 */
    private fun chooseLang(l: Lang) {
        if (I18n.lang == l || transitioning) return
        val m = repo.modules.first { it.id == SampleData.GUI_ID }
        repo.setInt(m, SampleData.KEY_LANG, l.ordinal)
        I18n.lang = l
        showPage(2, true)
    }

    // ===================== ④ 准备完成并进入 =====================
    private fun pageReady(): View {
        val c = context
        val track = FrameLayout(c).apply { background = rr(withAlpha(Palette.ink, 0.14f), Ui.f(99f)) }
        val fill = View(c).apply {
            background = rr(Palette.ink, Ui.f(99f)); pivotX = 0f; scaleX = 0f
        }
        track.addView(fill, LayoutParams(-1, -1))
        transitioning = true
        progressAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener { fill.scaleX = it.animatedValue as Float }
            var cancelled = false
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (!cancelled) finish() }
            })
            startDelay = 350
            start()
        }
        return column().apply {
            addView(badgeText("N", 32f), lp(Ui.u(72f), Ui.u(72f)))
            addView(title(tr("一切就绪")), lp(-2, -2).apply { topMargin = Ui.u(22f) })
            addView(subtitle(tr("正在为你准备 rlin client…")), lp(-2, -2).apply { topMargin = Ui.u(8f); bottomMargin = Ui.u(28f) })
            addView(track, lp(Ui.u(220f), Ui.u(6f)))
        }
    }

    private fun finish() {
        accounts.markSetupDone()
        animate().alpha(0f).setDuration(220).withEndAction { onFinished() }.start()
    }

    private companion object {
        val GREETINGS = listOf("欢迎使用", "Welcome")
    }
}
