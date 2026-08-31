package com.lottery.history.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.lottery.history.AppContext
import com.lottery.history.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 聊天 WebSocket 客户端：单例，全局共享一个连接。
 *
 * 协议（与 server/src/ws/chatServer.js 对应）：
 *   出：{type:"auth", token:"<JWT>"}
 *   出：{type:"chat", to:"admin", payload:{type:"TEXT"|"IMAGE"|"VOICE", text?, mediaPath?, duration?}}
 *   出：{type:"read", to:"admin"}
 *   出：{type:"call"|"offer"|"answer"|"candidate"|"hangup", to:..., payload:{...}}
 *
 *   入：{type:"auth_ok", identity, role}
 *   入：{type:"chat", id, from, role, payload:{type, text, mediaPath, duration}, createdAt, sessionUserPhone}
 *   入：{type:"presence", user, online}（仅管理员会收）
 *   入：{type:"call"|"offer"|"answer"|"candidate"|"hangup", from, payload}
 *   入：{type:"error", error}
 *
 * UI 通过 [incoming] Flow 订阅所有入站消息。
 */
object ChatClient {

    private const val TAG = "ChatClient"
    private const val NORMAL_CLOSURE = 1000

    private val gson = Gson()
    private val sessionStore by lazy { SessionStore(AppContext.get) }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var connected = false
    private var authed = false

    // P1-7: 自动重连（网络抖动/服务重启后自动恢复）。仅当未手动 disconnect 且有登录态时重连。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var shouldReconnect = false
    private var reconnectAttempt = 0
    private var connecting = false
    private var reconnectJob: kotlinx.coroutines.Job? = null

    /** 入站消息流：UI 订阅，收到 server 推送的消息会 emit */
    private val _incoming = MutableSharedFlow<IncomingEvent>(extraBufferCapacity = 64)
    val incoming: SharedFlow<IncomingEvent> = _incoming

    /** WS 连接状态 */
    private val _state = MutableSharedFlow<ConnState>(extraBufferCapacity = 4)
    val state: SharedFlow<ConnState> = _state

    /** 连接（若已连接且已认证则跳过；会启用断线自动重连） */
    fun connect() {
        shouldReconnect = true
        reconnectAttempt = 0
        doConnect()
    }

