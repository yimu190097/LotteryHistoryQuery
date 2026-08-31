package com.lottery.history.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 消息方向：用户发出 / 客服收到 */
enum class ChatRole { SENT, RECEIVED }

/** 消息类型：文本 / 付款图片 / 语音 */
enum class ChatType { TEXT, IMAGE, VOICE }

/**
 * 客服聊天消息表。
 * - text：文本内容
 * - mediaPath：图片或语音文件的本地路径（IMAGE/VOICE 用）
 * - duration：语音时长（秒），仅 VOICE 用
 */
@Entity(
    tableName = "chat_messages",
    indices = [androidx.room.Index(value = ["createdAt"])]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: ChatRole,
    val type: ChatType,
    val text: String? = null,
    val mediaPath: String? = null,
    val duration: Int = 0,
    val createdAt: Long
)
