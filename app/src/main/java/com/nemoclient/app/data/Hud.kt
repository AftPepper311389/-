package com.nemoclient.app.data

const val HUD_VERSION = "v0.6"

/** HUD 的可调选项：开关 / 选择。key 用于持久化（hud.<id>.<key>） */
sealed class HudOpt(val key: String, val label: String) {
    class Toggle(key: String, label: String, val def: Boolean) : HudOpt(key, label)
    class Choice(key: String, label: String, val options: List<String>, val def: Int) : HudOpt(key, label)
}

/**
 * 游戏画面上的 HUD 类型。每种 HUD 有一个总开关 + 若干选项（opts）。
 * 新增 HUD：在这里加一项，再在 ui/HudViews.kt 写对应的视图（继承 HudCanvasView），
 * 最后在 OverlayService.addHud 里接上。
 */
enum class HudType(
    val id: String,
    val label: String,
    val desc: String,
    val opts: List<HudOpt>
) {
    WATERMARK("watermark", "水印", "显示客户端名称、账号与状态", listOf(
        HudOpt.Choice("style", "样式", listOf("胶囊", "Neverlose", "Pharos", "Logo"), 0),
        HudOpt.Toggle("demo", "演示数据", true),
        HudOpt.Choice("glow", "辉光", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Choice("shadow", "边缘阴影", listOf("关", "弱", "中", "强"), 0)
    )),
    MODULE_LIST("modulelist", "模块列表", "显示已启用的模块", listOf(
        HudOpt.Toggle("bg", "背景", true),
        HudOpt.Toggle("suffix", "显示后缀（当前模式）", true),
        HudOpt.Toggle("line", "侧边线", false),
        HudOpt.Toggle("lower", "全部小写", false),
        HudOpt.Choice("style", "样式", listOf("阶梯", "强调条", "药丸", "文字"), 0),
        HudOpt.Choice("anim", "动画", listOf("滑动", "淡入", "滑动+淡入", "缩放"), 2),
        HudOpt.Choice("color", "文字颜色", listOf("主题渐变", "强调色", "白色"), 0),
        HudOpt.Choice("sort", "排序", listOf("最长优先", "最短优先", "A-Z"), 0),
        HudOpt.Choice("glow", "辉光", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Choice("shadow", "边缘阴影", listOf("关", "弱", "中", "强"), 0)
    )),
    TARGET("target", "目标信息", "显示战斗对象的名字与生命", listOf(
        HudOpt.Choice("style", "样式", listOf("Round", "Rise", "Moon", "Naven", "Simple"), 0),
        HudOpt.Choice("readout", "生命读数（Naven / Simple）", listOf("数字", "心形"), 0),
        HudOpt.Toggle("demo", "演示数据", true),
        HudOpt.Choice("glow", "辉光", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Choice("shadow", "边缘阴影", listOf("关", "弱", "中", "强"), 0)
    )),
    POTION("potion", "药水效果", "显示身上的药水效果、等级与剩余时间", listOf(
        HudOpt.Choice("style", "样式", listOf("环形", "条形"), 0),
        HudOpt.Choice("sort", "排序", listOf("剩余时间", "名称", "等级"), 0),
        HudOpt.Toggle("icon", "图标", true),
        HudOpt.Toggle("duration", "剩余时间", true),
        HudOpt.Toggle("level", "等级（罗马数字）", true),
        HudOpt.Toggle("tint", "药水颜色", true),
        HudOpt.Toggle("warn", "快结束时变红", true),
        HudOpt.Toggle("ambient", "隐藏信标效果", false),
        HudOpt.Choice("glow", "辉光", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Choice("shadow", "边缘阴影", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Toggle("demo", "演示数据", true)
    )),
    FPS("fps", "帧率", "帧率数字与最近一段时间的柱状图", listOf(
        HudOpt.Toggle("graph", "柱状图", true),
        HudOpt.Choice("window", "时长", listOf("10 秒", "20 秒", "40 秒"), 1),
        HudOpt.Choice("glow", "辉光", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Choice("shadow", "边缘阴影", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Toggle("demo", "演示数据", true)
    )),
    SPEED("speed", "速度计", "移动速度数字与最近一段时间的柱状图", listOf(
        HudOpt.Toggle("graph", "柱状图", true),
        HudOpt.Choice("unit", "速度单位", listOf("格/秒", "千米/时"), 0),
        HudOpt.Choice("window", "时长", listOf("10 秒", "20 秒", "40 秒"), 1),
        HudOpt.Choice("glow", "辉光", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Choice("shadow", "边缘阴影", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Toggle("demo", "演示数据", true)
    )),
    COORDS("coords", "坐标", "显示自己的 XYZ 坐标，可选朝向与下界换算", listOf(
        HudOpt.Choice("style", "样式", listOf("卡片", "单行"), 0),
        HudOpt.Choice("decimals", "小数位", listOf("0", "1", "2"), 1),
        HudOpt.Toggle("facing", "朝向", true),
        HudOpt.Toggle("nether", "下界 / 主世界换算（÷8）", false),
        HudOpt.Choice("glow", "辉光", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Choice("shadow", "边缘阴影", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Toggle("demo", "演示数据", true)
    )),
    BLOCKS("blocks", "方块计数", "搭路时显示方块数量、进度条和移动速度（参考 nilore 的 ScaffoldHud）", listOf(
        HudOpt.Toggle("speed", "移动速度", true),
        HudOpt.Choice("unit", "速度单位", listOf("格/秒", "千米/时"), 0),
        HudOpt.Choice("full", "进度满格", listOf("64", "128", "256"), 0),
        HudOpt.Toggle("alert", "方块少时变红（≤ 32）", true),
        HudOpt.Choice("glow", "辉光", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Choice("shadow", "边缘阴影", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Toggle("demo", "演示数据", true)
    )),
    RADAR("radar", "雷达", "圆角方块雷达：显示周围实体方位，最近的敌人高亮", listOf(
        HudOpt.Choice("size", "大小", listOf("小", "中", "大"), 1),
        HudOpt.Choice("range", "探测范围", listOf("20 格", "40 格", "80 格"), 1),
        HudOpt.Choice("orient", "朝向", listOf("视线向上", "正北向上"), 0),
        HudOpt.Toggle("fov", "视野扇形", true),
        HudOpt.Choice("glow", "辉光", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Choice("shadow", "边缘阴影", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Toggle("demo", "演示数据", true)
    )),
    ISLAND("island", "灵动岛", "常驻名称与状态；模块开关时展开提示，编辑 HUD 时展开设置", listOf(
        HudOpt.Choice("style", "样式", listOf("默认", "终末地", "材质"), 0),
        HudOpt.Choice("efwidth", "终末地宽度", listOf("紧凑 200", "标准 230", "宽松 260"), 0),
        HudOpt.Toggle("notify", "开关提示", true),
        HudOpt.Toggle("off", "同时提示关闭", true),
        HudOpt.Toggle("blocks", "搭路方块计数（BlocksFeed）", true),
        HudOpt.Toggle("music", "显示音乐", true),
        HudOpt.Choice("glow", "辉光", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Choice("shadow", "边缘阴影", listOf("关", "弱", "中", "强"), 0)
    )),
    NOTIFY("notify", "开关提示", "模块开关时从右下角弹出提示", listOf(
        HudOpt.Choice("style", "样式", listOf("Southside", "Simple", "Akarin", "Naven", "Material"), 0),
        HudOpt.Toggle("glass", "毛玻璃背景", true),
        HudOpt.Toggle("bar", "进度条", true),
        HudOpt.Choice("hold", "停留时间", listOf("短", "中", "长"), 0),
        HudOpt.Toggle("off", "同时提示关闭", true),
        HudOpt.Choice("glow", "辉光", listOf("关", "弱", "中", "强"), 0),
        HudOpt.Choice("shadow", "边缘阴影", listOf("关", "弱", "中", "强"), 0)
    ))
}

/** 某个 HUD 当前的选项值快照（由 ModuleRepo.hudCfg 生成，视图只读） */
class HudCfg(
    private val bools: Map<String, Boolean>,
    private val ints: Map<String, Int>,
    val theme: Int,
    /** 整体缩放（0.5 ~ 2.0） */
    val scale: Float = 1f,
    /** 整体不透明度（0.2 ~ 1.0） */
    val opacity: Float = 1f,
    /** 背景实时模糊（还要看 ClickGUI 里的“界面模糊”总开关） */
    val blur: Boolean = true
) {
    fun b(key: String): Boolean = bools[key] ?: false
    fun i(key: String): Int = ints[key] ?: 0

    companion object {
        val EMPTY = HudCfg(emptyMap(), emptyMap(), 0)
    }
}

/** 模块列表的一行：名字 + 可选后缀（当前模式） */
data class HudModuleRow(val name: String, val suffix: String?)

/** 战斗对象的信息（health / maxHealth 单位：点，distance 单位：米） */
data class TargetInfo(
    val name: String,
    val health: Float,
    val maxHealth: Float,
    val distance: Float,
    /** 头像（玩家皮肤的头部，建议 8x8 或其整数倍的位图）；null 时头像位留空 */
    val avatar: android.graphics.Bitmap? = null
)

/**
 * 目标信息的数据入口。接入真实系统：在你的战斗逻辑里
 * 选中目标 / 目标血量变化时调用 TargetFeed.push(TargetInfo(...))，失去目标时 push(null)。
 * HUD 会自动淡入 / 淡出、血条带掉血残影、受伤时头像弹一下。可在任意线程调用。
 */
object TargetFeed {
    @Volatile var current: TargetInfo? = null
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun push(t: TargetInfo?) { current = t; listeners.forEach { it() } }
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
}

/**
 * 水印用的状态信息。接入真实系统：定时调用 HudInfo.push(fps, pingMs)；
 * 没有的项传 -1，水印里就不显示。user 是水印里显示的账号名（由服务自动同步为当前昵称）。
 */
object HudInfo {
    @Volatile var fps: Int = -1
    @Volatile var ping: Int = -1
    @Volatile var user: String = "player"

    fun push(fps: Int, ping: Int) { this.fps = fps; this.ping = ping }
}

/**
 * 移动速度数据入口（格/秒）。二选一：
 *  - SpeedFeed.pushPosition(x, y, z)：每 tick 或每帧给玩家坐标，这里按水平距离 / 时间差算速度并平滑；
 *  - SpeedFeed.pushSpeed(bps)：自己算好直接给。可在任意线程调用。负数 = 没有数据。
 */
/**
 * 自身坐标数据入口：PosFeed.push(x, y, z, yaw)，yaw 用 Minecraft 的约定（0 = 南 +Z，90 = 西 −X，180 = 北，270 = 东），
 * 没有朝向就给 NaN。SpeedFeed.pushPosition 也会顺手更新这里的坐标。可在任意线程调用。x 为 NaN = 没有数据。
 */
object PosFeed {
    @Volatile var x = Double.NaN; private set
    @Volatile var y = Double.NaN; private set
    @Volatile var z = Double.NaN; private set
    @Volatile var yaw = Double.NaN; private set

    fun push(x: Double, y: Double, z: Double, yaw: Double = Double.NaN) {
        this.x = x; this.y = y; this.z = z
        if (!yaw.isNaN()) this.yaw = yaw
    }
}

/**
 * 搭路方块数据入口：Scaffold 运行时每 tick / 每秒调用 BlocksFeed.push(背包里可用方块总数)，停止时调用 clear()（或停止推送 1.5 秒自动消失）。
 * 灵动岛会把它显示成“Blocks / Scaffold / 数量 / 进度（当前 ÷ 本轮峰值）”，数量 ≤ LOW 时变红。可在任意线程调用。
 */
/** 雷达上的一个目标：相对自己的世界坐标偏移（格），friend = 友方 / 好友 */
data class RadarTarget(val dx: Float, val dz: Float, val friend: Boolean = false)

/**
 * 雷达数据入口：每 tick 或每 100ms 调用 RadarFeed.push(周围实体相对自己的偏移列表)；朝向来自 PosFeed.push(..., yaw)。
 * 停止推送 1.5 秒后雷达上的目标清空；可在任意线程调用。
 */
object RadarFeed {
    @Volatile var list: List<RadarTarget> = emptyList(); private set
    @Volatile var stamp = 0L; private set
    fun push(l: List<RadarTarget>) { list = l; stamp = android.os.SystemClock.uptimeMillis() }
    fun clear() { list = emptyList() }
}

object BlocksFeed {
    const val LOW = 32
    @Volatile var count = -1; private set
    @Volatile var stamp = 0L; private set

    fun push(n: Int) { count = n; stamp = android.os.SystemClock.uptimeMillis() }
    fun clear() { count = -1 }
}

object SpeedFeed {
    @Volatile var bps: Float = -1f
        private set
    private var lx = 0.0
    private var lz = 0.0
    private var lt = 0L
    private val recent = ArrayDeque<Float>()

    fun pushSpeed(v: Float) { bps = v }

    @Synchronized
    fun pushPosition(x: Double, y: Double, z: Double) {
        PosFeed.push(x, y, z)
        val now = System.nanoTime()
        if (lt != 0L) {
            val dt = (now - lt) / 1e9
            if (dt in 0.005..1.0) {
                val dist = Math.hypot(x - lx, z - lz)
                val v = (dist / dt).toFloat().coerceAtMost(60f)
                if (!v.isNaN() && !v.isInfinite()) {
                    recent.addLast(v); if (recent.size > 5) recent.removeFirst()
                    bps = recent.average().toFloat()
                }
            } else if (dt > 1.0) recent.clear()
        }
        lx = x; lz = z; lt = now
    }
}

/** 一个药水效果（seconds 为剩余秒数；color 为药水自己的颜色 0xRRGGBB） */
data class PotionInfo(val name: String, val amplifier: Int, val seconds: Int, val color: Int, val ambient: Boolean = false)

/**
 * 药水效果的数据入口。接入真实系统：每次效果列表变化 / 每秒调用 PotionFeed.push(list)；
 * 列表为空时，如果该 HUD 开着“演示数据”就显示演示内容。可在任意线程调用。
 */
object PotionFeed {
    @Volatile var list: List<PotionInfo> = emptyList()
        private set

    fun push(l: List<PotionInfo>) { list = l }
}

/** 通知类型 */
enum class NotifyKind { INFO, SUCCESS, WARNING, ERROR }

/**
 * 通用通知入口（“开关提示”卡片和灵动岛都会显示）。接真实系统：
 * 比如 Scaffold 方块快用完了 → HudNotify.push("Scaffold", "方块不足 12", NotifyKind.WARNING)。可在任意线程调用。
 */
object HudNotify {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String, String, NotifyKind) -> Unit>()
    fun push(title: String, message: String, kind: NotifyKind = NotifyKind.INFO) { listeners.forEach { it(title, message, kind) } }
    fun addListener(l: (String, String, NotifyKind) -> Unit) { listeners.add(l) }
    fun removeListener(l: (String, String, NotifyKind) -> Unit) { listeners.remove(l) }
}
