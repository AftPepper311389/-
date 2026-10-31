package com.nemoclient.app.data

import android.content.Context
import com.nemoclient.app.i18n.I18n
import com.nemoclient.app.i18n.Lang
import com.nemoclient.app.ui.Palette
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 模块状态 + 设置的读写与持久化（SharedPreferences）。
 * 开关 / 快捷键变化会通知监听者，让 GUI 与悬浮快捷键保持同步。
 */
class ModuleRepo(ctx: Context, var backend: ModuleBackend = LogBackend()) {
    private val sp = ctx.applicationContext.getSharedPreferences("nemo_state", Context.MODE_PRIVATE)
    val modules: List<Module> = SampleData.modules
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val toggleListeners = CopyOnWriteArrayList<(Module, Boolean) -> Unit>()
    /** 模块开关真正发生变化时回调（开关提示 HUD 用） */
    fun addToggleListener(l: (Module, Boolean) -> Unit) { toggleListeners.add(l) }
    fun removeToggleListener(l: (Module, Boolean) -> Unit) { toggleListeners.remove(l) }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() = listeners.forEach { it() }

    // ---- 启用 / 快捷键 ----
    fun isOn(m: Module) = sp.getBoolean("on.${m.id}", m.defaultOn)
    fun setOn(m: Module, v: Boolean) {
        val old = isOn(m)
        sp.edit().putBoolean("on.${m.id}", v).apply()
        backend.onToggle(m, v)
        if (old != v) toggleListeners.forEach { it(m, v) }
        changed()
    }

    fun hasShortcut(m: Module) = sp.getBoolean("sc.${m.id}", false)
    fun setShortcut(m: Module, v: Boolean) {
        sp.edit().putBoolean("sc.${m.id}", v).apply()
        changed()
    }

    fun enabledCount() = modules.count { isOn(it) }

    // ---- 设置值 ----
    fun getFloat(id: String, key: String, def: Float) = sp.getFloat("v.$id.$key", def)
    fun getBool(id: String, key: String, def: Boolean) = sp.getBoolean("v.$id.$key", def)
    fun getInt(id: String, key: String, def: Int) = sp.getInt("v.$id.$key", def)

    fun setFloat(m: Module, key: String, v: Float) {
        sp.edit().putFloat("v.${m.id}.$key", v).apply(); backend.onSettingChange(m, key, v)
    }
    fun setBool(m: Module, key: String, v: Boolean) {
        sp.edit().putBoolean("v.${m.id}.$key", v).apply(); backend.onSettingChange(m, key, v)
    }
    fun setInt(m: Module, key: String, v: Int) {
        sp.edit().putInt("v.${m.id}.$key", v).apply(); backend.onSettingChange(m, key, v)
    }

    // ---- 界面外观（来自 ClickGUI 管理）----
    fun blurEnabled() = getBool(SampleData.GUI_ID, SampleData.KEY_BLUR, true)
    fun uiScale() = getFloat(SampleData.GUI_ID, SampleData.KEY_SCALE, 100f) / 100f
    fun uiAlpha() = getFloat(SampleData.GUI_ID, SampleData.KEY_ALPHA, 100f) / 100f
    fun applyAppearance() {
        val i = getInt(SampleData.GUI_ID, SampleData.KEY_THEME, 0).coerceIn(0, SampleData.themeNames.size - 1)
        if (i < Palette.accents.size) { Palette.accent = Palette.accents[i]; Palette.tint = false }
        else Palette.setCustom(getInt(SampleData.GUI_ID, SampleData.KEY_COLOR, 0xFFB15F32.toInt()))
        Palette.light = getInt(SampleData.GUI_ID, SampleData.KEY_MODE, 0) == 1
        Palette.hudLight = Palette.light && getInt(SampleData.GUI_ID, SampleData.KEY_HUD_MODE, 0) == 1
        val l = getInt(SampleData.GUI_ID, SampleData.KEY_LANG, I18n.systemDefault().ordinal).coerceIn(0, Lang.values().size - 1)
        I18n.lang = Lang.values()[l]
    }

