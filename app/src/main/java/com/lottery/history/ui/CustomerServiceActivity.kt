package com.lottery.history.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import com.lottery.history.R
import com.lottery.history.adapter.ChatAdapter
import com.lottery.history.data.SessionStore
import com.lottery.history.db.ChatMessageDao
import com.lottery.history.db.ChatMessageEntity
import com.lottery.history.db.ChatRole
import com.lottery.history.db.ChatType
import com.lottery.history.db.LotteryDatabase
import com.lottery.history.network.ApiClient
import com.lottery.history.network.ChatClient
import com.lottery.history.util.VoiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 联系客服（聊天）Activity。
 *
 * 接 server WebSocket：
 * - 文字 / 图片 / 语音 发送给管理员
 * - 接收管理员实时回复
 * - 图片和语音先上传到 server，再通过 ws 推送
 *
 * 离线时：本地 Room 仍可查看历史消息，但发送会失败提示。
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
    private val sessionStore by lazy { SessionStore(this) }

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
        findViewById<TextView>(R.id.tvWechat).setOnClickListener { showWechatDialog() }

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
                    lifecycleScope.launch { sendVoice(File(path), dur) }
                }
                MotionEvent.ACTION_CANCEL -> voiceHelper.cancelRecord()
            }
            true
        }

        // 订阅本地消息（Room Flow）
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

        // 连接 WebSocket 并订阅入站消息
        ChatClient.connect()
        lifecycleScope.launch {
            ChatClient.incoming.collectLatest { event ->
                when (event) {
                    is ChatClient.IncomingEvent.Chat -> {
                        if (event.role == "RECEIVED") {
                            // 来自管理员的消息：写入本地 Room
                            // mediaPath 是 server 路径（/uploads/xxx.jpg），加载时拼接 BASE_URL
                            val fullUrl = ApiClient.fileUrl(event.payload.mediaPath)
                            dao.insert(
                                ChatMessageEntity(
                                    role = ChatRole.RECEIVED,
                                    type = ChatType.valueOf(event.payload.type),
                                    text = event.payload.text,
                                    mediaPath = fullUrl,
                                    duration = event.payload.duration,
                                    createdAt = event.createdAt
                                )
                            )
                            // 自动已读
                            ChatClient.sendRead()
                        }
                    }
                    is ChatClient.IncomingEvent.Error -> {
                        Toast.makeText(this@CustomerServiceActivity, event.message, Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 进入聊天页即标记已读
        ChatClient.connect()
        ChatClient.sendRead()
    }

    // ========== 微信客服二维码 ==========
    private fun showWechatDialog() {
        lifecycleScope.launch {
            val config = try {
                ApiClient.getClientConfig()
            } catch (e: Exception) {
                Toast.makeText(this@CustomerServiceActivity, "获取配置失败：${e.message}", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val qr = config.wechat_qr_url
            if (qr.isNullOrBlank()) {
                Toast.makeText(this@CustomerServiceActivity, "暂未配置微信客服二维码", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val acc = config.wechat_account
            val dp = resources.displayMetrics.density

            val img = ImageView(this@CustomerServiceActivity)
            img.setImageURI(Uri.parse(ApiClient.fileUrl(qr)))
            img.adjustViewBounds = true
            img.layoutParams = LinearLayout.LayoutParams((200 * dp).toInt(), (200 * dp).toInt())

            val body = LinearLayout(this@CustomerServiceActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
                addView(img)
            }
            if (!acc.isNullOrBlank()) {
                val tv = TextView(this@CustomerServiceActivity).apply {
                    text = "微信号：$acc"
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    setTextColor(resources.getColor(android.R.color.darker_gray, null))
                    textSize = 14f
                    setPadding(0, (10 * dp).toInt(), 0, 0)
                }
                body.addView(tv)
            }
            AlertDialog.Builder(this@CustomerServiceActivity)
                .setTitle("微信客服")
                .setView(body)
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    // ========== 发送文本 ==========
    private fun sendText() {
        val text = etMessage.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        etMessage.text?.clear()

        lifecycleScope.launch {
            // 1) 本地存 SENT（UI 立即响应）
            val now = System.currentTimeMillis()
            dao.insert(
                ChatMessageEntity(
                    role = ChatRole.SENT,
                    type = ChatType.TEXT,
                    text = text,
                    createdAt = now
                )
            )
            // 2) 通过 WebSocket 发给管理员
            ChatClient.sendChatToAdmin(
                ChatClient.ChatPayload(type = "TEXT", text = text)
            )
        }
    }

    // ========== 发送图片（付款截图） ==========
    private fun trySendImage() {
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

    // ========== 上传图片 + 发送 ==========
    private suspend fun sendImage(localFile: File) = withContext(Dispatchers.IO) {
        // 1) 上传到 server
        val upload = try {
            ApiClient.uploadFile(localFile, "image/jpeg")
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@CustomerServiceActivity, "图片上传失败：${e.message}", Toast.LENGTH_LONG).show()
            }
            return@withContext
        }
        // 2) 本地存 SENT，mediaPath 用 server URL（ChatAdapter 可直接加载 http URL）
        val now = System.currentTimeMillis()
        dao.insert(
            ChatMessageEntity(
                role = ChatRole.SENT,
                type = ChatType.IMAGE,
                mediaPath = ApiClient.fileUrl(upload.url),
                createdAt = now
            )
        )
        // 3) 通过 WebSocket 推送给管理员（payload.mediaPath 用 server 相对路径 /uploads/xxx.jpg）
        ChatClient.sendChatToAdmin(
            ChatClient.ChatPayload(type = "IMAGE", mediaPath = upload.url)
        )
    }

    // ========== 上传语音 + 发送 ==========
    private suspend fun sendVoice(localFile: File, duration: Int) = withContext(Dispatchers.IO) {
        val upload = try {
            ApiClient.uploadFile(localFile, "audio/mp4")
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@CustomerServiceActivity, "语音上传失败：${e.message}", Toast.LENGTH_LONG).show()
            }
            return@withContext
        }
        val now = System.currentTimeMillis()
        dao.insert(
            ChatMessageEntity(
                role = ChatRole.SENT,
                type = ChatType.VOICE,
                mediaPath = ApiClient.fileUrl(upload.url),
                duration = duration,
                createdAt = now
            )
        )
        ChatClient.sendChatToAdmin(
            ChatClient.ChatPayload(type = "VOICE", mediaPath = upload.url, duration = duration)
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                lifecycleScope.launch {
                    // 拷贝到本地文件，再上传
                    val localFile = withContext(Dispatchers.IO) {
                        runCatching {
                            val dest = File(getExternalFilesDir(null), "img_${System.currentTimeMillis()}.jpg")
                            contentResolver.openInputStream(uri).use { inp ->
                                dest.outputStream().use { out -> inp?.copyTo(out) }
                            }
                            dest
                        }.getOrNull()
                    }
                    if (localFile != null) {
                        sendImage(localFile)
                    } else {
                        Toast.makeText(this@CustomerServiceActivity, "图片读取失败", Toast.LENGTH_SHORT).show()
                    }
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

    override fun onDestroy() {
        super.onDestroy()
        // 不在 onDestroy 断开 ws，保持后台可收消息。Application 退出会自动断开。
    }
}
