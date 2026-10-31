package com.rlin.inject

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed 入口（HKP / LSPatch 打补丁后，在游戏进程里最早被调用）。
 * 1) 加载原生库，钩 eglSwapBuffers（实时模糊）
 * 2) 钩 Activity.onResume：游戏主 Activity 出现后，启动游戏内 UI（悬浮球 / HUD / ClickGUI）
 * 游戏是多进程的，只有主进程（进程名 == 包名）才有画面，其余直接跳过。
 */
class RlinEntry : IXposedHookLoadPackage {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var host: InGameHost? = null
    private var hostAct: Activity? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!lpparam.isFirstApplication) return
        Log.i("rlin", "handleLoadPackage ${lpparam.packageName} process=${lpparam.processName}")
        if (lpparam.processName != lpparam.packageName) return // 子进程不渲染
        if (!Native.load(lpparam.appInfo?.dataDir)) return
        val ok = Native.nativeInit()
        Log.i("rlin", "nativeInit = $ok")
        if (!ok) return
        hookActivity()
        hookBackKey()
    }

    private fun isGameActivity(a: Activity): Boolean {
        val n = a.javaClass.name
        if (n.contains("minecraftpe", ignoreCase = true)) return true
        // 国际版基岩 com.mojang.minecraftpe：主 Activity 被壳/加固改名时，按包名 + GameActivity 兜底
        return a.packageName == "com.mojang.minecraftpe" &&
            (n.endsWith("MainActivity") || generateSequence<Class<*>>(a.javaClass) { it.superclass }.any { it.name.contains("GameActivity") })
    }

    private fun hookActivity() {
        try {
            XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject as Activity
                    Log.i("rlin", "onResume ${a.javaClass.name}")
                    if (!isGameActivity(a) || hostAct === a) return
                    hostAct = a
                    startWhenReady(a, 0)
                }
            })
            XposedHelpers.findAndHookMethod(Activity::class.java, "onDestroy", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.thisObject === hostAct) {
                        runCatching { host?.stop() }
                        host = null; hostAct = null
                    }
                }
            })
            Log.i("rlin", "activity hooks installed")
        } catch (t: Throwable) {
            Log.e("rlin", "hook Activity failed", t)
        }
    }

    /** 等游戏自己的窗口挂上去之后再加我们的窗口（同一个 token 下，后加的在上面） */
    private fun startWhenReady(a: Activity, tries: Int) {
        if (hostAct !== a) return
        val ready = a.window?.decorView?.isAttachedToWindow == true && a.window.decorView.width > 0
        if (!ready && tries < 40) { main.postDelayed({ startWhenReady(a, tries + 1) }, 250); return }
        main.postDelayed({
            if (hostAct !== a || host != null) return@postDelayed
            try {
                val mc = ModuleContext.create(a) ?: return@postDelayed
                val h = InGameHost(a, mc)
                h.start()
                host = h
            } catch (t: Throwable) {
                Log.e("rlin", "start InGameHost failed", t)
            }
        }, 600)
    }

    /** 编辑 HUD 位置时游戏窗口保持焦点，返回键由这里接管用来退出编辑 */
    private fun hookBackKey() {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.internal.policy.DecorView", null, "dispatchKeyEvent", KeyEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val e = param.args[0] as? KeyEvent ?: return
                        if (e.keyCode != KeyEvent.KEYCODE_BACK) return
                        val h = host ?: return
                        if (!h.wantsBack()) return
                        if (e.action == KeyEvent.ACTION_UP) h.onBack()
                        param.result = true
                    }
                })
        } catch (t: Throwable) {
            Log.e("rlin", "hook back key failed", t)
        }
    }
}
