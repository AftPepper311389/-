package com.nemoclient.app.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** 一首歌。url 为空表示还没解析出直链（播放时由音源 resolve）。 */
class Track(
    val id: String, val title: String, val artist: String, val album: String,
    val durationMs: Long, var url: String?, var cover: String?, val source: Int,
    /** 音源自定义的拓展字段（封面 ID / 歌词 ID / 所属平台），不用的音源为空 */
    val picId: String? = null, val lyricId: String? = null, val platform: String? = null,
    /** 把 http 直链升级成 https 后，原始 http 链接留作回退（游戏进程可能禁止明文，也可能 https 不通） */
    var fallbackUrl: String? = null
)

class LrcLine(val timeMs: Long, val text: String)

/** 音源：三个方法都会在后台线程调用，可以直接阻塞联网。 */
interface MusicSource {
    val name: String
    fun search(q: String, limit: Int): List<Track>
    fun resolve(t: Track): String?
    /** 带时间轴的 LRC 文本；没有返回 null（会回退到 LRCLIB） */
    fun lyric(t: Track): String? = null
    /** 封面图直链；默认用 Track.cover，音源可以覆盖（比如要再请求一次才能拿到） */
    fun cover(t: Track): String? = t.cover
}

internal object Http {
    fun get(url: String, timeout: Int = 12000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        c.setRequestProperty("User-Agent", "rlin-music/0.11 (+https://github.com)")
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

/** Jamendo：合法的 CC 授权音乐，需要自己去 developer.jamendo.com 申请一个免费 client_id。 */
class JamendoSource(private val clientId: String, private val idx: Int) : MusicSource {
    override val name = "Jamendo"
    override fun search(q: String, limit: Int): List<Track> {
        if (clientId.isBlank()) throw IllegalStateException("未填写 Jamendo client_id")
        val j = JSONObject(Http.get("https://api.jamendo.com/v3.0/tracks/?client_id=${Http.enc(clientId)}" +
            "&format=json&limit=$limit&audioformat=mp32&search=${Http.enc(q)}"))
        val a = j.optJSONArray("results") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Track(it.str("id"), it.str("name"), it.str("artist_name"), it.str("album_name"),
                it.optLong("duration") * 1000, it.str("audio").ifEmpty { null }, it.str("image").ifEmpty { null }, idx)
        }
    }
    override fun resolve(t: Track): String? = t.url
}

/**
 * 自定义 API（接你自己的服务 / 自建网关；返回内容要自己有授权）：
 *   GET {base}/search?q=关键字&limit=20 → [{id,title,artist,album,duration(秒),url?,cover?}]
 *   GET {base}/url?id=xxx → {"url":"https://..."}
 *   GET {base}/lyric?id=xxx → {"lrc":"[00:01.00]..."}
 */
class CustomSource(base: String, private val idx: Int) : MusicSource {
    private val b = base.trim().trimEnd('/')
    override val name = "自定义 API"
    override fun search(q: String, limit: Int): List<Track> {
        if (b.isEmpty()) throw IllegalStateException("未填写自定义 API 地址")
        val a = JSONArray(Http.get("$b/search?q=${Http.enc(q)}&limit=$limit"))
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Track(it.str("id"), it.str("title"), it.str("artist"), it.str("album"),
                it.optLong("duration") * 1000, it.str("url").ifEmpty { null }, it.str("cover").ifEmpty { null }, idx)
        }
    }
    override fun resolve(t: Track): String? = t.url ?: JSONObject(Http.get("$b/url?id=${Http.enc(t.id)}")).str("url").ifEmpty { null }
    override fun lyric(t: Track): String? = runCatching { JSONObject(Http.get("$b/lyric?id=${Http.enc(t.id)}")).str("lrc").ifEmpty { null } }.getOrNull()
}

/**
 * 网易云音乐 API（NeteaseCloudMusicApi 一类的服务，地址由你自己填，本程序不内置也不托管任何服务）：
 *   GET {base}/cloudsearch?keywords=&limit= · GET {base}/song/url/v1?id=&level=standard · GET {base}/lyric?id=
 * Cookie 可选（你自己账号的 MUSIC_U=…），原样作为 cookie 参数交给你的服务。
 * 没有版权 / 需要会员的歌，服务端不给链接时直接提示，不做任何绕过。
 */
