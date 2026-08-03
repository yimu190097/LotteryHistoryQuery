package com.lottery.history.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户表：手机号为主键，密码存 bcrypt 哈希（含盐，禁止明文）。
 * 当前阶段为本地账号，后期接入服务器后由服务器校验，本地仅缓存。
 */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val phone: String,
    /** bcrypt 哈希串（含盐，60 字符） */
    val passwordHash: String,
    val createdAt: Long,
    val updatedAt: Long
)
