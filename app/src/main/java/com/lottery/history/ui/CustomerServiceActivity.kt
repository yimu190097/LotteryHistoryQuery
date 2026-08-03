package com.lottery.history.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lottery.history.R
import com.lottery.history.adapter.ChatAdapter
import com.lottery.history.data.AuthRepository
import com.lottery.history.databinding.ActivityCustomerServiceBinding
import com.lottery.history.db.ChatMessageEntity
import com.lottery.history.db.ChatRole
import com.lottery.history.db.ChatType
import com.lottery.history.db.LotteryDatabase
import com.lottery.history.util.VoiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 联系客服 - 微信式聊天。
 *
 * 支持：
 * - 文本消息
 * - 付款截图（Photo Picker，无需手动申请读权限）
 * - 语音消息（按住说话，松开发送；上滑取消；最长60秒自动发送）
 * - 语音联系客服（跳转 VoiceCallActivity 模拟呼叫）
 *
 * 消息持久化到 Room，进入页面后自动订阅最新列表。
 */
class CustomerServiceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomerServiceBinding
    private lateinit var adapter: ChatAdapter
    private lateinit var voiceHelper: VoiceHelper
    private val dao by lazy { LotteryDatabase.get(this).chatMessageDao() }

    /** 是否已发送过欢迎语（避免每次进入页面都重复插入） */
    private var welcomed = false

    /** 录音按住时的起始 Y 坐标，用于判断上滑取消 */
    private var touchDownY = 0f
    private var isRecording = false

    /** 语音模式标记：true=按住说话模式，false=文本输入模式 */
    private var voiceMode = false

    /** 权限请求：录音 */
    private val recordPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "需要麦克风权限才能发送语音消息", Toast.LENGTH_SHORT).show()
        }
    }

    /** 图片选择：Photo Picker（13+ 原生，<13 自动回退，无需读权限） */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            sendImageMessage(uri)
        }
    }

    /** 软键盘监听：键盘弹出时滚动到底部 */
    private var keyboardListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomerServiceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voiceHelper = VoiceHelper(this)

        setupToolbar()
        setupRecyclerView()
        setupInputBar()
        setupKeyboardScroll()
        observeMessages()
    }

    private fun setupToolbar() {
        binding.tvBack.setOnClickListener { finish() }
        binding.tvVoiceCall.setOnClickListener {
            startActivity(Intent(this, VoiceCallActivity::class.java))
        }
    }

    private fun setupRecyclerView() {
        adapter = ChatAdapter { msg -> onVoiceMessageClick(msg) }
        val lm = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvChat.layoutManager = lm
        binding.rvChat.adapter = adapter
    }

    private fun setupInputBar() {
        // 切换 文本 <-> 语音
        binding.ivToggleVoice.setOnClickListener {
            voiceMode = !voiceMode
            binding.btnHoldToTalk.visibility = if (voiceMode) View.VISIBLE else View.GONE
            binding.etMessage.visibility = if (voiceMode) View.GONE else View.VISIBLE
            // 语音模式下隐藏发送按钮（没有文本可发）
            binding.btnSendText.visibility = if (voiceMode) View.GONE else View.VISIBLE
            // 文字按钮标注当前可切换到的模式
            binding.ivToggleVoice.text = if (voiceMode) "文字" else "语音"
            if (!voiceMode) {
                // 切回文本模式时刷新发送按钮可用态
                updateSendButtonState(binding.etMessage.text.toString())
            }
        }

        // 文本输入监听：空内容时禁用发送按钮样式
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateSendButtonState(s?.toString().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 发送文本：点击按钮
        binding.btnSendText.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendTextMessage(text)
                binding.etMessage.text?.clear()
            }
        }

        // 选图：Photo Picker
        binding.ivSendImage.setOnClickListener {
            pickImageLauncher.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }

        // 按住说话
        binding.btnHoldToTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownY = event.rawY
                    startRecording()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = touchDownY - event.rawY
                    binding.btnHoldToTalk.text = if (dy > 80) "松开 取消" else "松开 发送"
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = touchDownY - event.rawY
                    binding.btnHoldToTalk.text = "按住 说话"
                    if (dy > 80) cancelRecording() else finishRecording()
                }
            }
            true
        }

        // 初始化发送按钮状态
        updateSendButtonState("")
    }

    private fun updateSendButtonState(text: String) {
        val empty = text.isBlank()
        binding.btnSendText.isEnabled = !empty
        binding.btnSendText.alpha = if (empty) 0.5f else 1.0f
    }

    /** 监听根布局高度变化，键盘弹出时自动滚动到底部 */
    private fun setupKeyboardScroll() {
        val rootView = window.decorView.rootView
        keyboardListener = ViewTreeObserver.OnGlobalLayoutListener {
            val r = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(r)
            val screenHeight = rootView.height
            val keypadHeight = screenHeight - r.bottom
            if (keypadHeight > screenHeight * 0.15) {
                // 键盘弹出，滚动到底部
                scrollToBottom()
            }
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(keyboardListener)
    }

    private fun scrollToBottom() {
        binding.rvChat.post {
            val count = adapter.itemCount
            if (count > 0) binding.rvChat.smoothScrollToPosition(count - 1)
        }
    }

    // ===================== 消息订阅 =====================

    private fun observeMessages() {
        lifecycleScope.launch {
            dao.observeAll().collectLatest { list ->
                // 首次进入空列表：插入欢迎语
                if (!welcomed && list.isEmpty()) {
                    welcomed = true
                    insertReceived(
                        ChatType.TEXT,
                        text = "您好，客服小助手为您服务。请描述您的问题，或直接发送付款截图与语音说明。"
                    )
                    return@collectLatest
                }
                welcomed = true
                adapter.submitList(list) {
                    if (list.isNotEmpty()) {
                        binding.rvChat.smoothScrollToPosition(list.size - 1)
                    }
                }
            }
        }
    }

    // ===================== 发送消息 =====================

    private fun sendTextMessage(text: String) {
        lifecycleScope.launch {
            dao.insert(
                ChatMessageEntity(
                    role = ChatRole.SENT,
                    type = ChatType.TEXT,
                    text = text,
                    createdAt = System.currentTimeMillis()
                )
            )
            triggerAutoReply()
        }
    }

    private fun sendImageMessage(uri: Uri) {
        Toast.makeText(this, "正在保存图片...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val savedPath = withContext(Dispatchers.IO) { copyImageToInternal(uri) }
            if (savedPath == null) {
                Toast.makeText(this@CustomerServiceActivity, "图片读取失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            dao.insert(
                ChatMessageEntity(
                    role = ChatRole.SENT,
                    type = ChatType.IMAGE,
                    mediaPath = savedPath,
                    text = "付款截图",
                    createdAt = System.currentTimeMillis()
                )
            )
            triggerAutoReply()
        }
    }

    // ===================== 语音录制 =====================

    private fun startRecording() {
        if (!hasRecordPermission()) {
            recordPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        val file = voiceHelper.startRecording(onMaxDuration = {
            // 达到 60 秒上限，自动发送
            runOnUiThread {
                Toast.makeText(this, "已达最长60秒，自动发送", Toast.LENGTH_SHORT).show()
                finishRecording()
            }
        })
        if (file == null) {
            Toast.makeText(this, "无法启动录音，请检查权限", Toast.LENGTH_SHORT).show()
        } else {
            isRecording = true
            binding.btnHoldToTalk.text = "松开 发送"
        }
    }

    private fun finishRecording() {
        if (!isRecording) return
        isRecording = false
        val result = voiceHelper.stopRecording()
        if (result == null) {
            Toast.makeText(this, "录音太短，已取消", Toast.LENGTH_SHORT).show()
            return
        }
        val (file, seconds) = result
        lifecycleScope.launch {
            dao.insert(
                ChatMessageEntity(
                    role = ChatRole.SENT,
                    type = ChatType.VOICE,
                    mediaPath = file.absolutePath,
                    duration = seconds,
                    createdAt = System.currentTimeMillis()
                )
            )
            triggerAutoReply()
        }
    }

    private fun cancelRecording() {
        if (!isRecording) return
        isRecording = false
        voiceHelper.cancelRecording()
        Toast.makeText(this, "已取消", Toast.LENGTH_SHORT).show()
    }

    // ===================== 语音播放 =====================

    private fun onVoiceMessageClick(msg: ChatMessageEntity) {
        val path = msg.mediaPath ?: return
        if (!File(path).exists()) {
            Toast.makeText(this, "语音文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        // 同一条再次点击：停止
        if (voiceHelper.currentPlayingPath == path) {
            voiceHelper.stop()
            adapter.playingVoiceId = null
            return
        }
        adapter.playingVoiceId = msg.id
        voiceHelper.play(
            path = path,
            onComplete = { adapter.playingVoiceId = null }
        )
    }

    // ===================== 客服自动回复（本地模拟） =====================

    private var replyJob: kotlinx.coroutines.Job? = null

    private fun triggerAutoReply() {
        replyJob?.cancel()
        replyJob = lifecycleScope.launch {
            delay(800)
            insertReceived(ChatType.TEXT, text = pickAutoReply())
        }
    }

    private fun pickAutoReply(): String {
        val phone = AuthRepository(this).currentPhone()
        val masked = if (phone != null && phone.length == 11) {
            "${phone.substring(0, 3)}****${phone.substring(7)}"
        } else null
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val greetings = if (masked != null) "收到您的消息（$masked）。" else "收到您的消息。"
        val followUps = listOf(
            "客服正在为您核实，请稍候 $now。",
            "如已付款，请附带付款截图，便于尽快处理。",
            "您也可以点击右上角【语音】按钮直接呼叫我。"
        )
        return greetings + followUps.random()
    }

    private suspend fun insertReceived(type: ChatType, text: String? = null, mediaPath: String? = null) {
        dao.insert(
            ChatMessageEntity(
                role = ChatRole.RECEIVED,
                type = type,
                text = text,
                mediaPath = mediaPath,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    // ===================== 工具 =====================

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 把相册返回的 Uri 内容复制到 app 内部目录，保证后续路径持续可用 */
    private fun copyImageToInternal(uri: Uri): String? {
        return try {
            val dir = File(filesDir, "chat_images").apply { if (!exists()) mkdirs() }
            val name = "img_" + System.currentTimeMillis() + ".jpg"
            val dest = File(dir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            dest.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceHelper.release()
        keyboardListener?.let {
            window.decorView.rootView.viewTreeObserver.removeOnGlobalLayoutListener(it)
        }
    }
}
