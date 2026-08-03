package com.lottery.history.adapter

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lottery.history.R
import com.lottery.history.db.ChatMessageEntity
import com.lottery.history.db.ChatRole
import com.lottery.history.db.ChatType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 客服聊天消息适配器。
 *
 * 双头像方案（左+右）+ visibility 切换，避免 bind 时 removeView/addView 重排导致闪退。
 *
 * 优化点：
 * - 图片解码使用 inSampleSize 降采样，避免大图 OOM
 * - 语音图标按发送/接收方向水平翻转，提示播放方向
 * - 智能时间戳：与上一条间隔>5分钟才显示
 * - 全局 try-catch 防止任何单行绑定异常导致崩溃
 */
class ChatAdapter(
    private val onVoiceClick: (ChatMessageEntity) -> Unit
) : ListAdapter<ChatMessageEntity, ChatAdapter.MsgVH>(DIFF) {

    /** 当前正在播放的语音消息 id，用于动画态展示 */
    var playingVoiceId: Long? = null
        set(value) {
            val old = field
            field = value
            if (old != null && old != value) notifyItemChangedById(old)
            if (value != null) notifyItemChangedById(value)
        }

    private fun notifyItemChangedById(id: Long) {
        for (i in 0 until itemCount) {
            if (getItem(i).id == id) {
                notifyItemChanged(i, PAYLOAD_PLAYING)
                return
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        val m = getItem(position)
        val side = if (m.role == ChatRole.SENT) 1 else 0
        val type = when (m.type) {
            ChatType.TEXT -> 1
            ChatType.IMAGE -> 2
            ChatType.VOICE -> 3
        }
        return side * 10 + type
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MsgVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MsgVH(view)
    }

    override fun onBindViewHolder(holder: MsgVH, position: Int) {
        // 绑定过程中任何异常都捕获，避免单行数据异常导致整页闪退
        try {
            holder.bind(getItem(position), position)
        } catch (_: Throwable) { /* 静默跳过，保证列表可用 */ }
    }

    override fun onBindViewHolder(holder: MsgVH, position: Int, payloads: MutableList<Any>) {
        try {
            if (payloads.contains(PAYLOAD_PLAYING)) {
                holder.refreshPlayingState(getItem(position))
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        } catch (_: Throwable) { /* 静默跳过 */ }
    }

    inner class MsgVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val llRow: LinearLayout = itemView.findViewById(R.id.llRow)
        private val ivAvatarLeft: TextView = itemView.findViewById(R.id.ivAvatarLeft)
        private val ivAvatarRight: TextView = itemView.findViewById(R.id.ivAvatarRight)
        private val llBubble: LinearLayout = itemView.findViewById(R.id.llBubble)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvText: TextView = itemView.findViewById(R.id.tvText)
        private val ivImage: ImageView = itemView.findViewById(R.id.ivImage)
        private val tvImageLabel: TextView = itemView.findViewById(R.id.tvImageLabel)
        private val llVoice: LinearLayout = itemView.findViewById(R.id.llVoice)
        private val ivVoiceIcon: TextView = itemView.findViewById(R.id.ivVoiceIcon)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)

        private var currentMsg: ChatMessageEntity? = null

        init {
            try {
                llVoice.setOnClickListener {
                    currentMsg?.let { onVoiceClick(it) }
                }
            } catch (_: Throwable) {}
        }

        fun bind(msg: ChatMessageEntity, position: Int) {
            currentMsg = msg

            // 1. 智能时间戳：与上一条间隔>5分钟或首条才显示
            val showTime = position == 0 ||
                (msg.createdAt - getItem(position - 1).createdAt) > TIME_GAP_MS
            tvTime.visibility = if (showTime) View.VISIBLE else View.GONE
            if (showTime) tvTime.text = formatTime(msg.createdAt)

            // 2. 对齐与头像双显切换（不做重排）
            val isSent = msg.role == ChatRole.SENT
            llRow.gravity = Gravity.CENTER_VERTICAL or (if (isSent) Gravity.END else Gravity.START)
            if (isSent) {
                ivAvatarRight.visibility = View.VISIBLE
                ivAvatarLeft.visibility = View.GONE
                llBubble.setBackgroundResource(R.drawable.bg_chat_bubble_sent)
                tvText.setTextColor(Color.WHITE)
            } else {
                ivAvatarLeft.visibility = View.VISIBLE
                ivAvatarRight.visibility = View.GONE
                llBubble.setBackgroundResource(R.drawable.bg_chat_bubble_received)
                tvText.setTextColor(0xFF212121.toInt())
            }

            // 3. 内容显示
            tvText.visibility = View.GONE
            ivImage.visibility = View.GONE
            tvImageLabel.visibility = View.GONE
            llVoice.visibility = View.GONE

            when (msg.type) {
                ChatType.TEXT -> {
                    tvText.visibility = View.VISIBLE
                    tvText.text = msg.text.orEmpty()
                }
                ChatType.IMAGE -> {
                    ivImage.visibility = View.VISIBLE
                    loadImage(ivImage, msg.mediaPath)
                    if (!msg.text.isNullOrBlank()) {
                        tvImageLabel.visibility = View.VISIBLE
                        tvImageLabel.text = msg.text
                    }
                }
                ChatType.VOICE -> {
                    llVoice.visibility = View.VISIBLE
                    tvDuration.text = "${msg.duration.coerceAtLeast(1)}"
                    refreshPlayingState(msg)
                }
            }
        }

        fun refreshPlayingState(msg: ChatMessageEntity) {
            try {
                if (msg.type != ChatType.VOICE) return
                val playing = playingVoiceId == msg.id
                ivVoiceIcon.text = if (playing) "播放中" else "语音"
                ivVoiceIcon.alpha = if (playing) 1.0f else 0.85f
            } catch (_: Throwable) {}
        }

        /** 图片加载：任何失败都不崩溃 */
        private fun loadImage(iv: ImageView, path: String?) {
            try {
                if (path.isNullOrBlank()) {
                    showImageError(iv, "图片无效")
                    return
                }
                if (path.startsWith("content:") || path.startsWith("http")) {
                    iv.setImageURI(Uri.parse(path))
                } else {
                    val file = File(path)
                    if (file.exists()) {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, opts)
                        val targetW = 360
                        opts.inSampleSize = calcSampleSize(opts.outWidth, targetW)
                        opts.inJustDecodeBounds = false
                        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                        if (bmp != null) {
                            iv.setImageBitmap(bmp)
                        } else {
                            showImageError(iv, "图片损坏")
                        }
                    } else {
                        showImageError(iv, "图片不存在")
                    }
                }
            } catch (_: Throwable) {
                showImageError(iv, "图片加载失败")
            }
        }

        private fun showImageError(iv: ImageView, msg: String) {
            iv.setImageDrawable(null)
            iv.background = null
            tvImageLabel.visibility = View.VISIBLE
            tvImageLabel.text = msg
        }

        private fun calcSampleSize(outWidth: Int, target: Int): Int {
            var sample = 1
            while (outWidth / sample > target) sample *= 2
            return sample.coerceAtLeast(1)
        }

        private fun formatTime(ts: Long): String {
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(ts))
        }
    }

    companion object {
        private const val PAYLOAD_PLAYING = "payload_playing"
        /** 时间戳显示间隔：5 分钟 */
        private const val TIME_GAP_MS = 5 * 60 * 1000L

        private val DIFF = object : DiffUtil.ItemCallback<ChatMessageEntity>() {
            override fun areItemsTheSame(oldItem: ChatMessageEntity, newItem: ChatMessageEntity) =
                oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ChatMessageEntity, newItem: ChatMessageEntity) =
                oldItem == newItem
        }
    }
}

