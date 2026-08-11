package com.lottery.history.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lottery.history.R
import com.lottery.history.network.ChatClient
import com.lottery.history.network.WebRtcClient
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

/**
 * 语音通话客服 Activity（WebRTC 真实通话）
 *
 * 主叫流程：
 *   1. onCreate → ChatClient.sendCallSignal("call") → 等管理员 accept
 *   2. 收到 accept → WebRtcClient.init + createPeerConnection + createOffer
 *   3. offer → ws 发给管理员
 *   4. 收到 answer → setRemoteDescription
 *   5. onIceCandidate → ws 发给管理员
 *   6. 收到对方 candidate → addRemoteIceCandidate
 *   7. onConnectionChange(CONNECTED) → 计时开始
 *
 * 挂断：发送 hangup 信令 + dispose WebRTC
 */
class VoiceCallActivity : AppCompatActivity() {

    private val TAG = "VoiceCallActivity"

    private lateinit var tvStatus: TextView
    private lateinit var tvDuration: TextView
    private lateinit var llCallActions: LinearLayout
    private lateinit var btnHangup: TextView
    private lateinit var btnMute: TextView
    private lateinit var btnSpeaker: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var startedSec = 0
    private var connected = false
    private var muted = false

    private var webRtcClient: WebRtcClient? = null
    private var callId: String? = null
    private var isCaller = true  // 默认主叫（用户主动发起）
    private var remoteSdpReceived = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!connected) return
            startedSec++
            val m = startedSec / 60
            val s = startedSec % 60
            tvDuration.text = "%02d:%02d".format(m, s)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_call)

        tvStatus = findViewById(R.id.tvStatus)
        tvDuration = findViewById(R.id.tvDuration)
        llCallActions = findViewById(R.id.llCallActions)
        btnHangup = findViewById(R.id.btnHangup)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)

        tvStatus.text = "正在呼叫客服..."
        tvDuration.visibility = View.GONE
        llCallActions.visibility = View.GONE

        btnMute.setOnClickListener {
            muted = !muted
            btnMute.text = if (muted) "取消静音" else "静音"
            webRtcClient?.setMuted(muted)
        }
        btnSpeaker.setOnClickListener {
            // WebRTC 自动启用扬声器，这里只切换 UI 状态
            val on = btnSpeaker.text.toString() == "免提"
            btnSpeaker.text = if (on) "取消免提" else "免提"
            // 通过 AudioManager 切换
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.isSpeakerphoneOn = on
        }
        btnHangup.setOnClickListener { hangup() }

        // 初始化 WebRTC
        webRtcClient = WebRtcClient(applicationContext).apply {
            onIceCandidate = { candidate ->
                sendIceCandidate(candidate)
            }
            onRemoteAudio = { /* 远端音频自动播放（WebRTC 内部处理） */ }
            onConnectionChange = { state ->
                runOnUiThread {
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> onCallConnected()
                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            tvStatus.text = "连接失败"
                            hangup()
                        }
                        else -> {}
                    }
                }
            }
        }

        // 订阅信令消息
        lifecycleScope.launch {
            ChatClient.incoming.collectLatest { event ->
                when (event) {
                    is ChatClient.IncomingEvent.CallSignal -> handleCallSignal(event)
                    else -> {}
                }
            }
        }

        // 主动发起呼叫
        startCall()
    }

    private fun startCall() {
        isCaller = true
        tvStatus.text = "正在呼叫客服..."
        // 发起 call 信令
        ChatClient.sendCallSignal(
            type = "call",
            to = "admin",
            payload = mapOf("callId" to "")
        )
    }

    private fun handleCallSignal(event: ChatClient.IncomingEvent.CallSignal) {
        Log.d(TAG, "signal: ${event.type} from=${event.from}")
        when (event.type) {
            "accept" -> {
                // 管理员接听 → 开始 SDP 交换（主叫侧）
                tvStatus.text = "对方已接听，建立通话中..."
                startWebRtc()
            }
            "reject" -> {
                tvStatus.text = "对方拒绝了通话"
                handler.postDelayed({ finish() }, 1500)
            }
            "answer" -> {
                // 收到对方的 answer SDP
                val sdpStr = parseSdpFromPayload(event.payloadJson) ?: return
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpStr)
                lifecycleScope.launch {
                    webRtcClient?.setRemoteDescription(sdp)
                }
            }
            "offer" -> {
                // 被叫模式：收到 offer（当前 Activity 不会作为被叫，用户主动发起）
                // 此处暂不处理被叫场景，由 Web 端发起的通话走其他入口
            }
            "candidate" -> {
                val cand = parseIceCandidateFromPayload(event.payloadJson) ?: return
                webRtcClient?.addRemoteIceCandidate(cand)
            }
            "hangup" -> {
                tvStatus.text = "对方已挂断"
                handler.postDelayed({ finish() }, 1000)
            }
            "call_state" -> {
                // server 推过来的状态变更
            }
        }
    }

    private fun startWebRtc() {
        lifecycleScope.launch {
            try {
                webRtcClient?.init()
                if (webRtcClient?.createPeerConnection() != true) {
                    tvStatus.text = "WebRTC 初始化失败"
                    return@launch
                }
                // 创建 offer
                val offer = webRtcClient!!.createOffer()
                // 通过 ws 发给管理员
                ChatClient.sendCallSignal(
                    type = "offer",
                    to = "admin",
                    payload = mapOf(
                        "type" to "offer",
                        "sdp" to offer.description,
                        "callId" to (callId ?: "")
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "startWebRtc error: ${e.message}", e)
                tvStatus.text = "通话建立失败：${e.message}"
            }
        }
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        ChatClient.sendCallSignal(
            type = "candidate",
            to = "admin",
            payload = mapOf(
                "candidate" to candidate.sdp,
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "callId" to (callId ?: "")
            )
        )
    }

    private fun onCallConnected() {
        connected = true
        tvStatus.text = "通话中..."
        tvDuration.visibility = View.VISIBLE
        llCallActions.visibility = View.VISIBLE
        handler.post(tickRunnable)
    }

    private fun hangup() {
        handler.removeCallbacksAndMessages(null)
        if (connected) {
            connected = false
        }
        // 发送挂断信令
        ChatClient.sendCallSignal(
            type = "hangup",
            to = "admin",
            payload = mapOf("callId" to (callId ?: ""))
        )
        webRtcClient?.dispose()
        finish()
    }

    private fun parseSdpFromPayload(payloadJson: String?): String? {
        if (payloadJson.isNullOrBlank()) return null
        return try {
            val obj = com.google.gson.JsonParser.parseString(payloadJson).asJsonObject
            obj.get("sdp")?.asString
        } catch (e: Exception) { null }
    }

    private fun parseIceCandidateFromPayload(payloadJson: String?): IceCandidate? {
        if (payloadJson.isNullOrBlank()) return null
        return try {
            val obj = com.google.gson.JsonParser.parseString(payloadJson).asJsonObject
            IceCandidate(
                obj.get("sdpMid")?.asString ?: "audio",
                obj.get("sdpMLineIndex")?.asInt ?: 0,
                obj.get("candidate")?.asString ?: ""
            )
        } catch (e: Exception) { null }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        webRtcClient?.dispose()
    }
}