class NeteaseSource(base: String, private val cookie: String, private val idx: Int) : MusicSource {
    private val b = base.trim().trimEnd('/')
    override val name = "网易云 API"
    private fun ck() = if (cookie.isBlank()) "" else "&cookie=${Http.enc(cookie.trim())}"

    override fun search(q: String, limit: Int): List<Track> {
        if (b.isEmpty()) throw IllegalStateException("未填写网易云 API 地址")
        val j = JSONObject(Http.get("$b/cloudsearch?keywords=${Http.enc(q)}&limit=$limit${ck()}"))
        val a = j.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            val ar = it.optJSONArray("ar") ?: it.optJSONArray("artists") ?: JSONArray()
            val artist = (0 until ar.length()).joinToString("/") { i -> ar.getJSONObject(i).str("name") }
            val al = it.optJSONObject("al") ?: it.optJSONObject("album")
            Track(it.optLong("id").toString(), it.str("name"), artist, al?.str("name") ?: "",
                if (it.has("dt")) it.optLong("dt") else it.optLong("duration"), null, al?.str("picUrl")?.ifEmpty { null }, idx)
        }
    }

    override fun resolve(t: Track): String? {
        var u: String? = null
        try {
            val d = JSONObject(Http.get("$b/song/url/v1?id=${Http.enc(t.id)}&level=standard${ck()}")).optJSONArray("data")?.optJSONObject(0)
            u = d?.str("url")
        } catch (e: Throwable) {
            val d = JSONObject(Http.get("$b/song/url?id=${Http.enc(t.id)}${ck()}")).optJSONArray("data")?.optJSONObject(0)
            u = d?.str("url")
        }
        if (u.isNullOrEmpty()) throw IllegalStateException("无法播放：无版权或需要会员")
        return u.replace("http://", "https://")
    }

    override fun lyric(t: Track): String? = runCatching {
        JSONObject(Http.get("$b/lyric?id=${Http.enc(t.id)}${ck()}")).optJSONObject("lrc")?.str("lyric")?.ifEmpty { null }
    }.getOrNull()
}


/** 封面：下载 + 缩小解码，最近 8 张缓存在内存。 */
internal object CoverLoader {
    private val cache = object : android.util.LruCache<String, Bitmap>(8) {}

    fun fetch(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        val u = if (url.startsWith("http://")) "https://" + url.substring(7) else url
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        try {
            if (c.responseCode !in 200..299) return null
            val bytes = c.inputStream.use { it.readBytes() }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= 256 && o.outHeight / (sample * 2) >= 256) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp != null) cache.put(url, bmp)
            return bmp
        } finally { c.disconnect() }
    }
}

