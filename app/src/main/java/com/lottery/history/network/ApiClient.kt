package com.lottery.history.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.lottery.history.AppContext
import com.lottery.history.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 客户端 API：与后台 server 通信（登录/注册/扣次数/上传文件）。
 *
 * baseUrl 默认指向 10.0.2.2:3000（Android 模拟器 → 宿主机 3000）。
 * 真机或公网部署时改为实际域名（在 [BASE_URL] 修改，或通过 BuildConfig 注入）。
 */
object ApiClient {

    // 公网部署时改为：https://your-domain.example.com
    // 模拟器测试用 10.0.2.2，真机连本地虚拟机用 192.168.x.x:3000
    const val BASE_URL = "https://showbiz-unbridle-decent.ngrok-free.dev"

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client by lazy {
        val cacheDir = File(AppContext.get.cacheDir, "okhttp_cache")
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
            .cache(Cache(cacheDir, 5 * 1024 * 1024)) // 5MB 响应缓存，登录/配额等重复请求可复用
            .build()
    }

    private val sessionStore by lazy { SessionStore(AppContext.get) }

    // ============ 数据模型 ============

    data class AuthResponse(
        val token: String,
        val phone: String,
        val nickname: String?,
        val planType: String,
        val freeUsed: Int,
        val freeLimit: Int,
        val monthlyExpireAt: Long?,
        val vipExpired: Boolean,
        val notice: String?
    )

    data class ConsumeResponse(
        val success: Boolean,
        val freeUsed: Int,
        val freeLimit: Int,
        val planType: String,
        val canQuery: Boolean
    )
    data class QuotaResponse(
        val phone: String,
        val nickname: String?,
        val planType: String,
        val freeUsed: Int,
        val freeLimit: Int,
        val monthlyExpireAt: Long?,
        val vipExpired: Boolean
    )
    data class SessionInfo(
        val id: Long,
        val deviceInfo: String,
        val ipAddress: String,
        val createdAt: Long,
        val lastActiveAt: Long,
        val isCurrent: Boolean,
        val online: Boolean
    )
    data class SessionsResponse(val sessions: List<SessionInfo>, val maxSessions: Int)
    data class UploadResponse(val url: String, val size: Long, val mimetype: String)

    class ApiException(val code: Int, override val message: String) : Exception(message)

    // ============ API 方法 ============

    suspend fun register(phone: String, password: String, nickname: String? = null): AuthResponse = withContext(Dispatchers.IO) {
        val params = mutableMapOf("phone" to phone, "password" to password)
        if (!nickname.isNullOrBlank()) params["nickname"] = nickname
        val resp = post("/api/users/client/register", gson.toJson(params))
        gson.fromJson(resp, AuthResponse::class.java).also { result ->
            sessionStore.saveLogin(result.phone, result.token, result.nickname)
        }
    }

