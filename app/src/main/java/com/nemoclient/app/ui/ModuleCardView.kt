package com.nemoclient.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nemoclient.app.R
import com.nemoclient.app.data.Module
import com.nemoclient.app.i18n.tr

/** 模块卡片：纯文字（名称 + 说明）+ MD3 开关 + 设置箭头。扁平，无图标、无光效。 */
class ModuleCardView(ctx: Context) : LinearLayout(ctx) {
    private val name = TextView(ctx)
    private val desc = TextView(ctx)
    private val sw = Md3Switch(ctx)
    private val ex = ImageView(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rr(Palette.glass, Ui.f(18f), Palette.line, Ui.u(1f))
        setPadding(Ui.u(18f), Ui.u(16f), Ui.u(14f), Ui.u(16f))

        name.apply { px(15f); bold(); setTextColor(Palette.ink); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        desc.apply { px(11.5f); setTextColor(Palette.ink2); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        val col = LinearLayout(ctx).apply {
            orientation = VERTICAL
            addView(name)
            addView(desc, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.u(2f) })
        }
        addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(sw, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.u(12f) })

        ex.apply {
            setImageResource(R.drawable.ic_expand)
            setColorFilter(Palette.ink2)
            val pd = Ui.u(7f)
            setPadding(pd, pd, pd, pd)
            background = RippleDrawable(
                ColorStateList.valueOf(withAlpha(Palette.sage, 0.45f)), null, ShapeDrawable(OvalShape())
            )
        }
        addView(ex, LayoutParams(Ui.u(30f), Ui.u(30f)).apply { leftMargin = Ui.u(8f) })
    }

    fun bind(m: Module, on: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
        name.text = tr(m.name)
        desc.text = tr(m.desc)
        sw.setChecked(on, false)
        sw.onToggle = onToggle
        ex.setOnClickListener { onOpen() }
    }
}
