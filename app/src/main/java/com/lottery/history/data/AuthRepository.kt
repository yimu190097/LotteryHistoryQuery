package com.lottery.history.data

import android.content.Context
import com.lottery.history.db.LotteryDatabase
import com.lottery.history.db.UserEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mindrot.jbcrypt.BCrypt

/**
 * 认证 Repository：面向接口，当前为本地实现，后期接服务器时替换为 RemoteAuthRepository，UI 层零改动。
 *
 * 当前阶段（服务器未接入）：
 * - 注册：本地写 users 表，密码 bcrypt 哈希存储
 * - 登录：本地校验 bcrypt
 * - 改密：本地更新
 *
 * 后期阶段：替换为调服务器接口，token 存 SessionStore，用户信息缓存到 users 表便于离线展示。
 */
class AuthRepository(private val context: Context) {

    private val userDao by lazy { LotteryDatabase.get(context).userDao() }
    private val sessionStore by lazy { SessionStore(context) }

    /** 注册并登录：手机号不存在则创建，初始配额见 QuotaRepository.initForNewUser */
    suspend fun register(phone: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!isValidPhone(phone)) return@withContext Result.failure(IllegalArgumentException("手机号格式不正确"))
            if (password.length < 6) return@withContext Result.failure(IllegalArgumentException("密码至少6位"))
            if (userDao.countByPhone(phone) > 0) {
                return@withContext Result.failure(IllegalArgumentException("该手机号已注册"))
            }
            val now = System.currentTimeMillis()
            userDao.upsert(
                UserEntity(
                    phone = phone,
                    passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(12)),
                    createdAt = now,
                    updatedAt = now
                )
            )
            // 初始化配额（按次用户，赠送10次体验）
            QuotaRepository(context).initForNewUser(phone)
            sessionStore.saveLoginPhone(phone)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 登录：本地校验 bcrypt */
    suspend fun login(phone: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val user = userDao.getByPhone(phone)
                ?: return@withContext Result.failure(IllegalArgumentException("用户不存在"))
            if (!BCrypt.checkpw(password, user.passwordHash)) {
                return@withContext Result.failure(IllegalArgumentException("密码错误"))
            }
            sessionStore.saveLoginPhone(phone)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 修改密码：需校验旧密码 */
    suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val phone = sessionStore.getLoginPhone()
                ?: return@withContext Result.failure(IllegalArgumentException("未登录"))
            if (newPassword.length < 6) return@withContext Result.failure(IllegalArgumentException("密码至少6位"))
            val user = userDao.getByPhone(phone)
                ?: return@withContext Result.failure(IllegalArgumentException("用户不存在"))
            if (!BCrypt.checkpw(oldPassword, user.passwordHash)) {
                return@withContext Result.failure(IllegalArgumentException("旧密码错误"))
            }
            userDao.upsert(
                user.copy(
                    passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(12)),
                    updatedAt = System.currentTimeMillis()
                )
            )
            // 改密入队待同步（服务器接入后生效）
            QuotaRepository(context).enqueuePasswordChange(phone)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun currentPhone(): String? = sessionStore.getLoginPhone()
    fun isLoggedIn(): Boolean = sessionStore.isLoggedIn()
    fun logout() = sessionStore.clear()

    private fun isValidPhone(phone: String): Boolean =
        phone.length == 11 && phone.all { it.isDigit() } && phone.startsWith("1")
}
