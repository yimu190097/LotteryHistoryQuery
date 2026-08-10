package com.lottery.history.adapter

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lottery.history.R
import com.lottery.history.db.ChatMessageEntity
import com.lottery.history.db.ChatRole
import com.lottery.history.db.ChatType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天消息 Adapter：双头像（左客服/右我）切换，三类型（文字/图片/语音）切换。
 *
 * 防闪退设计：布局中左/右头像均存在，仅切换 visibility，避免 bind 时动态 removeView/addView。
 */
class ChatAdapter : ListAdapter<ChatMessageEntity, ChatAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessageEntity>() {
            override fun areItemsTheSame(a: ChatMessageEntity, b: ChatMessageEntity) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessageEntity, b: ChatMessageEntity) = a == b
        }
        private const val TIME_GAP_MS = 5L * 60L * 1000L
        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.CHINA)
    }

    inner class VH(root: View) : RecyclerView.ViewHolder(root) {
        val tvTime: TextView = root.findViewById(R.id.tvTime)
        val llRow: LinearLayout = root.findViewById(R.id.llRow)
        val ivAvatarLeft: TextView = root.findViewById(R.id.ivAvatarLeft)
        val ivAvatarRight: TextView = root.findViewById(R.id.ivAvatarRight)
        val llBubble: LinearLayout = root.findViewById(R.id.llBubble)
        val tvText: TextView = root.findViewById(R.id.tvText)
        val ivImage: ImageView = root.findViewById(R.id.ivImage)
        val tvImageLabel: TextView = root.findViewById(R.id.tvImageLabel)
        val llVoice: LinearLayout = root.findViewById(R.id.llVoice)
        val ivVoiceIcon: TextView = root.findViewById(R.id.ivVoiceIcon)
        val tvDuration: TextView = root.findViewById(R.id.tvDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.llBubble.context
        val prev = if (position > 0) getItem(position - 1) else null

        // ===== 时间戳：仅与上一条 >5 分钟才显示 =====
        if (prev == null || item.createdAt - prev.createdAt > TIME_GAP_MS) {
            holder.tvTime.visibility = View.VISIBLE
            holder.tvTime.text = TIME_FMT.format(Date(item.createdAt))
        } else {
            holder.tvTime.visibility = View.GONE
        }

        // ===== 头像 & 气泡位置：我=右红，客服=左灰 =====
        val isMe = item.role == ChatRole.SENT
        holder.ivAvatarRight.visibility = if (isMe) View.VISIBLE else View.GONE
        holder.ivAvatarLeft.visibility = if (isMe) View.GONE else View.VISIBLE
        // 重力：整行左/右对齐
        (holder.llRow.layoutParams as RecyclerView.LayoutParams).apply {
            marginStart = if (isMe) 48 else 0
            marginEnd = if (isMe) 0 else 48
        }
        // 气泡背景
        val bgRes = if (isMe) R.drawable.bg_bubble_me else R.drawable.bg_bubble_other
        holder.llBubble.background = ContextCompat.getDrawable(ctx, bgRes)
        // 气泡文字颜色
        val textColor = if (isMe) android.R.color.white else R.color.text_primary
        val textColorRes = ContextCompat.getColor(ctx, textColor)
        holder.tvText.setTextColor(textColorRes)
        holder.ivVoiceIcon.setTextColor(textColorRes)
        holder.tvDuration.setTextColor(textColorRes)

        // ===== 消息类型显隐：避免 findViewById NPE =====
        holder.tvText.visibility = View.GONE
        holder.ivImage.visibility = View.GONE
        holder.tvImageLabel.visibility = View.GONE
        holder.llVoice.visibility = View.GONE

        when (item.type) {
            ChatType.TEXT -> {
                holder.tvText.visibility = View.VISIBLE
                holder.tvText.text = item.text ?: ""
            }
            ChatType.IMAGE -> {
                holder.ivImage.visibility = View.VISIBLE
                holder.tvImageLabel.visibility = View.VISIBLE
                holder.tvImageLabel.text = "付款截图"
                // 有路径就加载，没路径就显示占位色（模拟已发送）
                if (item.mediaPath != null) {
                    runCatching {
                        holder.ivImage.setImageURI(android.net.Uri.parse(item.mediaPath))
                    }
                } else {
                    holder.ivImage.setImageDrawable(null)
                    val ph = GradientDrawable().apply {
                        setColor(if (isMe) 0xFFE63946.toInt() else 0xFFE0E0E0.toInt())
                        shape = GradientDrawable.RECTANGLE
                    }
                    holder.ivImage.setImageDrawable(ph)
                }
            }
            ChatType.VOICE -> {
                holder.llVoice.visibility = View.VISIBLE
                holder.tvDuration.text = item.duration.toString()
            }
        }
    }
}
