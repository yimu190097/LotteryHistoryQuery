package com.lottery.history.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lottery.history.R

/**
 * 语音通话客服：模拟"呼叫→接通→挂断"流程。
 *
 * 当前阶段：VoIP 后端未接入，仅 UI 演示（2 秒后自动接通，计时+静音/免提按钮可用）。
 * 后期阶段：接 Agora / 声网 SDK 或 WebRTC，替换 mock 接通逻辑。
 */
class VoiceCallActivity : AppCompatActivity() {

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
    private var speaker = false

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

        // 模拟 2s 后接通
        handler.postDelayed({
            connected = true
            tvStatus.text = "通话中..."
            tvDuration.visibility = android.view.View.VISIBLE
            llCallActions.visibility = android.view.View.VISIBLE
            handler.post(tickRunnable)
        }, 2000)

        btnMute.setOnClickListener {
            muted = !muted
            btnMute.text = if (muted) "取消静音" else "静音"
            // TODO(VoIP): SDK.mute(muted)
        }
        btnSpeaker.setOnClickListener {
            speaker = !speaker
            btnSpeaker.text = if (speaker) "取消免提" else "免提"
            // TODO(VoIP): SDK.setSpeaker(speaker)
        }
        btnHangup.setOnClickListener { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
