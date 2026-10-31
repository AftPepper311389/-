package com.nemoclient.app.i18n

import android.content.Context
import java.util.Locale

enum class Lang(val label: String) { ZH("简体中文"), EN("English") }

/**
 * 轻量多语言：以中文原文为 key，English 在 [EN] 里查表，查不到就原样显示。
 * 语言保存在 ClickGUI 管理的「语言」设置里（与向导第三步同一份）。
 */
object I18n {
    var lang: Lang = Lang.ZH

    /** 英文单词普遍比汉字长：英文模式下界面文字整体缩小一点，避免撑变形 */
    val textScale: Float get() = if (lang == Lang.EN) 0.86f else 1f

    fun systemDefault(): Lang = if (Locale.getDefault().language == "zh") Lang.ZH else Lang.EN

    /** 在没有 ModuleRepo 的地方（Activity / Service 启动时）读取已保存的语言 */
    fun load(ctx: Context) {
        val sp = ctx.applicationContext.getSharedPreferences("nemo_state", Context.MODE_PRIVATE)
        val i = sp.getInt("v.ClickGUI.语言", systemDefault().ordinal).coerceIn(0, Lang.values().size - 1)
        lang = Lang.values()[i]
    }

    fun tr(zh: String): String = if (lang == Lang.EN) EN[zh] ?: zh else zh
}

fun tr(zh: String): String = I18n.tr(zh)

/** 带占位符的文案：模板用 %d / %s，中英文各写一份 */
fun tf(zh: String, vararg args: Any): String = String.format(Locale.US, I18n.tr(zh), *args)
