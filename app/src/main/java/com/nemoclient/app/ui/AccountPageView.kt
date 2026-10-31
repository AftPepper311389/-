package com.nemoclient.app.ui

import android.content.Context
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nemoclient.app.R
import com.nemoclient.app.i18n.tf
import com.nemoclient.app.i18n.tr
import com.nemoclient.app.data.AccountStore
import com.nemoclient.app.data.GameAccount
import com.nemoclient.app.data.SaveResult
import com.nemoclient.app.data.Tier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 账号页需要宿主（ClickGuiView）提供的能力：弹出卡片 + 轻提示 */
interface SheetHost {
    fun showSheet(
        title: String, subtitle: String?, body: View?, primary: String,
        danger: Boolean = false, secondary: String? = tr("取消"), onPrimary: () -> Boolean
    )
    fun toast(msg: String)
}

/**
 * 账号管理页：
 *  ① 软件账号卡片（头像 / 昵称可改 / 称号只读 / UID·注册时间·累计使用 / 切换·退出）
 *  ② 游戏账号（Cookie 登录）：添加、批量导入、多选批量删除、选用
 */
class AccountPageView(
    ctx: Context,
    private val store: AccountStore,
    private val host: SheetHost
) : LinearLayout(ctx) {

    private val profileBox = LinearLayout(ctx)
    private val gamesHeader = LinearLayout(ctx)
    private val gamesGrid = LinearLayout(ctx)
    private var filter = ""
    private var selecting = false
    private val selected = mutableSetOf<String>()

    init {
        orientation = LinearLayout.VERTICAL
        profileBox.orientation = LinearLayout.VERTICAL
        gamesGrid.orientation = LinearLayout.VERTICAL

        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.u(26f), Ui.u(18f), Ui.u(26f), Ui.u(14f))
            addView(TextView(ctx).apply { text = tr("账号"); px(22f); bold(); setTextColor(Palette.ink) })
            addView(TextView(ctx).apply { text = tr("本地账号 · 数据仅保存在本机"); px(12f); setTextColor(Palette.ink2) },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(14f) })
        })
        addView(View(ctx).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(-1, Ui.u(1f)))

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.u(26f), Ui.u(18f), Ui.u(26f), Ui.u(24f))
            addView(profileBox)
            addView(gamesHeader, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.u(22f) })
            addView(gamesGrid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.u(10f) })
        }
        addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(body)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        refreshProfile()
        refreshGames()
    }

    fun reset() { selecting = false; selected.clear(); filter = "" }
    fun setFilter(q: String) { filter = q.lowercase(); refreshGames() }

    // ===================== ① 软件账号卡片 =====================
    fun refreshProfile() {
        val c = context
        val p = store.profile
        profileBox.removeAllViews()

        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = rr(Palette.glass, Ui.f(20f), Palette.line, Ui.u(1f))
            setPadding(Ui.u(22f), Ui.u(20f), Ui.u(22f), Ui.u(18f))
        }

        val avatar = TextView(c).apply {
            text = p.nickname.take(1).uppercase(); px(28f); bold()
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = rr(Palette.brown, Ui.f(999f))
        }
        val name = TextView(c).apply {
            text = p.nickname; px(20f); bold(); setTextColor(Palette.ink)
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        }
        val edit = ImageView(c).apply {
            setImageResource(R.drawable.ic_edit); setColorFilter(Palette.ink2)
            val pd = Ui.u(6f); setPadding(pd, pd, pd, pd)
            background = circleRipple()
            setOnClickListener { editNickname() }
        }
        val nameRow = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(name, LinearLayout.LayoutParams(-2, -2))
            addView(edit, LinearLayout.LayoutParams(Ui.u(28f), Ui.u(28f)).apply { leftMargin = Ui.u(6f) })
        }
        val badges = LinearLayout(c).apply {
            addView(badge(tr(p.tier.label), tierColor(p.tier), true))
            p.titles.forEach { addView(badge(tr(it), Palette.accent, false)) }
        }
        val hint = TextView(c).apply { text = tr("称号由官方授予，无法自行修改"); px(11f); setTextColor(Palette.ink2) }
        val mid = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameRow)
            addView(badges, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.u(8f) })
            addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.u(6f) })
        }
        val actions = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(pillButton(c, tr("切换账号"), 1) { switchUserSheet() })
            addView(pillButton(c, tr("退出登录"), 2) { logoutSheet() },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.u(8f) })
        }
        card.addView(LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(avatar, LinearLayout.LayoutParams(Ui.u(72f), Ui.u(72f)))
            addView(mid, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.u(18f) })
            addView(actions)
        })
        card.addView(View(c).apply { setBackgroundColor(Palette.line) },
            LinearLayout.LayoutParams(-1, Ui.u(1f)).apply { topMargin = Ui.u(16f); bottomMargin = Ui.u(14f) })
        card.addView(LinearLayout(c).apply {
            addView(stat("UID", p.uid), LinearLayout.LayoutParams(0, -2, 1f))
            addView(stat(tr("注册时间"), fmtDate(p.registeredAt)), LinearLayout.LayoutParams(0, -2, 1f))
            addView(stat(tr("累计使用"), fmtUsage(p.usageSeconds)), LinearLayout.LayoutParams(0, -2, 1f))
        })
        profileBox.addView(card)
    }

    private fun stat(label: String, value: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { text = label; px(11f); setTextColor(Palette.ink2) })
        addView(TextView(context).apply { text = value; px(14f); bold(); setTextColor(Palette.ink) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.u(3f) })
    }

    private fun tierColor(t: Tier) = when (t) {
        Tier.NORMAL -> alphaMix(Palette.sage)
        Tier.PRO -> Palette.accent
        Tier.SPONSOR -> 0xFF8C7A3E.toInt()
    }

    private fun alphaMix(c: Int) = withAlpha(c, 0.7f)

    private fun badge(text: String, color: Int, filled: Boolean) = TextView(context).apply {
        this.text = text; px(11.5f); bold(); gravity = Gravity.CENTER
        setTextColor(if (filled) Color.WHITE else color)
        setPadding(Ui.u(11f), Ui.u(4f), Ui.u(11f), Ui.u(4f))
        background = if (filled) rr(color, Ui.f(999f)) else rr(0, Ui.f(999f), color, Ui.u(1f))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Ui.u(6f) }
    }

    // ===================== ② 游戏账号 =====================
    fun refreshGames() {
        val c = context
        selected.retainAll(store.games.map { it.id }.toSet())
        val cur = store.games.firstOrNull { it.id == store.currentGameId }

        gamesHeader.removeAllViews()
        gamesHeader.gravity = Gravity.CENTER_VERTICAL
        val left = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(c).apply {
                text = if (selecting) tf("已选 %d 个", selected.size) else tr("游戏账号")
                px(15f); bold(); setTextColor(Palette.ink)
            })
            addView(TextView(c).apply {
                text = if (selecting) tr("点按卡片选择 / 取消") else tf("共 %d 个 · 使用中：%s", store.games.size, cur?.name ?: tr("无"))
                px(11.5f); setTextColor(Palette.ink2)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.u(2f) })
        }
        gamesHeader.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val gap = { LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(8f) } }
        if (!selecting) {
            gamesHeader.addView(pillButton(c, tr("添加"), 0) { addGameSheet() }, gap())
            gamesHeader.addView(pillButton(c, tr("批量导入"), 1) { importSheet() }, gap())
            gamesHeader.addView(pillButton(c, tr("多选"), 1) { selecting = true; selected.clear(); refreshGames() }, gap())
        } else {
            val visibleIds = visibleGames().map { it.id }
            val allOn = visibleIds.isNotEmpty() && selected.containsAll(visibleIds)
            gamesHeader.addView(pillButton(c, if (allOn) tr("取消全选") else tr("全选"), 1) {
                if (allOn) selected.removeAll(visibleIds.toSet()) else selected.addAll(visibleIds)
                refreshGames()
            }, gap())
            gamesHeader.addView(pillButton(c, tr("删除"), if (selected.isEmpty()) 1 else 3) { deleteSelected() }.apply {
                alpha = if (selected.isEmpty()) 0.5f else 1f
            }, gap())
            gamesHeader.addView(pillButton(c, tr("完成"), 1) { selecting = false; selected.clear(); refreshGames() }, gap())
        }

        gamesGrid.removeAllViews()
        val list = visibleGames()
        if (list.isEmpty()) {
            gamesGrid.addView(TextView(c).apply {
                text = if (store.games.isEmpty()) tr("还没有游戏账号，点「添加」或「批量导入」") else tr("没有匹配的游戏账号")
                px(12.5f); setTextColor(Palette.ink2); gravity = Gravity.CENTER
                background = rr(Palette.glass, Ui.f(18f), Palette.line, Ui.u(1f))
                setPadding(0, Ui.u(28f), 0, Ui.u(28f))
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }
        list.chunked(2).forEachIndexed { r, pair ->
            val row = LinearLayout(c)
            pair.forEachIndexed { i, a ->
                row.addView(accountCard(a), LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (i == 0) rightMargin = Ui.u(6f) else leftMargin = Ui.u(6f)
                })
            }
            if (pair.size == 1) row.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = Ui.u(6f) })
            gamesGrid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = Ui.u(12f) })
        }
    }

    private fun visibleGames(): List<GameAccount> = store.games.filter {
        filter.isEmpty() || it.name.lowercase().contains(filter) || it.note.lowercase().contains(filter)
    }

    private fun accountCard(a: GameAccount): View {
        val c = context
        val cur = store.currentGameId == a.id
        val card = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rr(
                if (cur) withAlpha(Palette.accent, 0.14f) else Palette.glass, Ui.f(18f),
                if (cur) Palette.accent else Palette.line, if (cur) Ui.u(1.5f) else Ui.u(1f)
            )
            setPadding(Ui.u(18f), Ui.u(14f), Ui.u(14f), Ui.u(14f))
        }
        if (selecting) {
            card.addView(CheckBoxView(c).apply { checked = a.id in selected },
                LinearLayout.LayoutParams(Ui.u(22f), Ui.u(22f)).apply { rightMargin = Ui.u(14f) })
        }
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(c).apply {
                text = a.name; px(14.5f); bold(); setTextColor(Palette.ink)
                isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(c).apply {
                text = "${tr(a.note.ifBlank { "Cookie 登录" })} · ${fmtAgo(a.lastUsed)}"
                px(11.5f); setTextColor(Palette.ink2); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.u(2f) })
        }
        card.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (!selecting) {
            if (cur) {
                card.addView(TextView(c).apply {
                    text = tr("使用中"); px(11.5f); bold(); setTextColor(Palette.accent); gravity = Gravity.CENTER
                    setPadding(Ui.u(11f), Ui.u(5f), Ui.u(11f), Ui.u(5f))
                    background = rr(0, Ui.f(999f), Palette.accent, Ui.u(1f))
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(8f) })
            } else {
                card.addView(pillButton(c, tr("使用"), 1) {
                    if (store.useGame(a.id)) { host.toast(tf("已选用 %s", a.name)); refreshGames() }
                    else host.toast(tr("Cookie 读取失败，请重新添加该账号"))
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.u(8f) })
            }
        }
        card.setOnClickListener {
            if (selecting) { if (!selected.add(a.id)) selected.remove(a.id); refreshGames() }
        }
        card.setOnLongClickListener {
            if (!selecting) { selecting = true; selected.clear(); selected.add(a.id); refreshGames() }
            true
        }
        return card
    }

    // ===================== 弹出卡片 =====================
    private fun input(hint: String, multi: Boolean = false, secret: Boolean = false): EditText = EditText(context).apply {
        this.hint = hint
        px(13.5f); setTextColor(Palette.ink); setHintTextColor(Palette.ink2)
        background = rr(withAlpha(Palette.sage, 0.35f), Ui.f(12f), Palette.line, Ui.u(1f))
        setPadding(Ui.u(14f), Ui.u(10f), Ui.u(14f), Ui.u(10f))
        when {
            multi -> {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 5; maxLines = 8; gravity = Gravity.TOP or Gravity.START
            }
            secret -> {
                // 圆点遮挡，但不使用密码输入类型（避免弹出系统安全键盘）
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isSingleLine = true
                transformationMethod = PasswordTransformationMethod.getInstance()
            }
            else -> isSingleLine = true
        }
    }

    private fun msg(text: String) = TextView(context).apply {
        this.text = text; px(13f); setTextColor(Palette.ink2); setLineSpacing(Ui.f(3f), 1f)
    }

    private fun editNickname() {
        val et = input(tr("昵称")).apply {
            filters = arrayOf(InputFilter.LengthFilter(16))
            setText(store.profile.nickname); setSelection(text.length)
        }
        host.showSheet(tr("修改昵称"), tr("最多 16 个字符"), et, tr("保存")) {
            val n = et.text.toString().trim()
            if (n.isEmpty()) { host.toast(tr("昵称不能为空")); false }
            else { store.setNickname(n); refreshProfile(); host.toast(tr("昵称已更新")); true }
        }
    }

    private fun addGameSheet() {
        val name = input(tr("游戏账号名称")).apply { filters = arrayOf(InputFilter.LengthFilter(32)) }
        val cookie = input("Cookie", secret = true)
        val note = input(tr("备注（可选）")).apply { filters = arrayOf(InputFilter.LengthFilter(24)) }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(cookie, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.u(10f) })
            addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.u(10f) })
        }
        host.showSheet(tr("添加游戏账号"), tr("Cookie 加密保存在本机，界面不会显示；同名账号会更新 Cookie"), box, tr("保存")) {
            val n = name.text.toString().trim()
            val ck = cookie.text.toString().trim()
            when {
                n.isEmpty() -> { host.toast(tr("请填写账号名称")); false }
                ck.isEmpty() -> { host.toast(tr("请填写 Cookie")); false }
                else -> when (store.saveGame(n, note.text.toString().trim(), ck)) {
                    SaveResult.ADDED -> { refreshGames(); host.toast(tf("已添加 %s", n)); true }
                    SaveResult.UPDATED -> { refreshGames(); host.toast(tf("已更新 %s 的 Cookie", n)); true }
                    SaveResult.FAILED -> { host.toast(tr("无法安全保存 Cookie")); false }
                }
            }
        }
    }

    private fun importSheet() {
        val et = input(tr("每行一个：名称----Cookie\n例如：Steve_01----key=value; key2=value2"), multi = true)
        host.showSheet(tr("批量导入游戏账号"), tr("每行一条，名称与 Cookie 用 ---- 分隔；同名自动更新"), et, tr("导入")) {
            val (added, updated, skipped) = store.importGames(et.text.toString())
            refreshGames()
            if (added + updated == 0) { host.toast(tr("没有可导入的有效行")); false }
            else {
                host.toast(tf("新增 %d · 更新 %d", added, updated) + (if (skipped > 0) tf(" · 跳过 %d", skipped) else ""))
                true
            }
        }
    }

    private fun deleteSelected() {
        if (selected.isEmpty()) return
        val n = selected.size
        host.showSheet(tr("删除游戏账号"), null, msg(tf("确定删除选中的 %d 个游戏账号吗？对应的 Cookie 会一并清除，此操作无法撤销。", n)), tr("删除"), danger = true) {
            store.removeGames(selected.toSet()); selected.clear(); selecting = false
            refreshGames(); host.toast(tf("已删除 %d 个账号", n)); true
        }
    }

    private fun logoutSheet() {
        host.showSheet(tr("退出登录"), null, msg(tr("退出后需要重新登录软件账号，游戏账号列表不会受影响。")), tr("退出"), danger = true) {
            store.logout(); host.toast(tr("已退出登录")); true
        }
    }

    private fun switchUserSheet() {
        host.showSheet(tr("切换账号"), null, msg(tr("切换到其他软件账号。接入登录系统后，这里会列出可切换的账号。")), tr("切换")) {
            store.switchUser(); host.toast(tr("已发起切换（暂未接入登录系统）")); true
        }
    }

    // ===================== 格式化 =====================
    private fun fmtDate(t: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(t))

    private fun fmtUsage(sec: Long): String {
        val m = sec / 60; val h = m / 60
        return if (h > 0) tf("%d 小时 %d 分钟", h, m % 60) else tf("%d 分钟", m)
    }

    private fun fmtAgo(t: Long): String {
        if (t <= 0) return tr("从未使用")
        val d = (System.currentTimeMillis() - t) / 1000
        return when {
            d < 60 -> tr("刚刚")
            d < 3600 -> tf("%d 分钟前", d / 60)
            d < 86400 -> tf("%d 小时前", d / 3600)
            else -> tf("%d 天前", d / 86400)
        }
    }
}
