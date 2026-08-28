package com.lottery.admin.network

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 后台API客户端：连接后台服务器，管理用户/配额/统计/配置。
 */
object AdminApi {
    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 默认服务器地址，发布时替换为实际域名 */
    var baseUrl = "https://hypothetical-loc-earning-nutritional.trycloudflare.com"
    var token: String? = null

    // ========== 数据模型 ==========

    data class AdminUser(
        val id: Int,
        val username: String,
        val role: String
    )

    data class LoginResponse(
        val token: String,
        val admin: AdminUser
    )

    data class DashboardStats(
        val totalUsers: Int,
        val todayNewUsers: Int,
        val weekNewUsers: Int,
        val totalQueries: Int,
        val todayQueries: Int,
        val quotaStats: QuotaStats?
    )
    data class QuotaStats(
        val total: Int,
        val pay_per_use_count: Int,
        val monthly_count: Int,
        val total_remaining: Int
    )

    data class AuditLog(
        val id: Long,
        val admin_id: Int?,
        val admin_username: String?,
        val action: String,
        val target: String?,
        val detail: String?,
        val created_at: Long
    )

    data class DashboardResponse(
        val stats: DashboardStats,
        val recentLogs: List<AuditLog>
    )

    data class UserInfo(
        val phone: String,
        val nickname: String?,
        val is_admin: Int,
        val created_at: Long,
        val updated_at: Long,
        val plan_type: String?,
        val remaining_queries: Int?,
        val monthly_expire_at: Long?,
        val quota_updated_at: Long?
    )

    data class PagedResponse<T>(
        val total: Int,
        val page: Int,
        val size: Int,
        val totalPages: Int,
        val data: List<T>
    )

    data class AdminEntry(
        val id: Int,
        val username: String,
        val role: String,
        val created_at: Long,
        val last_login: Long?
    )

    data class ErrorResponse(val error: String)

    // ========== API 方法 ==========

