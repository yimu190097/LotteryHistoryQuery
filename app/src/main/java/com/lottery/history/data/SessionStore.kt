package com.lottery.history.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 加密存储登录态：手机号 + JWT token + 昵称。
 * 使用 EncryptedSharedPreferences + Android Keystore（AES256_GCM），防止 root 读取明文。
 */
class SessionStore(context: Context) {

    private val prefs = try {
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "session",
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // 极少数设备 Keystore 不可用时回退到普通 SharedPreferences（降级，仍可用）
        context.applicationContext.getSharedPreferences("session_fallback", Context.MODE_PRIVATE)
    }

    fun saveLogin(phone: String, token: String, nickname: String? = null) {
        prefs.edit()
            .putString(KEY_PHONE, phone)
            .putString(KEY_TOKEN, token)
            .putString(KEY_NICKNAME, nickname)
            .putBoolean(KEY_LOGGED_IN, true)
            .apply()
    }

    /** 旧接口兼容（无 token 时仅存手机号，模拟离线模式） */
    fun saveLoginPhone(phone: String) {
        prefs.edit().putString(KEY_PHONE, phone).putBoolean(KEY_LOGGED_IN, true).apply()
    }

    fun getLoginPhone(): String? = prefs.getString(KEY_PHONE, null)

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun getNickname(): String? = prefs.getString(KEY_NICKNAME, null)

    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_LOGGED_IN, false)

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_PHONE = "phone"
        private const val KEY_TOKEN = "token"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_LOGGED_IN = "logged_in"
    }
}