    private fun doConnect() {
        val token = sessionStore.getToken() ?: run {
            Log.w(TAG, "connect: no token, skip")
            return
        }
        if (connecting) { Log.d(TAG, "already connecting"); return }
        if (connected && authed) {
            Log.d(TAG, "already connected & authed")
            return
        }
        connecting = true
        val url = ApiClient.BASE_URL.replaceFirst("^http".toRegex(), "ws") + "/ws"
        Log.d(TAG, "connecting to $url")
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connecting = false
                reconnectAttempt = 0
                connected = true
                Log.d(TAG, "ws open, sending auth")
                // 发送 auth 帧
                sendRaw(gson.toJson(mapOf("type" to "auth", "token" to token)))
                _state.tryEmit(ConnState.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncoming(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false; authed = false; connecting = false
                Log.w(TAG, "ws closed: $code/$reason")
                _state.tryEmit(ConnState.DISCONNECTED)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false; authed = false; connecting = false
                Log.e(TAG, "ws failure: ${t.message}")
                _state.tryEmit(ConnState.ERROR)
                scheduleReconnect()
            }
        })
    }

    /** P1-7: 指数退避重连（1s,2s,4s…封顶 30s），仅当未手动断开且仍有登录态时执行 */
    private fun scheduleReconnect() {
        if (!shouldReconnect || sessionStore.getToken().isNullOrBlank()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val attempts = reconnectAttempt
            val delayMs = Math.min(1000L shl attempts, 30_000L)
            reconnectAttempt = attempts + 1
            delay(delayMs)
            if (shouldReconnect && scope.isActive) doConnect()
        }
    }

    /** 主动断开（会停止自动重连，直到下次 connect()） */
    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        ws?.close(NORMAL_CLOSURE, "user disconnect")
        ws = null
        connected = false; authed = false; connecting = false
    }

    /** 发送聊天消息（用户 → 管理员） */
    fun sendChatToAdmin(payload: ChatPayload) {
        val msg = mapOf(
            "type" to "chat",
            "to" to "admin",
            "payload" to payload
        )
        sendRaw(gson.toJson(msg))
    }

    /** 发送已读标记 */
    fun sendRead() {
        sendRaw(gson.toJson(mapOf("type" to "read", "to" to "admin")))
    }

    /** WebRTC 信令转发 */
    fun sendCallSignal(type: String, to: String, payload: Map<String, Any?>) {
        val msg: MutableMap<String, Any?> = mutableMapOf("type" to type, "to" to to)
        msg["payload"] = payload
        sendRaw(gson.toJson(msg))
    }

    private fun sendRaw(text: String): Boolean {
        val w = ws ?: return false
        return w.send(text)
    }

    private fun handleIncoming(raw: String) {
        val obj = try {
            gson.fromJson(raw, JsonObject::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "bad json: $raw"); return
        }
        val type = obj.get("type")?.asString ?: return

        when (type) {
            "auth_ok" -> {
                authed = true
                Log.d(TAG, "auth ok: ${obj.get("identity")?.asString}")
                _state.tryEmit(ConnState.AUTHED)
            }
            "auth_fail" -> {
                Log.w(TAG, "auth failed: ${obj.get("error")?.asString}")
                authed = false
                _state.tryEmit(ConnState.AUTH_FAILED)
            }
            "chat" -> {
                val id = obj.get("id")?.asLong ?: return
                val role = obj.get("role")?.asString ?: return
                val payloadObj = obj.getAsJsonObject("payload") ?: return
                val createdAt = obj.get("createdAt")?.asLong ?: System.currentTimeMillis()
                val payload = ChatPayload(
                    type = payloadObj.get("type")?.asString ?: "TEXT",
                    text = payloadObj.get("text")?.takeIf { !it.isJsonNull }?.asString,
                    mediaPath = payloadObj.get("mediaPath")?.takeIf { !it.isJsonNull }?.asString,
                    duration = payloadObj.get("duration")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                )
                _incoming.tryEmit(IncomingEvent.Chat(id = id, role = role, payload = payload, createdAt = createdAt))
            }
            "presence" -> {
                val user = obj.get("user")?.asString ?: return
                val online = obj.get("online")?.asBoolean ?: return
                _incoming.tryEmit(IncomingEvent.Presence(userPhone = user, online = online))
            }
            "call", "offer", "answer", "candidate", "hangup" -> {
                val from = obj.get("from")?.asString ?: return
                val payload = obj.getAsJsonObject("payload")?.toString()
                _incoming.tryEmit(IncomingEvent.CallSignal(type = type, from = from, payloadJson = payload))
            }
            "error" -> {
                val err = obj.get("error")?.asString ?: "unknown"
                Log.w(TAG, "server error: $err")
                _incoming.tryEmit(IncomingEvent.Error(message = err))
            }
        }
    }

    // ============ 数据模型 ============

    data class ChatPayload(
        val type: String,             // TEXT / IMAGE / VOICE
        val text: String? = null,
        val mediaPath: String? = null,  // 完整 URL 或本地路径
        val duration: Int = 0
    )

    sealed class IncomingEvent {
        data class Chat(
            val id: Long,
            val role: String,           // "SENT" / "RECEIVED"
            val payload: ChatPayload,
            val createdAt: Long
        ) : IncomingEvent()

        data class Presence(val userPhone: String, val online: Boolean) : IncomingEvent()
        data class CallSignal(val type: String, val from: String, val payloadJson: String?) : IncomingEvent()
        data class Error(val message: String) : IncomingEvent()
    }

    enum class ConnState { DISCONNECTED, CONNECTED, AUTHED, AUTH_FAILED, ERROR }
}