    suspend fun login(phone: String, password: String): AuthResponse = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("phone" to phone, "password" to password))
        val resp = post("/api/users/client/login", body)
        gson.fromJson(resp, AuthResponse::class.java).also { result ->
            sessionStore.saveLogin(result.phone, result.token, result.nickname)
        }
    }

    /**
     * 扣减查询次数：需用户 Token，phone 必须是当前登录用户。
     *
     * @param clientOpId 幂等键：服务器据此去重，防止「服务器已扣减但响应丢失 → 重试再扣」。
     *                  同一 clientOpId 重复调用只扣一次，后续直接返回首次结果。
     * @return 剩余次数，失败抛 [ApiException]
     */
    suspend fun consumeQuery(phone: String, count: Int = 1, clientOpId: String? = null): ConsumeResponse = withContext(Dispatchers.IO) {
        val params = mutableMapOf("phone" to phone, "count" to count)
        if (clientOpId != null) params["clientOpId"] = clientOpId
        val body = gson.toJson(params)
        val resp = post("/api/users/client/consume", body, requireAuth = true)
        gson.fromJson(resp, ConsumeResponse::class.java)
    }

    /**
     * 拉取当前用户权威配额快照（用于离线后对账）。
     */
    suspend fun getQuota(): QuotaResponse = withContext(Dispatchers.IO) {
        val resp = get("/api/users/client/quota", requireAuth = true)
        gson.fromJson(resp, QuotaResponse::class.java)
    }

    /** 获取当前用户的所有活跃终端 */
    suspend fun getSessions(): SessionsResponse = withContext(Dispatchers.IO) {
        val resp = get("/api/users/client/sessions", requireAuth = true)
        gson.fromJson(resp, SessionsResponse::class.java)
    }

    /** 删除指定终端 */
    suspend fun deleteSession(id: Long): String = withContext(Dispatchers.IO) {
        val resp = delete("/api/users/client/sessions/$id", requireAuth = true)
        gson.fromJson(resp, JsonObject::class.java).get("message")?.asString ?: "ok"
    }

    /** 删除所有其他终端（保留当前） */
    suspend fun deleteOtherSessions(): String = withContext(Dispatchers.IO) {
        val resp = delete("/api/users/client/sessions", requireAuth = true)
        gson.fromJson(resp, JsonObject::class.java).get("message")?.asString ?: "ok"
    }

    /**
     * 上传文件（图片/语音）：multipart/form-data，需 Token。
     * @param file 本地文件
     * @param mimeType 如 "image/jpeg" / "audio/mp4"
     * @return 上传后的 URL 路径（如 /uploads/xxx.jpg），失败抛 [ApiException]
     */
    suspend fun uploadFile(file: File, mimeType: String): UploadResponse = withContext(Dispatchers.IO) {
        val mediaType = mimeType.toMediaType()
        val reqBody = file.asRequestBody(mediaType)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, reqBody)
            .build()
        val req = Request.Builder()
            .url(BASE_URL + "/api/upload")
            .post(multipart)
            .apply {
                sessionStore.getToken()?.let { header("Authorization", "Bearer $it") }
            }
            .build()
        val resp = client.newCall(req).execute()
        val respBody = resp.body?.string() ?: "{}"
        if (!resp.isSuccessful) {
            val err = parseError(respBody)
            throw ApiException(resp.code, err)
        }
        gson.fromJson(respBody, UploadResponse::class.java)
    }

    /** 拼接完整文件 URL（消息里的 mediaPath 是 /uploads/xxx.jpg，UI 加载时拼上 BASE_URL） */
    fun fileUrl(mediaPath: String?): String? {
        if (mediaPath.isNullOrBlank()) return null
        if (mediaPath.startsWith("http://") || mediaPath.startsWith("https://")) return mediaPath
        return BASE_URL + mediaPath
    }

    // ============ HTTP 底层 ============

    private fun post(path: String, json: String, requireAuth: Boolean = false): String {
        val req = Request.Builder()
            .url(BASE_URL + path)
            .post(json.toRequestBody(JSON))
            .apply {
                if (requireAuth) {
                    sessionStore.getToken()?.let { header("Authorization", "Bearer $it") }
                }
            }
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: "{}"
        if (!resp.isSuccessful) {
            throw ApiException(resp.code, parseError(body))
        }
        return body
    }

    private fun get(path: String, requireAuth: Boolean = false): String {
        val req = Request.Builder()
            .url(BASE_URL + path)
            .get()
            .apply {
                if (requireAuth) {
                    sessionStore.getToken()?.let { header("Authorization", "Bearer $it") }
                }
            }
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: "{}"
        if (!resp.isSuccessful) {
            throw ApiException(resp.code, parseError(body))
        }
        return body
    }

    private fun delete(path: String, requireAuth: Boolean = false): String {
        val req = Request.Builder()
            .url(BASE_URL + path)
            .delete()
            .apply {
                if (requireAuth) {
                    sessionStore.getToken()?.let { header("Authorization", "Bearer $it") }
                }
            }
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: "{}"
        if (!resp.isSuccessful) {
            throw ApiException(resp.code, parseError(body))
        }
        return body
    }

    private fun parseError(body: String): String {
        return try {
            gson.fromJson(body, JsonObject::class.java).get("error")?.asString ?: body
        } catch (_: Exception) { body }
    }
}
