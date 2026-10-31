package com.rlin.inject

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.DisplayMetrics
import android.util.Log
import com.nemoclient.app.R

/**
 * 给 UI 代码用的 Context：外壳是游戏 Activity（窗口、输入法、SharedPreferences 都走它），
 * 但 getResources() 换成“模块自己的资源”——否则模块里的 R.drawable.* 会撞上游戏自己的资源 id。
 * 同时把屏幕尺寸修正成真实全屏大小，让 HUD 的坐标和游戏画面的像素一一对应。
 */
class ModuleContext(base: Context, private val res: Resources) : ContextWrapper(base) {
    private var th: Resources.Theme? = null

    override fun getResources(): Resources = res
    override fun getAssets(): AssetManager = res.assets
    override fun getTheme(): Resources.Theme {
        th?.let { return it }
        val t = res.newTheme()
        t.applyStyle(android.R.style.Theme_DeviceDefault_NoActionBar, true)
        th = t
        return t
    }

    companion object {
        fun modulePath(): String? {
            val s = RlinEntry::class.java.classLoader.toString()
            return Regex("module=([^,\\]]+)").find(s)?.groupValues?.get(1)
                ?: Regex("zip file \"([^\"]+\\.apk)\"").find(s)?.groupValues?.get(1)
        }

        private fun realMetrics(act: Activity): DisplayMetrics {
            val dm = DisplayMetrics()
            dm.setTo(act.resources.displayMetrics)
            runCatching {
                val b = act.windowManager.maximumWindowMetrics.bounds
                dm.widthPixels = b.width(); dm.heightPixels = b.height()
            }
            return dm
        }

        private fun works(r: Resources): Boolean = runCatching { r.getDrawable(R.drawable.ic_close, null); true }.getOrDefault(false)

        fun create(act: Activity): ModuleContext? {
            val apk = modulePath()
            if (apk == null) { Log.e("rlin", "module apk path not found in ${RlinEntry::class.java.classLoader}"); return null }
            val dm = realMetrics(act)
            var r: Resources? = null
            // 方案 A：公开 API，把模块 APK 当作一个“应用”取资源
            runCatching {
                val pm = act.packageManager
                val ai = pm.getPackageArchiveInfo(apk, 0)!!.applicationInfo!!
                ai.sourceDir = apk; ai.publicSourceDir = apk
                val rr = pm.getResourcesForApplication(ai)
                if (works(rr)) r = rr
            }.onFailure { Log.w("rlin", "resources plan A failed: $it") }
            // 方案 B：自己建 AssetManager 加载模块 APK（反射）
            if (r == null) runCatching {
                val am = AssetManager::class.java.getDeclaredConstructor().newInstance()
                val add = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
                if ((add.invoke(am, apk) as Int) != 0) {
                    @Suppress("DEPRECATION")
                    val rr = Resources(am, dm, act.resources.configuration)
                    if (works(rr)) r = rr
                }
            }.onFailure { Log.w("rlin", "resources plan B failed: $it") }
            val res = r ?: run { Log.e("rlin", "cannot load module resources from $apk"); return null }
            @Suppress("DEPRECATION")
            res.updateConfiguration(act.resources.configuration, dm)
            Log.i("rlin", "module resources ok: ${dm.widthPixels}x${dm.heightPixels} density=${dm.density} apk=$apk")
            return ModuleContext(act, res)
        }
    }
}
