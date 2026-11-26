package com.ajx.botforge.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 敏感字段保险箱（F4）
 *
 * 目标：AppSecret / API Key 不以明文出现在 botforge.xml 里。
 * 做法：把值搬进 EncryptedSharedPreferences（AES256-GCM，密钥由 Android Keystore 托管），
 *      原 SharedPreferences 里只留一个占位符。
 *
 * 降级策略：部分 ROM 的 Keystore 会异常（解锁失败、Provider 崩溃）。
 * 这种情况下宁可退回明文保证 App 可用，也不让用户打不开配置。
 * 降级会打日志并在 UI 上体现（hasSecureStore() 返回 false）。
 */
object SecretStore {

    private const val PLAIN_FILE = "botforge_secrets"
    private const val ENC_FILE = "botforge_secrets_enc"

    /** 占位符：原配置里出现它表示「真值在保险箱」 */
    const val PLACEHOLDER = "\u0000ENC\u0000"

    private var enc: SharedPreferences? = null
    private var plain: SharedPreferences? = null
    private var ready = false
    private var secure = false

    fun init(ctx: Context) {
        if (ready) return
        ready = true
        plain = ctx.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)
        enc = try {
            val key = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                ENC_FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { secure = true }
        } catch (e: Exception) {
            Logger.w("Secret", "加密存储不可用，降级为明文: ${e.javaClass.simpleName} ${e.message}")
            secure = false
            null
        }
    }

    /** 当前是否真的在加密存储（false 表示已降级） */
    fun hasSecureStore(): Boolean = secure

    fun put(key: String, value: String) {
        if (value.isEmpty()) {
            remove(key)
            return
        }
        val sp = enc
        if (sp != null) {
            try {
                sp.edit().putString(key, value).apply()
                return
            } catch (e: Exception) {
                Logger.w("Secret", "写入加密存储失败，落明文: ${e.message}")
            }
        }
        plain?.edit()?.putString(key, value)?.apply()
    }

    fun get(key: String): String {
        enc?.let {
            try {
                it.getString(key, null)?.let { v -> return v }
            } catch (e: Exception) {
                Logger.w("Secret", "读取加密存储失败: ${e.message}")
            }
        }
        return plain?.getString(key, "") ?: ""
    }

    fun remove(key: String) {
        try {
            enc?.edit()?.remove(key)?.apply()
        } catch (_: Exception) {
        }
        plain?.edit()?.remove(key)?.apply()
    }

    /** 保险箱里是否存在该键 */
    fun contains(key: String): Boolean {
        enc?.let {
            try {
                if (it.contains(key)) return true
            } catch (_: Exception) {
            }
        }
        return plain?.contains(key) ?: false
    }

    // ------------------------------------------------------------ 键名约定
    fun botKey(botId: String) = "bot_secret_$botId"
    fun modelKey(modelId: String) = "model_key_$modelId"
}
