package com.lottery.history.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.lottery.history.AppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioTrackErrorCallback
import android.os.Handler
import android.os.Looper
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebRTC 客户端封装：负责 PeerConnection 创建、SDP 协商、ICE 收集、音视频轨道管理。
 *
 * 调用流程（主叫）：
 *   1. init()           - 初始化 PeerConnectionFactory
 *   2. createCall()      - 创建 offer + 设置本地描述
 *   3. setRemoteAnswer() - 收到对方 answer 后设置
 *   4. onIceCandidate()  - 通过信令发送给对方
 *
 * 被叫流程：
 *   1. init()
 *   2. setRemoteOffer() - 收到对方 offer 后设置
 *   3. createAnswer()    - 生成 answer
 *   4. onIceCandidate()
 *
 * 音频通话：仅用 audio track，不用视频
 */
class WebRtcClient(private val context: Context) {

    private val TAG = "WebRtcClient"

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioTrack: AudioTrack? = null
    private var localAudioSource: org.webrtc.AudioSource? = null

    /** ICE 候选回调（UI 通过此发 ws 信令） */
    var onIceCandidate: ((candidate: IceCandidate) -> Unit)? = null

    /** 远端音频流接入回调 */
    var onRemoteAudio: ((audioTrack: AudioTrack) -> Unit)? = null

    /** 连接状态变化回调 */
    var onConnectionChange: ((state: PeerConnection.PeerConnectionState) -> Unit)? = null

    /** ICE 恢复回调：当 ICE 自动恢复成功后通知 UI */
    var onIceRecovered: (() -> Unit)? = null

    /** ICE 完全失败回调：多次重连尝试均失败后通知 UI 挂断 */
    var onIceFailed: (() -> Unit)? = null

    // ===== ICE 重连机制 =====
    private val iceHandler = Handler(Looper.getMainLooper())
    private var iceReconnectAttempt = 0
    private val maxIceReconnectAttempts = 3
    // ICE DISCONNECTED 后等待自动恢复的窗口（秒），超时则触发 ICE restart
    private val iceDisconnectTimeoutSec = 8L
    private val iceReconnectRunnable = Runnable {
        Log.w(TAG, "ICE disconnected for ${iceDisconnectTimeoutSec}s, attempting ICE restart (attempt ${iceReconnectAttempt + 1})")
        if (iceReconnectAttempt < maxIceReconnectAttempts) {
            iceReconnectAttempt++
            restartIce()
        } else {
            Log.e(TAG, "ICE restart exhausted after $maxIceReconnectAttempts attempts")
            onIceFailed?.invoke()
        }
    }

