package com.rlin.inject

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import com.nemoclient.app.i18n.tr
import com.nemoclient.app.ui.Palette
import com.nemoclient.app.ui.withAlpha
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 桌面入口 = 模块的“独立 App 版”。
 *
 * 打开就是和注入游戏后完全一样的体验：出现悬浮球 → 点悬浮球呼出 ClickGUI → 点面板外 / 返回键关闭，
 * HUD 直接显示、可进编辑模式拖动对齐、悬浮快捷键照常。没有额外的 demo 按钮 —— 所有交互都走 InGameHost
 * 的游戏内逻辑，只是 demo 模式下没有游戏画面：
 *   · 毛玻璃：InGameHost（demo 模式）每帧把要模糊的矩形推给 BlurLayer，这里贴一张预模糊好的背景图
 *     + 着色，和原生层画在游戏画面上同一个语义；
 *   · 背景：自带渐变光斑；右下角“更换背景图片”按钮可选相册图（存 filesDir/demo_bg.jpg），
 *     选的图同样进毛玻璃链路 —— 面板后面糊的就是这张图；长按按钮恢复默认；
 *   · 帧率：水印显示一个游走的演示值。
 * Demo 与游戏内是两个进程、两套存储：这里调样式不会影响注入游戏；注入游戏照旧走 RlinEntry / InGameHost。
 */
