package com.nemoclient.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random

/** 软件用户等级。称号由官方授予，用户不可自行修改。 */
enum class Tier(val label: String) { NORMAL("普通用户"), PRO("Pro 用户"), SPONSOR("赞助用户") }

class UserProfile(
    val uid: String,
    var nickname: String,
    val registeredAt: Long,
    var usageSeconds: Long,
    val tier: Tier,
    val titles: List<String>      // 特别称号（官方授予）
)

/** Cookie 登录的游戏账号。cookie 只以加密形式保存，界面永远不显示它。 */
class GameAccount(
    val id: String,
    var name: String,
    var note: String,
    var lastUsed: Long,
    var cookieEnc: String
)

class LoginResult(val ok: Boolean, val message: String? = null)

/**
 * 预留的账号系统接口。
 * - onUseGameAccount：点「使用」时调用，cookie 为解密后的明文，真实的 Cookie 登录流程接在这里。
 * - 其余回调用于同步到你的服务端。
 */
interface AccountBackend {
    /** 登录（异步）。password 只在这里经手，软件不会保存，也不会写日志。 */
    fun login(name: String, password: String, callback: (LoginResult) -> Unit)
    fun onNicknameChanged(name: String)
    fun onLogout()
    fun onSwitchUser()
    fun onUseGameAccount(account: GameAccount, cookie: String)
    fun onGameAccountsChanged(accounts: List<GameAccount>)
}

/** 默认实现：只打日志（不会记录 cookie） */
class LogAccountBackend : AccountBackend {
    private fun d(m: String) { android.util.Log.d("rlinclient", m) }
    /** 演示实现：稍作延迟后直接成功。接入真实服务端时替换 */
    override fun login(name: String, password: String, callback: (LoginResult) -> Unit) {
        d("login as $name")
        Handler(Looper.getMainLooper()).postDelayed({ callback(LoginResult(true)) }, 700)
    }
    override fun onNicknameChanged(name: String) = d("nickname -> $name")
    override fun onLogout() = d("logout")
    override fun onSwitchUser() = d("switch user")
    override fun onUseGameAccount(account: GameAccount, cookie: String) = d("use game account ${account.name}")
    override fun onGameAccountsChanged(accounts: List<GameAccount>) = d("game accounts: ${accounts.size}")
}

/** 用 Android Keystore 的 AES-GCM 加密 cookie，密钥不会离开系统安全模块 */
object CookieVault {
    private const val ALIAS = "nemo_cookie_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    }
}

enum class SaveResult { ADDED, UPDATED, FAILED }

/** 软件用户资料 + 游戏账号列表（本地持久化）。 */
class AccountStore(ctx: Context, var backend: AccountBackend = LogAccountBackend()) {
    private val sp = ctx.applicationContext.getSharedPreferences("nemo_account", Context.MODE_PRIVATE)
    init {
        // v0.3.3：黑屏 bug 期间向导可能被盲点走完并标记完成，一次性重置让向导重新出现
        if (!sp.getBoolean("v033_reset", false)) {
            sp.edit().putBoolean("v033_reset", true).putBoolean("setup_done", false).apply()
        }
    }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val list = mutableListOf<GameAccount>()

    val profile: UserProfile
    val games: List<GameAccount> get() = list
    var currentGameId: String? = null
        private set

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() = listeners.forEach { it() }

    init {
        var uid = sp.getString("uid", null)
        if (uid == null) {
            uid = (10000000 + Random.nextInt(90000000)).toString()
            sp.edit().putString("uid", uid).putLong("reg", System.currentTimeMillis()).apply()
        }
        val tiers = Tier.values()
        profile = UserProfile(
            uid, sp.getString("nick", "绒凛") ?: "绒凛",
            sp.getLong("reg", System.currentTimeMillis()), sp.getLong("usage", 0L),
            tiers[sp.getInt("tier", 0).coerceIn(0, tiers.size - 1)],
            (sp.getString("titles", "") ?: "").split("|").filter { it.isNotBlank() }
        )
        loadGames()
    }

    // ---- 软件用户 ----
    fun setNickname(name: String) {
        profile.nickname = name
        sp.edit().putString("nick", name).apply()
        backend.onNicknameChanged(name)
        changed()
    }