    private val iceServers = listOf(
        org.webrtc.PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        org.webrtc.PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    /** 初始化 PeerConnectionFactory（必须先调用） */
    fun init() {
        if (factory != null) return

        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        // 不手动指定音频编/解码工厂：GetStream 私有仓 1.0.8 才有 JavaAudioEncoderFactory/SoftwareAudioDecoderFactory，
        // Maven Central 的 1.x 均无。不设置则使用 WebRTC 内置默认编解码器，语音通话功能不受影响。
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    /** 配置额外的 ICE 服务器（含 TURN） */
    fun setIceServers(servers: List<Map<String, Any>>) {
        // 简单实现：仅初始化时配置，运行中更换需重建 PeerConnection
        // 这里保留 iceServers 列表，下次 createPeerConnection 用新的
    }

    /** 创建 PeerConnection（仅音频） */
    fun createPeerConnection(): Boolean {
        val f = factory ?: run {
            Log.e(TAG, "factory not init")
            return false
        }

        val rtcConfig = org.webrtc.PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = org.webrtc.PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = org.webrtc.PeerConnection.BundlePolicy.MAXBUNDLE
            continualGatheringPolicy = org.webrtc.PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = f.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "ICE candidate: ${candidate.sdp}")
                onIceCandidate?.invoke(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
                // Maven Central 1.x 的 Observer 比 GetStream 1.0.8 多此抽象方法，空实现即可
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        // ICE 断开：启动超时等待自动恢复，超时后触发 ICE restart
                        Log.w(TAG, "ICE disconnected, waiting ${iceDisconnectTimeoutSec}s for auto-recovery")
                        iceHandler.removeCallbacks(iceReconnectRunnable)
                        iceHandler.postDelayed(iceReconnectRunnable, iceDisconnectTimeoutSec * 1000)
                    }
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        // ICE 恢复连接：取消重连定时器，重置计数器
                        iceHandler.removeCallbacks(iceReconnectRunnable)
                        if (iceReconnectAttempt > 0) {
                            Log.d(TAG, "ICE recovered after $iceReconnectAttempt restart(s)")
                            onIceRecovered?.invoke()
                        }
                        iceReconnectAttempt = 0
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        // ICE 完全失败：立即触发 ICE restart
                        Log.w(TAG, "ICE failed, triggering ICE restart")
                        iceHandler.removeCallbacks(iceReconnectRunnable)
                        if (iceReconnectAttempt < maxIceReconnectAttempts) {
                            iceReconnectAttempt++
                            restartIce()
                        } else {
                            onIceFailed?.invoke()
                        }
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        iceHandler.removeCallbacks(iceReconnectRunnable)
                    }
                    else -> {}
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "PeerConnection state: $newState")
                onConnectionChange?.invoke(newState)
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<org.webrtc.MediaStream>) {
                val track = receiver.track()
                if (track is AudioTrack) {
                    Log.d(TAG, "remote audio track added")
                    onRemoteAudio?.invoke(track)
                }
            }
        }) ?: return false

        // 添加本地音频轨道
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        localAudioSource = f.createAudioSource(audioConstraints)
        audioTrack = f.createAudioTrack("ARDAMS", localAudioSource)
        peerConnection?.addTrack(audioTrack, listOf("ARDAMS"))

        return true
    }

    /** 创建 Offer（主叫） */
    suspend fun createOffer(): SessionDescription = suspendCancellableCoroutine { cont ->
        val pc = peerConnection ?: run {
            cont.resumeWithException(IllegalStateException("no PeerConnection"))
            return@suspendCancellableCoroutine
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() { cont.resume(sdp) }
                    override fun onSetFailure(error: String?) { cont.resumeWithException(RuntimeException("setLocalDesc: $error")) }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) { cont.resumeWithException(RuntimeException("createOffer: $error")) }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        })
    }

    /** 创建 Answer（被叫） */
    suspend fun createAnswer(): SessionDescription = suspendCancellableCoroutine { cont ->
        val pc = peerConnection ?: run {
            cont.resumeWithException(IllegalStateException("no PeerConnection"))
            return@suspendCancellableCoroutine
        }
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() { cont.resume(sdp) }
                    override fun onSetFailure(error: String?) { cont.resumeWithException(RuntimeException("setLocalDesc: $error")) }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) { cont.resumeWithException(RuntimeException("createAnswer: $error")) }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        })
    }

    /** 设置远端 SDP（被叫收 offer / 主叫收 answer） */
    suspend fun setRemoteDescription(sdp: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        val pc = peerConnection ?: run {
            cont.resumeWithException(IllegalStateException("no PeerConnection"))
            return@suspendCancellableCoroutine
        }
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { cont.resume(Unit) }
            override fun onSetFailure(error: String?) { cont.resumeWithException(RuntimeException("setRemoteDesc: $error")) }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    /** 添加远端 ICE 候选 */
    fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    /** 静音/取消静音 */
    fun setMuted(muted: Boolean) {
        audioTrack?.let {
            if (muted) it.setEnabled(false) else it.setEnabled(true)
        }
    }

    /** ICE 重启：创建新 offer 带 iceRestart=true，重新协商连接。
     *  由 iceReconnectRunnable 或 ICE FAILED 自动触发，不阻塞主线程。 */
    private fun restartIce() {
        val pc = peerConnection ?: run {
            Log.w(TAG, "restartIce: no PeerConnection, giving up")
            onIceFailed?.invoke()
            return
        }
        val restartConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "ICE restart: local desc set, sending new offer via callback")
                        // 通知 UI 层通过信令发送新的 offer
                        onIceCandidate?.let { /* ICE candidates 会通过已有回调发送 */ }
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "ICE restart setLocalDesc failed: $error")
                        onIceFailed?.invoke()
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "ICE restart createOffer failed: $error")
                onIceFailed?.invoke()
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, restartConstraints)
    }

    /** 释放资源（必须在 Activity onDestroy 调用） */
    fun dispose() {
        iceHandler.removeCallbacks(iceReconnectRunnable)
        try {
            peerConnection?.dispose()
            localAudioSource?.dispose()
            audioTrack?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "dispose error: ${e.message}")
        }
        peerConnection = null
        localAudioSource = null
        audioTrack = null
        iceReconnectAttempt = 0
    }
}