    suspend fun login(username: String, password: String): LoginResponse = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("username" to username, "password" to password))
        val resp = post("/api/auth/login", body)
        gson.fromJson(resp, LoginResponse::class.java)
    }

    suspend fun getDashboard(): DashboardResponse = withContext(Dispatchers.IO) {
        val resp = get("/api/stats/dashboard")
        gson.fromJson(resp, DashboardResponse::class.java)
    }

    suspend fun getUsers(page: Int, search: String = ""): PagedResponse<UserInfo> = withContext(Dispatchers.IO) {
        val resp = get("/api/users?page=$page&size=20&search=${java.net.URLEncoder.encode(search, "UTF-8")}")
        gson.fromJson(resp, object : TypeToken<PagedResponse<UserInfo>>() {}.type)
    }

    suspend fun getUserDetail(phone: String): Map<String, Any> = withContext(Dispatchers.IO) {
        val resp = get("/api/users/$phone")
        gson.fromJson(resp, object : TypeToken<Map<String, Any>>() {}.type)
    }

    suspend fun setQuota(phone: String, planType: String, remainingQueries: Int, monthlyExpireAt: Long?) = withContext(Dispatchers.IO) {
        val params = mutableMapOf<String, Any>("planType" to planType, "remainingQueries" to remainingQueries)
        if (monthlyExpireAt != null) params["monthlyExpireAt"] = monthlyExpireAt
        put("/api/users/$phone/quota", gson.toJson(params))
    }

    suspend fun getAuditLog(page: Int): PagedResponse<AuditLog> = withContext(Dispatchers.IO) {
        val resp = get("/api/stats/audit-log?page=$page&size=50")
        gson.fromJson(resp, object : TypeToken<PagedResponse<AuditLog>>() {}.type)
    }

    suspend fun getConfig(): Map<String, String> = withContext(Dispatchers.IO) {
        val resp = get("/api/config")
        gson.fromJson(resp, object : TypeToken<Map<String, String>>() {}.type)
    }

    suspend fun updateConfig(key: String, value: String) = withContext(Dispatchers.IO) {
        put("/api/config/$key", gson.toJson(mapOf("value" to value)))
    }

    suspend fun getAdmins(): List<AdminEntry> = withContext(Dispatchers.IO) {
        val resp = get("/api/config/admins")
        gson.fromJson(resp, object : TypeToken<List<AdminEntry>>() {}.type)
    }

    suspend fun createAdmin(username: String, password: String, role: String) = withContext(Dispatchers.IO) {
        post("/api/config/admins", gson.toJson(mapOf("username" to username, "password" to password, "role" to role)))
    }

    suspend fun registerUser(phone: String, password: String, nickname: String?, planType: String, remainingQueries: Int, monthlyExpireAt: Long?): Map<String, Any> = withContext(Dispatchers.IO) {
        val params = mutableMapOf<String, Any>(
            "phone" to phone,
            "password" to password,
            "planType" to planType,
            "remainingQueries" to remainingQueries
        )
        if (!nickname.isNullOrBlank()) params["nickname"] = nickname
        if (monthlyExpireAt != null) params["monthlyExpireAt"] = monthlyExpireAt
        val resp = post("/api/users/register", gson.toJson(params))
        gson.fromJson(resp, object : TypeToken<Map<String, Any>>() {}.type)
    }

    suspend fun resetPassword(phone: String, newPassword: String) = withContext(Dispatchers.IO) {
        post("/api/users/$phone/reset-password", gson.toJson(mapOf("newPassword" to newPassword)))
    }

    suspend fun deleteUser(phone: String) = withContext(Dispatchers.IO) {
        delete("/api/users/$phone")
    }

    // ========== HTTP 底层 ==========
    private fun get(path: String): String {
        val req = Request.Builder().url(baseUrl + path).get()
        token?.let { req.header("Authorization", "Bearer $it") }
        val resp = client.newCall(req.build()).execute()
        val body = resp.body?.string() ?: "{}"
        if (!resp.isSuccessful) {
            val err = try { gson.fromJson(body, ErrorResponse::class.java).error } catch (_: Exception) { body }
            throw ApiException(resp.code, err)
        }
        return body
    }

    private fun post(path: String, json: String): String {
        val body = json.toRequestBody(JSON)
        val req = Request.Builder().url(baseUrl + path).post(body)
        token?.let { req.header("Authorization", "Bearer $it") }
        val resp = client.newCall(req.build()).execute()
        val respBody = resp.body?.string() ?: "{}"
        if (!resp.isSuccessful) {
            val err = try { gson.fromJson(respBody, ErrorResponse::class.java).error } catch (_: Exception) { respBody }
            throw ApiException(resp.code, err)
        }
        return respBody
    }

    private fun put(path: String, json: String): String {
        val body = json.toRequestBody(JSON)
        val req = Request.Builder().url(baseUrl + path).put(body)
        token?.let { req.header("Authorization", "Bearer $it") }
        val resp = client.newCall(req.build()).execute()
        val respBody = resp.body?.string() ?: "{}"
        if (!resp.isSuccessful) {
            val err = try { gson.fromJson(respBody, ErrorResponse::class.java).error } catch (_: Exception) { respBody }
            throw ApiException(resp.code, err)
        }
        return respBody
    }

    private fun delete(path: String): String {
        val req = Request.Builder().url(baseUrl + path).delete()
        token?.let { req.header("Authorization", "Bearer $it") }
        val resp = client.newCall(req.build()).execute()
        val respBody = resp.body?.string() ?: "{}"
        if (!resp.isSuccessful) {
            val err = try { gson.fromJson(respBody, ErrorResponse::class.java).error } catch (_: Exception) { respBody }
            throw ApiException(resp.code, err)
        }
        return respBody
    }
}

class ApiException(val code: Int, override val message: String) : Exception(message)