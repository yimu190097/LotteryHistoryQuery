package com.lottery.history.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LotteryDrawEntity::class,
        QueryRecordEntity::class,
        UserEntity::class,
        QuotaEntity::class,
        ChatMessageEntity::class,
        PendingSyncEntity::class
    ],
    version = 15,
    exportSchema = true
)
abstract class LotteryDatabase : RoomDatabase() {
    abstract fun lotteryDao(): LotteryDao
    abstract fun queryRecordDao(): QueryRecordDao
    abstract fun userDao(): UserDao
    abstract fun quotaDao(): QuotaDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun pendingSyncDao(): PendingSyncDao

    companion object {
        @Volatile
        private var instance: LotteryDatabase? = null

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 仅保留 lottery_draws 表真实需要的 4 个解析来源审计字段
                // rule_version_catalog / match_rule_def / lottery_prize_tier 三个死表已废弃，
                // 不再 CREATE，避免升级后保留空表占用空间。
                database.execSQL("ALTER TABLE `lottery_draws` ADD COLUMN `parseSource` TEXT")
                database.execSQL("ALTER TABLE `lottery_draws` ADD COLUMN `parseAt` INTEGER")
                database.execSQL("ALTER TABLE `lottery_draws` ADD COLUMN `parserVersion` INTEGER")
                database.execSQL("ALTER TABLE `lottery_draws` ADD COLUMN `conditionalFlagsJson` TEXT")
            }
        }

        /**
         * Migration(11, 12)：数据类型修复（中奖注数 count Int→Long）
         * SQLite INTEGER 动态类型自动兼容，无需表结构变更。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 无操作：SQLite INTEGER 动态类型自动兼容 Int→Long
            }
        }

        /**
         * Migration(12, 13)：清理死表（rule_version_catalog / match_rule_def / lottery_prize_tier）。
         * 旧版本升级到 v13 时，若之前的 MIGRATION_10_11 创建了这三个死表，直接 DROP。
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                runCatching { database.execSQL("DROP TABLE IF EXISTS `rule_version_catalog`") }
                runCatching { database.execSQL("DROP TABLE IF EXISTS `match_rule_def`") }
                runCatching { database.execSQL("DROP TABLE IF EXISTS `lottery_prize_tier`") }
            }
        }

        /**
         * Migration(13, 14) 曾试图删除 chat_messages / pending_sync，
         * 但这两张表对应功能（客服聊天 + 离线同步队列）为后期接后端预留骨架，属上线必填模块，不应删除。
         * 因此 v14 作为过渡版本，v15 迁移直接 CREATE 还原（IF NOT EXISTS 幂等）。
         * 若老用户停留在 v14 且表被 drop → v15 重建；若新用户从未有这两张表 → 首次创建。
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 保持空迁移：实际 CREATE 放在 14→15，保证跨版本连续
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // --- 恢复 chat_messages 表 ---
                runCatching {
                    database.execSQL(
                        """CREATE TABLE IF NOT EXISTS `chat_messages` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `role` TEXT NOT NULL,
                            `type` TEXT NOT NULL,
                            `text` TEXT,
                            `mediaPath` TEXT,
                            `duration` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL
                        )"""
                    )
                }
                // --- 恢复 pending_sync 表 ---
                runCatching {
                    database.execSQL(
                        """CREATE TABLE IF NOT EXISTS `pending_sync` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `userPhone` TEXT NOT NULL,
                            `actionType` TEXT NOT NULL,
                            `payload` TEXT NOT NULL,
                            `clientOpId` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `retryCount` INTEGER NOT NULL,
                            `lastError` TEXT,
                            `createdAt` INTEGER NOT NULL,
                            `syncedAt` INTEGER
                        )"""
                    )
                }
            }
        }

        fun get(context: Context): LotteryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LotteryDatabase::class.java,
                    "lottery.db"
                ).addMigrations(
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15
                )
                // P0-4: 移除 fallbackToDestructiveMigration() —— 迁移失败时
                // 应抛 IllegalStateException 让全局异常处理器捕获并提示，
                // 而非静默删除整个数据库导致用户本地数据丢失。
                // 若确需重置（极少数损坏场景），由用户主动「清除应用数据」处理。
                .build().also { instance = it }
            }
        }
    }
}