object Lrc {
    private val tag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    fun parse(text: String): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        for (raw in text.lines()) {
            val ms = tag.findAll(raw).toList()
            if (ms.isEmpty()) continue
            val content = raw.substring(ms.last().range.last + 1).trim()
            for (m in ms) {
                val frac = m.groupValues[3]
                val f = if (frac.isEmpty()) 0L else (frac.padEnd(3, '0').take(3)).toLong()
                out.add(LrcLine(m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 + f, content))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** LRCLIB（免费、无需 key）：先精确匹配，不行再搜索，取第一条带时间轴的 */
    fun fetchLrclib(t: Track): String? {
        val dur = if (t.durationMs > 0) "&duration=${t.durationMs / 1000}" else ""
        runCatching {
            val j = JSONObject(Http.get("https://lrclib.net/api/get?track_name=${Http.enc(t.title)}&artist_name=${Http.enc(t.artist)}$dur"))
            val s = j.str("syncedLyrics"); if (s.isNotEmpty()) return s
        }
        runCatching {
            val a = JSONArray(Http.get("https://lrclib.net/api/search?track_name=${Http.enc(t.title)}&artist_name=${Http.enc(t.artist)}"))
            for (i in 0 until a.length()) { val s = a.getJSONObject(i).str("syncedLyrics"); if (s.isNotEmpty()) return s }
        }
        return null
    }
}

/** 播放状态变化回调（主线程）。trackChanged = 换歌了。 */
fun interface MusicListener { fun onChange(trackChanged: Boolean) }

/**
 * 内置播放器（单例，主线程使用）。
 * 不申请音频焦点：和游戏声音混着放，不会把游戏静音。联网/解析都在后台线程，回调回主线程。
 */
object MusicPlayer {
    /** 音源：只保留用户自己接入的三种（Jamendo 要自己申请 client_id，网易云 / 自定义要自己填服务地址） */
    const val SOURCE_JAMENDO = 0
    const val SOURCE_CUSTOM = 1
    const val SOURCE_NETEASE = 2

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val listeners = CopyOnWriteArrayList<MusicListener>()
    private var prefs: android.content.SharedPreferences? = null
    private var mp: MediaPlayer? = null
    private var gen = 0 // 每次换歌 +1，丢弃过期的异步结果

    val queue = ArrayList<Track>()
    var index = -1; private set
    var playing = false; private set
    var loading = false; private set
    var error: String? = null; private set
    var posMs = 0L; private set
    var durMs = 0L; private set
    var lyrics: List<LrcLine> = emptyList(); private set
    var coverBmp: Bitmap? = null; private set
    /** 0 列表循环 1 单曲循环 2 随机 */
    var mode = 0; private set

    val track: Track? get() = queue.getOrNull(index)
    val active: Boolean get() = track != null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("rlin_music", Context.MODE_PRIVATE)
    }

    var sourceIdx: Int
        get() = (prefs?.getInt("src", SOURCE_JAMENDO) ?: SOURCE_JAMENDO).takeIf { it in 0..SOURCE_NETEASE } ?: SOURCE_JAMENDO
        set(v) { prefs?.edit()?.putInt("src", v)?.apply() }
    var jamendoId: String
        get() = prefs?.getString("jid", "") ?: ""
        set(v) { prefs?.edit()?.putString("jid", v.trim())?.apply() }
    var customBase: String
        get() = prefs?.getString("cbase", "") ?: ""
        set(v) { prefs?.edit()?.putString("cbase", v.trim())?.apply() }
    var neteaseBase: String
        get() = prefs?.getString("nbase", "") ?: ""
        set(v) { prefs?.edit()?.putString("nbase", v.trim())?.apply() }
    var neteaseCookie: String
        get() = prefs?.getString("ncookie", "") ?: ""
        set(v) { prefs?.edit()?.putString("ncookie", v.trim())?.apply() }
    var volume: Float
        get() = prefs?.getFloat("vol", 1f) ?: 1f
        set(v) { prefs?.edit()?.putFloat("vol", v)?.apply(); mp?.setVolume(v, v) }

    fun source(i: Int): MusicSource = when (i) {
        SOURCE_JAMENDO -> JamendoSource(jamendoId, i)
        SOURCE_CUSTOM -> CustomSource(customBase, i)
        SOURCE_NETEASE -> NeteaseSource(neteaseBase, neteaseCookie, i)
        else -> JamendoSource(jamendoId, SOURCE_JAMENDO)
    }

    fun addListener(l: MusicListener) { listeners.addIfAbsent(l) }
    fun removeListener(l: MusicListener) { listeners.remove(l) }
    /** 单个监听器抛异常不能让整条回调链（含播放计时）断掉 */
    private fun fire(trackChanged: Boolean) {
        for (l in listeners) try { l.onChange(trackChanged) } catch (t: Throwable) { Log.e("rlin", "music listener failed", t) }
    }

    fun search(q: String, cb: (List<Track>?, String?) -> Unit) {
        val src = source(sourceIdx)
        pool.execute {
            var res: List<Track>? = null; var err: String? = null
            try { res = src.search(q, 20) } catch (e: Throwable) { err = e.message ?: e.javaClass.simpleName }
            main.post { cb(res, err) }
        }
    }

    fun playList(list: List<Track>, start: Int) {
        queue.clear(); queue.addAll(list); play(start)
    }

    fun play(i: Int) {
        if (i !in queue.indices) return
        val g = ++gen
        releasePlayer()
        stopTick()
        index = i; posMs = 0; durMs = queue[i].durationMs; lyrics = emptyList(); coverBmp = null
        loading = true; error = null; playing = false
        fire(true)
        val t = queue[i]
        pool.execute {
            var url: String? = null; var err: String? = null
            try {
                // 网易云 API 的直链会过期（约十几二十分钟），不缓存，每次播放重新取
                val cache = t.source != SOURCE_NETEASE
                url = (if (cache) t.url else null) ?: source(t.source).resolve(t)
                if (cache) t.url = url
            } catch (e: Throwable) { err = e.message ?: e.javaClass.simpleName }
            main.post {
                if (g != gen) return@post
                if (url == null) { fail(err ?: "没有可播放的音频"); return@post }
                startPlayer(url, g)
            }
            // 已经切歌了就别再继续取歌词 / 封面，避免浪费请求
            if (g != gen || url == null) return@execute
            // 歌词：音源自带优先，其次 LRCLIB
            val lrc = runCatching { source(t.source).lyric(t) ?: Lrc.fetchLrclib(t) }.getOrNull()
            if (lrc != null) main.post { if (g == gen) { lyrics = Lrc.parse(lrc); fire(false) } }
            if (g != gen) return@execute
            // 封面（失败就算了，不影响播放）
            val bmp = runCatching { source(t.source).cover(t)?.let { CoverLoader.fetch(it) } }.getOrNull()
            if (bmp != null) main.post { if (g == gen) { coverBmp = bmp; fire(false) } }
        }
        // 20 秒还没准备好就当失败，不让界面一直“加载中”
        main.postDelayed({ if (g == gen && loading) fail("加载超时，请检查网络或换一首") }, 20000L)
    }

    private fun startPlayer(url: String, g: Int) {
        try {
            val p = MediaPlayer()
            mp = p
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setVolume(volume, volume)
            p.setDataSource(url)
            p.setOnPreparedListener {
                if (g != gen) return@setOnPreparedListener
                loading = false; durMs = it.duration.toLong().coerceAtLeast(0); it.start(); playing = true; fire(false); startTick()
            }
            p.setOnCompletionListener { if (g == gen) onEnd() }
            p.setOnErrorListener { _, _, _ ->
                if (g == gen) main.post {
                    if (g != gen) return@post
                    val fb = track?.fallbackUrl
                    if (fb != null && fb != url) { track?.fallbackUrl = null; releasePlayer(); startPlayer(fb, g) }
                    else fail("播放失败（链接无效或格式不支持）")
                }
                true
            }
            p.prepareAsync()
        } catch (e: Throwable) { fail(e.message ?: "播放失败") }
    }

    // 播放进度计时：全局只有一条链。以前每次暂停→继续都会再开一条，计时越叠越多，界面刷新频率成倍上升
    private var ticking = false
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return
            val p = mp
            if (p != null && playing) { runCatching { posMs = p.currentPosition.toLong() }; fire(false) }
            if (ticking) main.postDelayed(this, 250L)
        }
    }
    private fun startTick() { if (ticking) return; ticking = true; main.postDelayed(tickRunnable, 250L) }
    private fun stopTick() { ticking = false; main.removeCallbacks(tickRunnable) }

    private fun onEnd() {
        playing = false; stopTick()
        when {
            queue.isEmpty() -> {}
            mode == 1 -> play(index)
            mode == 2 -> play((queue.indices).random())
            index + 1 < queue.size -> play(index + 1)
            else -> play(0)
        }
    }

    private fun fail(msg: String) {
        stopTick(); loading = false; playing = false; error = msg; releasePlayer(); fire(false)
    }

    private fun releasePlayer() { runCatching { mp?.release() }; mp = null }

    fun toggle() {
        val p = mp ?: return
        runCatching { if (p.isPlaying) { p.pause(); playing = false; stopTick() } else { p.start(); playing = true; startTick() } }
        fire(false)
    }
    fun next() { if (queue.isNotEmpty()) play(if (mode == 2) queue.indices.random() else (index + 1) % queue.size) }
    fun prev() { if (queue.isNotEmpty()) { if (posMs > 4000) seekTo(0) else play((index - 1 + queue.size) % queue.size) } }
    fun seekTo(ms: Long) { runCatching { mp?.seekTo(ms.toInt()) }; posMs = ms; fire(false) }
    fun cycleMode() { mode = (mode + 1) % 3; fire(false) }

    fun stop() {
        gen++; stopTick(); releasePlayer(); queue.clear(); index = -1; playing = false; loading = false; error = null
        posMs = 0; durMs = 0; lyrics = emptyList(); coverBmp = null; fire(true)
    }

    /** 当前时间对应的歌词行；没有歌词返回空串 */
    fun currentLine(): String {
        if (lyrics.isEmpty()) return ""
        var r = ""
        for (l in lyrics) { if (l.timeMs <= posMs + 150) r = l.text else break }
        return r
    }

    fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }
}
