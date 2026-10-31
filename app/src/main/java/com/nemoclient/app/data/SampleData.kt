package com.nemoclient.app.data

import com.nemoclient.app.data.Category.*

/** 示例假数据（来自设计稿），之后替换成真实模块列表即可 */
object SampleData {
    const val GUI_ID = "ClickGUI"
    const val KEY_BLUR = "界面模糊"
    const val KEY_SCALE = "界面缩放"
    const val KEY_THEME = "主题色"
    const val KEY_ALPHA = "界面透明度"
    const val KEY_LANG = "语言"
    const val KEY_MODE = "外观模式"
    const val KEY_HUD_MODE = "HUD 配色"
    const val KEY_COLOR = "自定义调色板"
    val themeNames = listOf("焦橙", "深棕", "灰绿", "自定义")

    private fun m(n: String, d: String, c: Category, on: Boolean = false, vararg s: Setting) =
        Module(n, n, d, c, on, s.toList())

    private fun sl(k: String, min: Float, max: Float, st: Float, def: Float) = Setting.Slider(k, min, max, st, def)
    private fun tg(k: String, def: Boolean, d: String? = null) = Setting.Toggle(k, def, d)
    private fun ch(k: String, def: Int, vararg o: String) = Setting.Choice(k, o.toList(), def)

    private val base: List<Module> = listOf(
        m("KillAura", "自动攻击周围的实体", COMBAT, true,
            sl("攻击范围", 3f, 6f, 0.1f, 3.2f), sl("攻击速度 CPS", 1f, 20f, 1f, 12f),
            ch("目标优先级", 0, "距离", "血量", "角度"), tg("穿透墙体", false)),
        m("AutoClicker", "模拟高频点击", COMBAT, true,
            sl("CPS 下限", 1f, 20f, 1f, 9f), sl("CPS 上限", 1f, 20f, 1f, 14f), tg("仅持剑时触发", true)),
        m("Criticals", "每次攻击强制暴击", COMBAT, false, ch("触发模式", 1, "跳跃", "数据包", "低跳")),
        m("AimAssist", "平滑辅助瞄准", COMBAT, true,
            sl("辅助力度", 0f, 100f, 5f, 40f), sl("作用角度", 10f, 180f, 5f, 60f), tg("随机抖动拟人", true)),
        m("Reach", "略微增加攻击距离", COMBAT, false, sl("额外距离", 0f, 1f, 0.05f, 0.3f)),
        m("AutoTotem", "低血自动放置图腾", COMBAT, false, sl("触发血量", 1f, 10f, 1f, 4f), tg("切换后提示", true)),

        m("Speed", "提升移动速度", MOVE, true, ch("模式", 0, "Strafe", "BHop", "低跳"), sl("速度倍率", 1f, 3f, 0.1f, 1.6f)),
        m("Fly", "允许飞行", MOVE, false, sl("飞行速度", 0.5f, 5f, 0.1f, 2f)),
        m("NoFall", "免除摔落伤害", MOVE, true, tg("仅高处生效", true)),
        m("Sprint", "自动疾跑", MOVE, true),
        m("Step", "自动走上台阶", MOVE, false, sl("步高", 0.5f, 2.5f, 0.5f, 1f)),
        m("Spider", "沿墙攀爬", MOVE),

        m("ESP", "透视显示实体", RENDER, true, ch("样式", 1, "方框", "发光", "描边"), tg("显示血量条", true)),
        m("FullBright", "全亮视野", RENDER, true),
        m("Nametags", "强化名牌显示", RENDER, false, sl("缩放", 0.5f, 2f, 0.1f, 1.2f)),
        m("Animations", "自定义挥剑动画", RENDER, true, ch("预设", 0, "1.7", "旋涡", "下滑")),
        m("Trajectories", "投掷物轨迹预测", RENDER),

        m("AutoArmor", "自动穿最优护甲", PLAYER),
        m("ChestStealer", "快速窃取箱子物品", PLAYER, false, sl("延迟 ms", 0f, 300f, 10f, 80f)),
        m("FastEat", "加速进食", PLAYER),
        m("AntiAFK", "防止挂机踢出", PLAYER, true),
        m("InvManager", "自动整理背包", PLAYER, false, tg("丢弃垃圾物品", true)),
        m("Blink", "假瞬移（延迟数据包）", PLAYER, false, sl("最大延迟 ms", 50f, 1000f, 50f, 200f)),

        Module(GUI_ID, "ClickGUI 管理", "调整界面外观与动效", OTHER, true, listOf(
            tg(KEY_BLUR, true, "关闭后变为纯色扁平"),
            sl(KEY_SCALE, 75f, 125f, 5f, 100f),
            sl(KEY_ALPHA, 60f, 100f, 5f, 100f),
            Setting.Choice(KEY_MODE, listOf("深色", "浅色"), 0),
            Setting.Choice(KEY_HUD_MODE, listOf("保持深色", "跟随外观"), 0),
            Setting.Choice(KEY_THEME, themeNames, 0),
            Setting.Color(KEY_COLOR, 0xFFB15F32.toInt()),
            Setting.Choice(KEY_LANG, listOf("简体中文", "English"), com.nemoclient.app.i18n.I18n.systemDefault().ordinal))),
        m("AntiBot", "过滤假人实体", OTHER, true),
        m("AutoGG", "结束自动发送 GG", OTHER, false, tg("自定义文案", false)),
        m("StreamerMode", "直播模式隐藏信息", OTHER),
        m("ConfigSync", "云端同步配置", OTHER, true, ch("同步频率", 2, "手动", "每小时", "实时")),
        m("KeyStrokes", "屏幕显示按键", OTHER),
        m("DiscordRPC", "Discord 状态展示", OTHER, true)
    )