class DemoActivity : Activity() {
    private lateinit var host: InGameHost
    private lateinit var blurLayer: BlurLayer
    private lateinit var bgImg: ImageView
    private var sw = 0
    private var sh = 0
    private val bgFile get() = File(filesDir, "demo_bg.jpg")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels
        sh = dm.heightPixels

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF0D1118.toInt()) }
        setContentView(root)
        bgImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(bgImg, ViewGroup.LayoutParams(-1, -1))
        blurLayer = BlurLayer(this)
        root.addView(blurLayer, ViewGroup.LayoutParams(-1, -1))
        loadBackground { src, blurred ->
            bgImg.setImageBitmap(src)
            blurLayer.setBlurred(blurred)
        }

        // 唯一的 demo 专属按钮：更换背景图片（长按 = 恢复默认光斑背景）
        val dp = resources.displayMetrics.density
        root.addView(BgButton(this), FrameLayout.LayoutParams(
            (44 * dp).toInt(), (44 * dp).toInt(),
            android.view.Gravity.BOTTOM or android.view.Gravity.END
        ).apply { rightMargin = (16 * dp).toInt(); bottomMargin = (20 * dp).toInt() })

        // 宿主跑在 demo 模式：悬浮球 / GUI / HUD / 编辑模式与游戏内同一套代码
        host = InGameHost(this, this, demo = true)
        host.demoBlurSink = { rects -> blurLayer.setRects(rects) }
        host.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** 返回键交给宿主：先退 GUI 子页面 / 编辑模式，都不在时退出 App */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!host.onBack()) finish()
    }

    override fun onDestroy() {
        host.stop()
        super.onDestroy()
    }

    // ---------------- 更换背景 ----------------

    private companion object { const val REQ_PICK_BG = 1001 }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_BG || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            runCatching {
                contentResolver.openInputStream(uri)?.use { ins ->
                    bgFile.outputStream().use { ins.copyTo(it) }
                }
            }
            loadBackground { src, blurred ->
                bgImg.setImageBitmap(src)
                blurLayer.setBlurred(blurred)
            }
        }.start()
    }

    /** 右下角的“更换背景”圆钮：相框 + 山 + 太阳图标，样式与悬浮快捷键同一套玻璃观感 */
    private inner class BgButton(ctx: Context) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val bgP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val iconP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

        init {
            contentDescription = tr("更换背景图片")
            isClickable = true
            setOnClickListener {
                runCatching {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" },
                        REQ_PICK_BG
                    )
                }
            }
            setOnLongClickListener { // 恢复默认光斑背景
                bgFile.delete()
                loadBackground { src, blurred ->
                    bgImg.setImageBitmap(src)
                    blurLayer.setBlurred(blurred)
                }
                Toast.makeText(ctx, tr("已恢复默认背景"), Toast.LENGTH_SHORT).show()
                true
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
            }
            return super.onTouchEvent(e)
        }

        override fun onDraw(c: Canvas) {
            val s = width.toFloat()
            val pressed = isPressed || scaleX < 0.95f
            bgP.color = withAlpha(Palette.inkBlack, if (pressed) 0.78f else 0.62f)
            c.drawCircle(s / 2f, s / 2f, s / 2f - d, bgP)
            lineP.color = Palette.line; lineP.strokeWidth = d
            c.drawCircle(s / 2f, s / 2f, s / 2f - d - lineP.strokeWidth / 2f, lineP)
            // 图标：相框 + 山 + 太阳
            iconP.color = Palette.ink; iconP.strokeWidth = 1.6f * d
            val f = RectF(s * 0.24f, s * 0.30f, s * 0.76f, s * 0.70f)
            c.drawRoundRect(f, 3.5f * d, 3.5f * d, iconP)
            val path = Path().apply {
                moveTo(f.left + 1.5f * d, f.bottom - 1.5f * d)
                lineTo(f.centerX() - 1.2f * d, f.top + (f.bottom - f.top) * 0.42f)
                lineTo(f.centerX() + 2.2f * d, f.bottom - 1.5f * d)
                moveTo(f.centerX() + 0.4f * d, f.bottom - 1.5f * d)
                lineTo(f.right - 1.5f * d, f.top + (f.bottom - f.top) * 0.62f)
            }
            c.drawPath(path, iconP)
            c.drawCircle(s * 0.665f, s * 0.395f, 1.9f * d, iconP)
        }
    }

    // ---------------- 背景 ----------------

    private fun loadBackground(done: (Bitmap, Bitmap) -> Unit) {
        Thread {
            val src: Bitmap = runCatching {
                if (bgFile.exists()) decodeScaled(bgFile.absolutePath, max(sw, sh)) else null
            }.getOrNull() ?: defaultBackground(sw / 3, sh / 3)
            val smallW = max(1, 96 * src.width / max(src.width, src.height))
            val smallH = max(1, 96 * src.height / max(src.width, src.height))
            val blurred = boxBlur(Bitmap.createScaledBitmap(src, smallW, smallH, true), 4)
            runOnUiThread { done(src, blurred) }
        }.start()
    }

    private fun decodeScaled(path: String, target: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (max(o.outWidth, o.outHeight) / s > target * 2) s *= 2
        val b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
        val scale = target.toFloat() / max(b.width, b.height)
        return if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
    }

    /** 自带背景：暗底上几团渐变光斑，够模糊后有层次感 */
    private fun defaultBackground(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val d = w / 360f
        c.drawColor(0xFF0D1118.toInt())
        val blobs = listOf(
            Triple(0.22f, 0.24f, 0x66B15F32),
            Triple(0.78f, 0.16f, 0x553A6EA5),
            Triple(0.62f, 0.68f, 0x4D8A5A44),
            Triple(0.18f, 0.80f, 0x44554E6E)
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((fx, fy, col) in blobs) {
            val r = min(w, h) * (0.45f + 0.25f * fx)
            p.shader = RadialGradient(w * fx, h * fy, r, intArrayOf(col, 0x00000000), null, Shader.TileMode.CLAMP)
            c.drawCircle(w * fx, h * fy, r, p)
        }
        p.shader = LinearGradient(0f, h.toFloat(), 0f, 0f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.35f, p)
        return b
    }

    /** 小图盒式模糊：迭代 4 次偏移采样，糊到看不出形状（背景本来就只是氛围） */
    private fun boxBlur(src: Bitmap, times: Int): Bitmap {
        var b = src
        val w = b.width
        val h = b.height
        val conf = b.config ?: Bitmap.Config.ARGB_8888
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var cur = px.copyOf()
        var tmp = IntArray(w * h)
        val offsets = intArrayOf(1, 2, 3, 4)
        repeat(times) { t ->
            val o = offsets[t]
            for (y in 0 until h) for (x in 0 until w) {
                var ar = 0; var ag = 0; var ab = 0; var n = 0
                for (dy in -o..o) for (dx in -o..o) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    val yy = (y + dy).coerceIn(0, h - 1)
                    val c = cur[yy * w + xx]
                    ar += (c shr 16) and 0xFF; ag += (c shr 8) and 0xFF; ab += c and 0xFF; n++
                }
                tmp[y * w + x] = 0xFF000000.toInt() or ((ar / n) shl 16) or ((ag / n) shl 8) or (ab / n)
            }
            val swap = cur; cur = tmp; tmp = swap
        }
        b = Bitmap.createBitmap(cur, w, h, conf)
        return b
    }

    // ---------------- 毛玻璃层 ----------------

    /**
     * demo 版“原生模糊”：InGameHost（demo 模式）每帧把要模糊的矩形推过来（和原生层同一套坐标语义：
     * 每 6 个 float 一组 x,y,w,h,radius,tintAlpha，屏幕像素，原点左上），
     * 这里把预模糊好的背景图在矩形处裁剪贴出来再叠着色，HUD / GUI 的半透明底板压上去就是毛玻璃。
     */
    private inner class BlurLayer(ctx: Activity) : View(ctx) {
        private var blurred: Bitmap? = null
        private var rects: FloatArray = FloatArray(0)
        private val m = Matrix()
        private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
        private val tint = Paint()
        private val path = Path()
        private val rf = RectF()

        fun setBlurred(b: Bitmap) {
            blurred = b
            val s = max(sw.toFloat() / b.width, sh.toFloat() / b.height)
            m.setScale(s, s)
            m.postTranslate((sw - b.width * s) / 2f, (sh - b.height * s) / 2f)
            invalidate()
        }

        /** 与原生 nativeSetRects 同语义；空数组 = 不画（模糊开关关闭 / 没有界面打开） */
        fun setRects(arr: FloatArray) {
            rects = arr
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val b = blurred ?: return
            var i = 0
            while (i + 5 < rects.size) {
                val x = rects[i]; val y = rects[i + 1]; val w = rects[i + 2]; val h = rects[i + 3]
                val r = rects[i + 4]; val k = rects[i + 5]
                i += 6
                if (w < 4f || h < 4f) continue
                rf.set(x, y, x + w, y + h)
                c.save()
                path.reset()
                path.addRoundRect(rf, r, r, Path.Direction.CW)
                c.clipPath(path)
                c.drawBitmap(b, m, bp)
                tint.color = Palette.bgRgb or ((k.coerceIn(0f, 1f) * 255).toInt() shl 24)
                c.drawRect(rf, tint)
                c.restore()
            }
        }
    }
}
