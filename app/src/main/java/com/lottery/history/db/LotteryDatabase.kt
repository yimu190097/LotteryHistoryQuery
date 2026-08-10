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
        QuotaEntity::class
    ],
    version = 14,
    exportSchema = true
)
abstract class LotteryDatabase : RoomDatabase() {
    abstract fun lotteryDao(): LotteryDao
    abstract fun queryRecordDao(): QueryRecordDao
    abstract fun userDao(): UserDao
    abstract fun quotaDao(): QuotaDao

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
         * Migration(13, 14)：删除未实现的 chat_messages 和 pending_sync 两个空表。
         * 聊天模块（无后端，纯本地模拟）和离线同步模块（无服务器，WorkManager只标记SYNCED）
         * 均为占位功能，长期不会接入，直接清理节省空间与DAO维护成本。
         * 同时清理对应的权限请求也从 Manifest 移除。
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                runCatching { database.execSQL("DROP TABLE IF EXISTS `chat_messages`") }
                runCatching { database.execSQL("DROP TABLE IF EXISTS `pending_sync`") }
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
                    MIGRATION_13_14
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}