    fun addUsage(seconds: Long) {
        if (seconds <= 0) return
        profile.usageSeconds += seconds
        sp.edit().putLong("usage", profile.usageSeconds).apply()
    }

    // ---- 首次启动向导 ----
    val setupDone: Boolean get() = sp.getBoolean("setup_done", false)
    fun markSetupDone() { sp.edit().putBoolean("setup_done", true).apply() }

    /** “记住我”：只记账户名，永远不记密码 */
    val rememberedName: String? get() = sp.getString("remember_name", null)
    fun setRememberedName(n: String?) { sp.edit().putString("remember_name", n).apply() }

    /** 登录成功后：昵称默认取账户名（之后可在账号页修改）。密码不会被保存。 */
    fun login(name: String, password: String, cb: (LoginResult) -> Unit) {
        backend.login(name, password) { r ->
            if (r.ok) {
                profile.nickname = name
                sp.edit().putString("nick", name).putString("login_name", name).apply()
                changed()
            }
            cb(r)
        }
    }

    fun logout() = backend.onLogout()
    fun switchUser() = backend.onSwitchUser()

    // ---- 游戏账号 ----
    /** 同名则更新 cookie，否则新增 */
    fun saveGame(name: String, note: String, cookie: String): SaveResult {
        val enc = runCatching { CookieVault.encrypt(cookie) }.getOrNull() ?: return SaveResult.FAILED
        val old = list.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val result: SaveResult
        if (old != null) {
            old.cookieEnc = enc
            if (note.isNotBlank()) old.note = note
            result = SaveResult.UPDATED
        } else {
            list.add(GameAccount(UUID.randomUUID().toString().take(8), name, note, 0L, enc))
            result = SaveResult.ADDED
        }
        persistGames()
        return result
    }

    /** 每行一条：名称----Cookie。返回 (新增, 更新, 跳过) */
    fun importGames(text: String): Triple<Int, Int, Int> {
        var added = 0; var updated = 0; var skipped = 0
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val i = line.indexOf("----")
            val name = if (i > 0) line.substring(0, i).trim() else ""
            val cookie = if (i > 0) line.substring(i + 4).trim() else ""
            if (name.isEmpty() || cookie.isEmpty()) { skipped++; return@forEach }
            when (saveGame(name, "", cookie)) {
                SaveResult.ADDED -> added++
                SaveResult.UPDATED -> updated++
                SaveResult.FAILED -> skipped++
            }
        }
        persistGames()
        return Triple(added, updated, skipped)
    }

    fun removeGames(ids: Set<String>) {
        list.removeAll { it.id in ids }
        if (currentGameId in ids) currentGameId = null
        persistGames()
    }

    /** 选用某个游戏账号：解密 cookie 后交给 backend */
    fun useGame(id: String): Boolean {
        val acc = list.firstOrNull { it.id == id } ?: return false
        val cookie = runCatching { CookieVault.decrypt(acc.cookieEnc) }.getOrNull() ?: return false
        currentGameId = id
        acc.lastUsed = System.currentTimeMillis()
        persistGames()
        backend.onUseGameAccount(acc, cookie)
        return true
    }

    private fun persistGames() {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("note", it.note)
                .put("last", it.lastUsed).put("c", it.cookieEnc))
        }
        sp.edit().putString("games", arr.toString()).putString("cur", currentGameId).apply()
        backend.onGameAccountsChanged(list)
        changed()
    }

    private fun loadGames() {
        val s = sp.getString("games", null)
        if (s == null) { seedDemo(); return }
        val arr = JSONArray(s)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(GameAccount(o.getString("id"), o.getString("name"), o.optString("note", ""), o.optLong("last", 0L), o.getString("c")))
        }
        currentGameId = sp.getString("cur", null)
    }

    /** 首次启动的演示数据（cookie 为占位值），正式使用前可全部删除 */
    private fun seedDemo() {
        listOf("Steve_01" to "演示数据", "NemoAlt" to "演示数据", "Player_7421" to "演示数据").forEach { (n, note) ->
            runCatching { CookieVault.encrypt("demo=1") }.getOrNull()?.let {
                list.add(GameAccount(UUID.randomUUID().toString().take(8), n, note, 0L, it))
            }
        }
        currentGameId = list.firstOrNull()?.id
        persistGames()
    }
}
