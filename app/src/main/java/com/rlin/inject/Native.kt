package com.rlin.inject

import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/** 原生层接口（librlinhook.so）。所有方法线程安全，可在任意线程调用。 */
object Native {
    @Volatile var loaded = false
        private set

    /**
     * 加载原生库。先走正常的 System.loadLibrary；失败的话（比如框架没把 .so 放进模块的库路径），
     * 就从模块 APK 里把 .so 解出来放到游戏的 code_cache 再 System.load。
     */
    fun load(dataDir: String?): Boolean {
        if (loaded) return true
        try {
            System.loadLibrary("rlinhook")
            loaded = true
            return true
        } catch (e: Throwable) {
            Log.w("rlin", "loadLibrary failed, trying extract fallback: ${e.message?.take(200)}")
        }
        return try {
            val apk = Regex("module=([^,\\]]+)").find(Native::class.java.classLoader.toString())?.groupValues?.get(1)
            if (apk == null || dataDir == null) { Log.e("rlin", "fallback: no apk path ($apk) or dataDir"); return false }
            val out = File(dataDir, "code_cache/librlinhook.so")
            out.parentFile?.mkdirs()
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry("lib/arm64-v8a/librlinhook.so")
                if (entry == null) { Log.e("rlin", "fallback: librlinhook.so is not inside $apk"); return false }
                zip.getInputStream(entry).use { ins -> out.outputStream().use { ins.copyTo(it) } }
            }
            System.load(out.absolutePath)
            loaded = true
            Log.i("rlin", "loaded via extract fallback: ${out.absolutePath}")
            true
        } catch (e: Throwable) {
            Log.e("rlin", "fallback load failed", e)
            false
        }
    }

    /** 初始化 ShadowHook 并钩住 eglSwapBuffers；成功返回 true */
    external fun nativeInit(): Boolean

    /** 演示：在屏幕上方画一块毛玻璃矩形，用来验证钩子 + 模糊都正常 */
    external fun nativeSetDemo(on: Boolean)

    /**
     * 设置要做模糊的区域。数组每 6 个 float 一组：x, y, w, h, radius, tint（着色强度 0~1）（屏幕像素，原点在左上角）。
     * 传空数组 = 不画。
     */
    external fun nativeSetRects(rects: FloatArray)

    /** 模糊强度：levels = 降采样次数（1~4），offset = 采样偏移（1~4，越大越糊） */
    external fun nativeSetBlur(levels: Int, offset: Float)

    /** 浅色主题：模糊着色换成暖白 */
    external fun nativeSetLight(light: Boolean)

    /** 最近一帧的帧率（每秒 eglSwapBuffers 次数），拿来给水印用 */
    external fun nativeFps(): Int
}
