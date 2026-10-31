package com.nemoclient.app.data

import com.nemoclient.app.R

enum class Category(val label: String, val iconRes: Int) {
    COMBAT("战斗", R.drawable.ic_sword),
    MOVE("移动", R.drawable.ic_move),
    RENDER("渲染", R.drawable.ic_eye),
    PLAYER("玩家", R.drawable.ic_bag),
    OTHER("其他", R.drawable.ic_gear)
}

/** 模块的可调设置项：滑条 / 开关 / 分段选择 */
sealed class Setting(val key: String) {
    class Slider(key: String, val min: Float, val max: Float, val step: Float, val def: Float) : Setting(key)
    class Toggle(key: String, val def: Boolean, val desc: String? = null) : Setting(key)
    class Choice(key: String, val options: List<String>, val def: Int) : Setting(key)
    /** 调色板：值是 ARGB Int（PS 风格取色器） */
    class Color(key: String, val def: Int) : Setting(key)
}

class Module(
    val id: String,
    val name: String,
    val desc: String,
    val category: Category,
    val defaultOn: Boolean = false,
    val settings: List<Setting> = emptyList()
)

/**
 * 预留的真实模块系统接口。
 * 以后把你的模块逻辑接进来：实现这个接口，然后赋给 ModuleRepo.backend 即可。
 */
interface ModuleBackend {
    fun onToggle(module: Module, enabled: Boolean)
    fun onSettingChange(module: Module, key: String, value: Any)
}

/** 默认实现：只打日志 */
class LogBackend : ModuleBackend {
    override fun onToggle(module: Module, enabled: Boolean) {
        android.util.Log.d("rlinclient", "toggle ${module.id} -> $enabled")
    }

    override fun onSettingChange(module: Module, key: String, value: Any) {
        android.util.Log.d("rlinclient", "setting ${module.id}.$key = $value")
    }
}