    // ---- HUD（水印 / 模块列表 ...）----
    fun hudOn(t: HudType) = sp.getBoolean("hud.${t.id}.on", false)
    fun setHudOn(t: HudType, v: Boolean) { sp.edit().putBoolean("hud.${t.id}.on", v).apply(); changed() }
    fun hudBool(t: HudType, key: String, def: Boolean) = sp.getBoolean("hud.${t.id}.$key", def)
    fun hudInt(t: HudType, key: String, def: Int) = sp.getInt("hud.${t.id}.$key", def)
    fun setHudBool(t: HudType, key: String, v: Boolean) { sp.edit().putBoolean("hud.${t.id}.$key", v).apply(); changed() }
    fun setHudInt(t: HudType, key: String, v: Int) { sp.edit().putInt("hud.${t.id}.$key", v).apply(); changed() }
    fun setHudScale(t: HudType, v: Int) = setHudInt(t, "scale", v)
    fun setHudOpacity(t: HudType, v: Int) = setHudInt(t, "opacity", v)
    /** 所有 HUD 共用的渐变配色（HudTheme 里的第几套） */
    fun hudTheme() = sp.getInt("hud.theme", 0)
    fun setHudTheme(i: Int) { sp.edit().putInt("hud.theme", i).apply(); changed() }

    /** 某个 HUD 当前的全部选项值，给视图读（每次仓库变化时重新生成） */
    fun hudCfg(t: HudType): HudCfg {
        val bools = HashMap<String, Boolean>()
        val ints = HashMap<String, Int>()
        t.opts.forEach { o ->
            when (o) {
                is HudOpt.Toggle -> bools[o.key] = hudBool(t, o.key, o.def)
                is HudOpt.Choice -> ints[o.key] = hudInt(t, o.key, o.def).coerceIn(0, o.options.size - 1)
            }
        }
        return HudCfg(bools, ints, hudTheme(), hudScale(t) / 100f, hudOpacity(t) / 100f, hudBool(t, "blur", true))
    }

    /** 每个 HUD 通用的大小 / 不透明度（百分比） */
    fun hudScale(t: HudType) = sp.getInt("hud.${t.id}.scale", 100).coerceIn(50, 200)
    fun hudOpacity(t: HudType) = sp.getInt("hud.${t.id}.opacity", 100).coerceIn(20, 100)

    // ---- HUD 布局方案：把所有 HUD 的开关 / 选项 / 大小 / 位置 / 配色存成一份快照 ----
    /** 每次载入方案 / 重置 HUD 后自增，OverlayService 据此把位置同步到正在显示的 HUD */
    @Volatile var layoutVersion = 0
        private set

    private fun isHudKey(k: String) = k.startsWith("hud.") || k.startsWith("hud2.")

    fun hasPreset(n: Int) = sp.contains("preset.$n")

    fun savePreset(n: Int) {
        val o = org.json.JSONObject()
        for ((k, v) in sp.all) {
            if (!isHudKey(k)) continue
            when (v) {
                is Boolean -> o.put(k, v)
                is Int -> o.put(k, v)
                is Float -> o.put(k, v.toDouble())
                is String -> o.put(k, v)
            }
        }
        sp.edit().putString("preset.$n", o.toString()).apply()
    }

    fun loadPreset(n: Int): Boolean {
        val raw = sp.getString("preset.$n", null) ?: return false
        val o = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return false
        val e = sp.edit()
        sp.all.keys.filter { isHudKey(it) }.forEach { e.remove(it) }
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (val v = o.get(k)) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Double -> e.putFloat(k, v.toFloat())
                is String -> e.putString(k, v)
            }
        }
        e.apply()
        layoutVersion++
        changed()
        return true
    }

    fun clearPreset(n: Int) { sp.edit().remove("preset.$n").apply() }

    /** 把某个 HUD 的选项 / 大小 / 位置恢复默认（开关状态保留） */
    fun resetHud(t: HudType) {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("hud.${t.id}.") && it != "hud.${t.id}.on" }.forEach { e.remove(it) }
        e.remove("hud2.${t.id}.x").remove("hud2.${t.id}.y")
        e.apply()
        layoutVersion++
        changed()
    }
    fun anyHudOn() = HudType.values().any { hudOn(it) }

    // ---- 悬浮球 / 悬浮快捷键 / HUD 位置 ----
    fun posX(k: String, def: Int) = sp.getInt("$k.x", def)
    fun posY(k: String, def: Int) = sp.getInt("$k.y", def)
    fun setPos(k: String, x: Int, y: Int) { sp.edit().putInt("$k.x", x).putInt("$k.y", y).apply() }

    init { applyAppearance() }
}
