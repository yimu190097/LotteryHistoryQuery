package com.lottery.history.data

import android.content.Context
import android.util.Log
import com.lottery.history.db.LotteryDatabase
import com.lottery.history.db.PlanType
import com.lottery.history.db.UserEntity
import com.lottery.history.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mindrot.jbcrypt.BCrypt

/**
 * 认证 Repository：服务器优先 + 本地兜底（离线可登录）。
 *
 * 流程：
 * - register/login：先调 server（拿 JWT），失败时降级到本地校验（仅离线可用）
 * - changePassword：本地改密 + 入队同步到 server
 * - 离线时使用本地 Room 缓存的用户（首次启动必须联网注册过）
 */
class AuthRepository(private val context: Context) {

    private val userDao by lazy { LotteryDatabase.get(context).userDao() }
    private val sessionStore by lazy { SessionStore(context) }

    /** 注册：服务器优先，拿 JWT + 配额。返回 notice 消息（终端踢除提示等） */
    suspend fun register(phone: String, password: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            if (!isValidPhone(phone)) return@withContext Result.failure(IllegalArgumentException("手机号格式不正确"))
            if (password.length < 6) return@withContext Result.failure(IllegalArgumentException("密码至少6位"))

            // 1) 服务器注册
            val resp = try {
                ApiClient.register(phone, password)
            } catch (e: ApiClient.ApiException) {
                return@withContext Result.failure(IllegalArgumentException(e.message))
            }
            // 2) 本地缓存用户（用 server 返回的 token，本地密码哈希仅作离线兜底）
            val now = System.currentTimeMillis()
            userDao.upsert(
                UserEntity(
                    phone = resp.phone,
                    passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(12)),
                    createdAt = now,
                    updatedAt = now
                )
            )
            // 3) 用 server 返回的配额初始化本地缓存
            QuotaRepository(context).applyServerSnapshot(
                phone = resp.phone,
                freeUsed = resp.freeUsed,
                freeQueryLimit = resp.freeLimit,
                planTypeStr = resp.planType,
                monthlyExpireAt = resp.monthlyExpireAt,
                serverVersion = 1
            )
            // sessionStore.saveLogin 已在 ApiClient.register 内部完成
            Result.success(resp.notice)
        } catch (e: Exception) {
            Log.w("AuthRepository", "register failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** 登录：服务器优先，失败时降级到本地（离线可用）。返回 notice 消息（终端踢除提示等） */
    suspend fun login(phone: String, password: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            // 1) 先尝试服务器登录
            try {
                val resp = ApiClient.login(phone, password)
                // 同步配额到本地缓存
                QuotaRepository(context).applyServerSnapshot(
                    phone = resp.phone,
                    freeUsed = resp.freeUsed,
                    freeQueryLimit = resp.freeLimit,
                    planTypeStr = resp.planType,
                    monthlyExpireAt = resp.monthlyExpireAt,
                    serverVersion = 1
                )
                return@withContext Result.success(resp.notice)
            } catch (serverErr: Exception) {
                Log.w("AuthRepository", "server login failed, fallback to local: ${serverErr.message}")
            }
            // 2) 服务器失败 → 本地校验（离线兜底）
            val user = userDao.getByPhone(phone)
                ?: return@withContext Result.failure(IllegalArgumentException("用户不存在（且服务器不可达）"))
            if (!BCrypt.checkpw(password, user.passwordHash)) {
                return@withContext Result.failure(IllegalArgumentException("密码错误"))
            }
            sessionStore.saveLoginPhone(phone)
            Result.success(null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 修改密码：本地改 + 入队待同步（SyncWorker 推到 server） */
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
            // 改密入队待同步
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
