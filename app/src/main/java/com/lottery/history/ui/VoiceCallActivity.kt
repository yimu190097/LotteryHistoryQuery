package com.lottery.history.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.lottery.history.databinding.ActivityVoiceCallBinding
import java.util.Locale

/**
 * 语音通话客服（模拟）。
 *
 * 状态机：
 *   CALLING (3s 呼叫中) -> CONNECTED (通话中，开始计时) -> 用户挂断 -> finish
 *
 * 后期接入真实 VoIP 后端时，只需替换呼叫/挂断逻辑即可。
 */
class VoiceCallActivity : AppCompatActivity() {

    private enum class State { CALLING, CONNECTED, ENDED }

    private lateinit var binding: ActivityVoiceCallBinding
    private val handler = Handler(Looper.getMainLooper())

    private var state: State = State.CALLING
    private var connectedAtElapsed: Long = 0L

    private var muted = false
    private var speakerOn = false

    /** 呼叫 3 秒后自动接通 */
    private val autoConnect = Runnable { connect() }

    /** 通话中每秒刷新计时 */
    private val tick = object : Runnable {
        override fun run() {
            if (state != State.CONNECTED) return
            val sec = ((SystemClock.elapsedRealtime() - connectedAtElapsed) / 1000).toInt()
            val mm = sec / 60
            val ss = sec % 60
            binding.tvDuration.text = String.format(Locale.getDefault(), "%02d:%02d", mm, ss)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()

        binding.btnHangup.setOnClickListener { hangup() }
        binding.btnMute.setOnClickListener { toggleMute() }
        binding.btnSpeaker.setOnClickListener { toggleSpeaker() }

        // 使用新版返回回调替代已废弃的 onBackPressed
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                hangup()
            }
        })

        startCalling()
    }

    private fun startCalling() {
        state = State.CALLING
        binding.tvStatus.text = "正在呼叫..."
        binding.tvDuration.visibility = View.GONE
        binding.llCallActions.visibility = View.GONE
        handler.postDelayed(autoConnect, 3000)
    }

    private fun connect() {
        if (state != State.CALLING) return
        state = State.CONNECTED
        connectedAtElapsed = SystemClock.elapsedRealtime()
        binding.tvStatus.text = "通话中"
        binding.tvDuration.visibility = View.VISIBLE
        binding.tvDuration.text = "00:00"
        binding.llCallActions.visibility = View.VISIBLE
        handler.post(tick)
    }

    private fun toggleMute() {
        muted = !muted
        // 激活态用红色背景提示
        binding.btnMute.setBackgroundResource(
            if (muted) com.lottery.history.R.drawable.bg_hangup_circle
            else com.lottery.history.R.drawable.bg_call_btn_circle
        )
        binding.btnMute.text = if (muted) "取消静音" else "静音"
    }

    private fun toggleSpeaker() {
        speakerOn = !speakerOn
        binding.btnSpeaker.setBackgroundResource(
            if (speakerOn) com.lottery.history.R.drawable.bg_hangup_circle
            else com.lottery.history.R.drawable.bg_call_btn_circle
        )
        binding.btnSpeaker.text = if (speakerOn) "关闭免提" else "免提"
    }

    private fun hangup() {
        state = State.ENDED
        handler.removeCallbacks(autoConnect)
        handler.removeCallbacks(tick)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(autoConnect)
        handler.removeCallbacks(tick)
    }
}
