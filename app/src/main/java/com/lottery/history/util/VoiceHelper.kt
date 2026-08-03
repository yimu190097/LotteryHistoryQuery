package com.lottery.history.util

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 客服聊天 - 语音录制与播放工具。
 *
 * - 录制：MediaRecorder -> 3gp 文件，按起止时间计时返回秒数。
 * - 播放：MediaPlayer，支持开始 / 停止 / 单条结束回调。
 *
 * 调用方需自行申请 RECORD_AUDIO 权限。
 */
class VoiceHelper(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null

    private var recordFile: File? = null
    private var recordStartElapsed: Long = 0L

    private val handler = Handler(Looper.getMainLooper())

    /** 录音达到最大时长（60秒）自动结束并回调 */
    private var maxDurationReached: (() -> Unit)? = null
    private val maxDurationCheck = object : Runnable {
        override fun run() {
            val seconds = ((SystemClock.elapsedRealtime() - recordStartElapsed) / 1000).toInt()
            if (seconds >= MAX_RECORD_SECONDS) {
                // 达到上限，自动结束
                val result = stopRecording()
                if (result != null) {
                    maxDurationReached?.invoke()
                }
            } else {
                handler.postDelayed(this, 500)
            }
        }
    }

    /** 当前播放语音文件路径，用于防止重复点击 */
    var currentPlayingPath: String? = null
        private set

    /** 播放进度回调（每 200ms 回调一次当前秒数），用于驱动声波动画 */
    private var playingCallback: ((seconds: Int) -> Unit)? = null
    private var playingStartElapsed: Long = 0L

    private val playingTimer = object : Runnable {
        override fun run() {
            val sec = ((SystemClock.elapsedRealtime() - playingStartElapsed) / 1000).toInt()
            playingCallback?.invoke(sec)
            handler.postDelayed(this, 200)
        }
    }

    // ===================== 录制 =====================

    /**
     * 开始录制。返回临时文件路径，结束时返回实际保存文件 + 时长。
     * @param onMaxDuration 达到最大时长（60秒）自动结束并回调。
     */
    fun startRecording(onMaxDuration: (() -> Unit)? = null): File? {
        if (recorder != null) return null
        return try {
            val dir = File(context.filesDir, "voice_messages").apply { if (!exists()) mkdirs() }
            val name = "vm_" + SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.getDefault())
                .format(Date(System.currentTimeMillis())) + ".3gp"
            val file = File(dir, name)
            val r = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = r
            recordFile = file
            recordStartElapsed = SystemClock.elapsedRealtime()
            maxDurationReached = onMaxDuration
            handler.postDelayed(maxDurationCheck, 500)
            file
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            recorder?.releaseSafely()
            recorder = null
            recordFile = null
            null
        }
    }

    /**
     * 结束录制。
     * @return Pair(文件, 秒数)；时长 < 1 秒视为无效，返回 null 并删除文件。
     */
    fun stopRecording(): Pair<File, Int>? {
        val r = recorder ?: return null
        handler.removeCallbacks(maxDurationCheck)
        val file = recordFile
        val seconds = ((SystemClock.elapsedRealtime() - recordStartElapsed) / 1000).toInt()
        try {
            r.stop()
            r.releaseSafely()
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording failed", e)
            r.releaseSafely()
        } finally {
            recorder = null
            recordFile = null
        }
        // 过短录音丢弃，避免误触
        if (file == null || seconds < 1) {
            file?.takeIf { it.exists() }?.delete()
            return null
        }
        return file to seconds
    }

    /** 取消录制：不保存文件 */
    fun cancelRecording() {
        val r = recorder ?: return
        handler.removeCallbacks(maxDurationCheck)
        try {
            r.stop()
        } catch (_: Exception) {
            // stop 失败也要释放
        }
        r.releaseSafely()
        recorder = null
        recordFile?.takeIf { it.exists() }?.delete()
        recordFile = null
    }

    // ===================== 播放 =====================

    /**
     * 播放语音文件。若当前正在播放同一文件，则停止；否则停止旧的并播放新的。
     * @param path 语音文件绝对路径
     * @param onProgress 播放进度回调（每 200ms）
     * @param onComplete 播放完成回调
     */
    fun play(path: String, onProgress: ((Int) -> Unit)? = null, onComplete: (() -> Unit)? = null) {
        // 同一条再次点击：停止
        if (currentPlayingPath == path && player?.isPlaying == true) {
            stop()
            return
        }
        stop()
        playingCallback = onProgress
        try {
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.setOnCompletionListener {
                stopInternal()
                onComplete?.invoke()
            }
            mp.setOnErrorListener { _, _, _ ->
                stopInternal()
                onComplete?.invoke()
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            currentPlayingPath = path
            playingStartElapsed = SystemClock.elapsedRealtime()
            handler.post(playingTimer)
        } catch (e: Exception) {
            Log.e(TAG, "play failed: $path", e)
            stopInternal()
            onComplete?.invoke()
        }
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        handler.removeCallbacks(playingTimer)
        player?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
                mp.reset()
                mp.release()
            } catch (_: Exception) {
            }
        }
        player = null
        currentPlayingPath = null
        playingCallback = null
    }

    /** 释放全部资源，建议在 Activity onDestroy 调用 */
    fun release() {
        cancelRecording()
        stopInternal()
    }

    private fun MediaRecorder.releaseSafely() {
        try {
            release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "VoiceHelper"
        /** 单条语音最大时长：60 秒（与微信一致） */
        const val MAX_RECORD_SECONDS = 60
    }
}
