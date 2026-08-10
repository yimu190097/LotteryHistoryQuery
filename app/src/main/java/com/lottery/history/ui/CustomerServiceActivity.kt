package com.lottery.history.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.lottery.history.R
import com.lottery.history.adapter.ChatAdapter
import com.lottery.history.db.ChatMessageDao
import com.lottery.history.db.ChatMessageEntity
import com.lottery.history.db.ChatRole
import com.lottery.history.db.ChatType
import com.lottery.history.db.LotteryDatabase
import com.lottery.history.util.VoiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 联系客服（聊天）Activity。
 * 三类型消息：文字 / 图片（付款截图）/ 语音。
 *
 * 当前阶段：后端未接入，模拟接收方自动回复，所有消息本地持久化。
 * 后期阶段：接入 WebSocket 推送真实客服消息。
 */
class CustomerServiceActivity : AppCompatActivity() {

    private lateinit var rvChat: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var dao: ChatMessageDao
    private lateinit var etMessage: EditText
    private lateinit var btnSend: TextView
    private lateinit var btnSendImage: TextView
    private lateinit var ivToggleVoice: TextView
    private lateinit var btnHoldToTalk: TextView
    private lateinit var llInputBar: LinearLayout
    private lateinit var voiceHelper: VoiceHelper

    private val REQ_PERM_VOICE = 1001
    private val REQ_PERM_IMAGE = 1002
    private val REQ_PICK_IMAGE = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customer_service)

        dao = LotteryDatabase.get(this).chatMessageDao()
        voiceHelper = VoiceHelper(this)

        findViewById<TextView>(R.id.tvBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvVoiceCall).setOnClickListener {
            startActivity(Intent(this, VoiceCallActivity::class.java))
        }

        rvChat = findViewById(R.id.rvChat)
        rvChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter = ChatAdapter()
        rvChat.adapter = adapter

        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSendText)
        btnSendImage = findViewById(R.id.ivSendImage)
        ivToggleVoice = findViewById(R.id.ivToggleVoice)
        btnHoldToTalk = findViewById(R.id.btnHoldToTalk)
        llInputBar = findViewById(R.id.llInputBar)

        btnSend.setOnClickListener { sendText() }
        btnSendImage.setOnClickListener { trySendImage() }
        ivToggleVoice.setOnClickListener { toggleVoiceMode() }
        btnHoldToTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> startVoiceSafe()
                MotionEvent.ACTION_UP -> {
                    val (path, dur) = voiceHelper.stopRecord() ?: return@setOnTouchListener true
                    lifecycleScope.launch { saveAndReply(ChatType.VOICE, mediaPath = path, duration = dur) }
                }
                MotionEvent.ACTION_CANCEL -> voiceHelper.cancelRecord()
            }
            true
        }

        // 订阅消息
        lifecycleScope.launch {
            dao.observeAll().collectLatest { list ->
                adapter.submitList(list) {
                    if (list.isNotEmpty()) rvChat.smoothScrollToPosition(list.size - 1)
                }
            }
        }
        // 首进：若空，插一条欢迎
        lifecycleScope.launch {
            if (dao.getAll().isEmpty()) {
                dao.insert(
                    ChatMessageEntity(
                        role = ChatRole.RECEIVED,
                        type = ChatType.TEXT,
                        text = "您好，我是彩票历史查询客服，请问需要什么帮助？\n如需购买套餐，请发送付款截图。",
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    // ========== 发送文本 ==========
    private fun sendText() {
        val text = etMessage.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        etMessage.text?.clear()
        lifecycleScope.launch { saveAndReply(ChatType.TEXT, text = text) }
    }

    // ========== 发送图片（付款截图） ==========
    private fun trySendImage() {
        // 权限：13+ READ_MEDIA_IMAGES，12- READ_EXTERNAL_STORAGE
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), REQ_PERM_IMAGE)
            return
        }
        pickImage()
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        startActivityForResult(Intent.createChooser(intent, "选择付款截图"), REQ_PICK_IMAGE)
    }

    // ========== 语音切换 ==========
    private var voiceMode = false
    private fun toggleVoiceMode() {
        if (!voiceMode && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERM_VOICE)
            return
        }
        voiceMode = !voiceMode
        ivToggleVoice.text = if (voiceMode) "文字" else "语音"
        etMessage.visibility = if (voiceMode) android.view.View.GONE else android.view.View.VISIBLE
        btnHoldToTalk.visibility = if (voiceMode) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun startVoiceSafe() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceHelper.startRecord()
        }
    }

    // ========== 持久化 + 模拟客服回复（后端接入前保留） ==========
    private suspend fun saveAndReply(
        type: ChatType,
        text: String? = null,
        mediaPath: String? = null,
        duration: Int = 0
    ) = withContext(Dispatchers.IO) {
        val sentId = dao.insert(
            ChatMessageEntity(
                role = ChatRole.SENT,
                type = type,
                text = text,
                mediaPath = mediaPath,
                duration = duration,
                createdAt = System.currentTimeMillis()
            )
        )
        // TODO(后端接入): 此处改为通过 WebSocket 推送消息，由客服真实回复。
        //  目前用模拟回复保证交互闭环。
        val replyText = when (type) {
            ChatType.TEXT -> "收到您的消息：\"${text}\"。（当前为本地模拟回复，接入后端后将替换为真实客服回复）"
            ChatType.IMAGE -> "已收到您的付款截图，我们将尽快核对并为您开通。感谢支持！"
            ChatType.VOICE -> "已收到您的语音消息（${duration}秒），稍后为您回复。"
        }
        dao.insert(
            ChatMessageEntity(
                role = ChatRole.RECEIVED,
                type = ChatType.TEXT,
                text = replyText,
                createdAt = System.currentTimeMillis() + 800L
            )
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                // 转为本地持久化路径：拷贝到 app files 目录，避免 uri 权限过期
                lifecycleScope.launch {
                    val path = withContext(Dispatchers.IO) {
                        runCatching {
                            val dest = File(getExternalFilesDir(null), "img_${System.currentTimeMillis()}.jpg")
                            contentResolver.openInputStream(uri).use { inp ->
                                dest.outputStream().use { out -> inp?.copyTo(out) }
                            }
                            dest.absolutePath
                        }.getOrNull() ?: uri.toString()
                    }
                    saveAndReply(ChatType.IMAGE, mediaPath = path)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (!ok) return
        when (requestCode) {
            REQ_PERM_VOICE -> toggleVoiceMode()
            REQ_PERM_IMAGE -> pickImage()
        }
    }
}