    private val more: List<Module> = listOf(
        // 战斗
        m("Velocity", "减少受到的击退", COMBAT, true, sl("水平", 0f, 100f, 5f, 80f), sl("垂直", 0f, 100f, 5f, 100f)),
        m("TriggerBot", "准星对准目标时自动攻击", COMBAT, false, sl("延迟 ms", 0f, 300f, 10f, 60f), tg("仅持武器时", true)),
        m("Hitboxes", "扩大目标判定范围", COMBAT, false, sl("扩大量", 0f, 0.6f, 0.05f, 0.2f)),
        m("BowAimbot", "弓箭自动预判瞄准", COMBAT, false, ch("预测", 0, "线性", "加速度"), sl("预判强度", 0f, 100f, 5f, 50f)),
        m("AutoPotion", "自动使用药水", COMBAT, false, sl("触发血量", 1f, 10f, 1f, 6f), tg("优先速度药水", false)),
        m("WTap", "攻击后重置疾跑", COMBAT, false, ch("模式", 0, "标准", "数据包")),
        m("TargetStrafe", "围绕目标环绕移动", COMBAT, false, sl("半径", 1f, 5f, 0.1f, 2.5f)),
        m("FastBow", "快速拉弓", COMBAT, false, sl("拉弓刻数", 3f, 20f, 1f, 3f)),
        // 移动
        m("Jesus", "水面行走", MOVE, false, ch("模式", 0, "固定", "弹跳")),
        m("LongJump", "增加跳跃距离", MOVE, false, sl("强度", 1f, 5f, 0.1f, 2f)),
        m("HighJump", "增加跳跃高度", MOVE, false, sl("高度", 1f, 5f, 0.1f, 2.5f)),
        m("NoSlow", "使用物品时不减速", MOVE, true, tg("仅进食时", false)),
        m("Timer", "调整游戏速度", MOVE, false, sl("倍率", 0.5f, 3f, 0.1f, 1.2f)),
        m("SafeWalk", "边缘不会掉落", MOVE, false, tg("仅潜行时", false)),
        m("Glide", "缓慢滑翔下落", MOVE, false, sl("下落速度", 0f, 1f, 0.05f, 0.2f)),
        m("Parkour", "到达边缘自动起跳", MOVE),
        // 渲染
        m("Chams", "实体穿墙着色", RENDER, false, ch("样式", 0, "纯色", "描边")),
        m("Tracers", "向实体绘制连线", RENDER, false, sl("线宽", 1f, 5f, 0.5f, 2f), tg("仅玩家", true)),
        m("BlockESP", "高亮指定方块", RENDER, false, ch("目标", 0, "矿石", "箱子", "全部")),
        m("Freecam", "脱离身体的自由视角", RENDER, false, sl("速度", 0.5f, 5f, 0.1f, 1.5f)),
        m("NoHurtCam", "取消受伤抖动", RENDER, true),
        m("Zoom", "按住放大视野", RENDER, false, sl("倍率", 1.5f, 8f, 0.5f, 3f)),
        m("XRay", "透视地下方块", RENDER, false, sl("透明度", 0f, 100f, 5f, 30f)),
        m("Breadcrumbs", "显示移动轨迹", RENDER, false, sl("长度", 10f, 200f, 10f, 60f)),
        m("ItemESP", "高亮掉落物", RENDER),
        // 玩家
        m("AutoTool", "自动切换最优工具", PLAYER, true),
        m("AutoSword", "自动切换最佳武器", PLAYER),
        m("Scaffold", "自动在脚下放置方块", PLAYER, false, ch("模式", 0, "普通", "快速"), sl("延迟 ms", 0f, 200f, 10f, 20f), tg("自动切换方块", true)),
        m("Nuker", "批量破坏周围方块", PLAYER, false, sl("范围", 1f, 6f, 1f, 3f)),
        m("FastPlace", "加速放置方块", PLAYER, false, sl("间隔 刻", 0f, 4f, 1f, 0f)),
        m("AutoRespawn", "死亡后自动重生", PLAYER, true),
        m("AutoFish", "自动钓鱼", PLAYER, false, sl("收杆延迟 ms", 0f, 1000f, 50f, 300f)),
        m("InvMove", "打开背包时仍可移动", PLAYER),
        // 其他
        m("Friends", "好友不会被攻击", OTHER, false, tg("中键添加好友", true)),
        m("Macro", "自定义快捷指令", OTHER),
        m("ChatFilter", "过滤聊天内容", OTHER, false, tg("屏蔽广告", true)),
        m("Panic", "一键关闭所有模块", OTHER)
    )

    val modules: List<Module> = base + more
}
