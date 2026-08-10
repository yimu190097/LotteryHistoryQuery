package com.lottery.history.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音工具：封装 MediaRecorder 开始/停止，返回本地文件路径。
 * 后期接后台时：上传文件到服务器返回 URL。
 */
class VoiceHelper(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startAtMs: Long = 0L

    private val outputDir: File by lazy {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "voice")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    /** 开始录音，文件按时间戳命名 */
    fun startRecord() {
        if (recorder != null) return
        val fileName = "voice_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())}.m4a"
        currentFile = File(outputDir, fileName)
        startAtMs = System.currentTimeMillis()
        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(44_100)
            setOutputFile(currentFile!!.absolutePath)
            prepare()
            start()
        }
    }

    /** 停止录音，返回文件路径 + 时长（秒）；时长<1s 视为取消返回 null */
    fun stopRecord(): Pair<String, Int>? {
        val r = recorder ?: return null
        val file = currentFile
        recorder = null
        currentFile = null
        return runCatching {
            r.stop()
            r.release()
            val durationMs = System.currentTimeMillis() - startAtMs
            val durationS = (durationMs / 1000).toInt()
            if (durationS < 1 || file == null) {
                file?.delete()
                null
            } else {
                file.absolutePath to durationS
            }
        }.getOrNull()
    }

    /** 取消：释放并删除临时文件 */
    fun cancelRecord() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop(); r.release() }
        currentFile?.delete()
        currentFile = null
    }
}
