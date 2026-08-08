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
        PendingSyncEntity::class,
        ChatMessageEntity::class,
        RuleVersionCatalogEntity::class,
        MatchRuleDefEntity::class,
        PrizeTierEntity::class
    ],
    version = 12,
    exportSchema = false
)
abstract class LotteryDatabase : RoomDatabase() {
    abstract fun lotteryDao(): LotteryDao
    abstract fun queryRecordDao(): QueryRecordDao
    abstract fun userDao(): UserDao
    abstract fun quotaDao(): QuotaDao
    abstract fun pendingSyncDao(): PendingSyncDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun ruleVersionCatalogDao(): RuleVersionCatalogDao
    abstract fun matchRuleDefDao(): MatchRuleDefDao
    abstract fun prizeTierDao(): PrizeTierDao

    companion object {
        @Volatile
        private var instance: LotteryDatabase? = null

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `rule_version_catalog` (
                        `ruleVersionKey` TEXT NOT NULL,
                        `code` TEXT NOT NULL,
                        `effectiveFromDate` TEXT NOT NULL,
                        `policyLabel` TEXT NOT NULL,
                        `changeNote` TEXT NOT NULL,
                        `realTiersToUse` INTEGER NOT NULL,
                        `prizeTierPairCount` INTEGER NOT NULL,
                        `extraFieldCount` INTEGER NOT NULL,
                        `appendTierPairCount` INTEGER NOT NULL,
                        `snapshotAt` INTEGER NOT NULL,
                        PRIMARY KEY(`ruleVersionKey`)
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `match_rule_def` (
                        `ruleVersionKey` TEXT NOT NULL,
                        `dedupIndex` INTEGER NOT NULL,
                        `ruleIndex` INTEGER NOT NULL,
                        `matchPrimary` INTEGER NOT NULL,
                        `matchSecondary` INTEGER NOT NULL,
                        `description` TEXT NOT NULL,
                        `prizeName` TEXT NOT NULL,
                        `fixedAmountYuan` INTEGER,
                        `conditionalKey` TEXT,
                        PRIMARY KEY(`ruleVersionKey`, `dedupIndex`, `ruleIndex`)
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lottery_prize_tier` (
                        `issue` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `tierGroup` TEXT NOT NULL,
                        `tierIndex` INTEGER NOT NULL,
                        `count` INTEGER NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`issue`, `type`, `tierGroup`, `tierIndex`)
                    )
                    """.trimIndent()
                )

                database.execSQL("ALTER TABLE `lottery_draws` ADD COLUMN `parseSource` TEXT")
                database.execSQL("ALTER TABLE `lottery_draws` ADD COLUMN `parseAt` INTEGER")
                database.execSQL("ALTER TABLE `lottery_draws` ADD COLUMN `parserVersion` INTEGER")
                database.execSQL("ALTER TABLE `lottery_draws` ADD COLUMN `conditionalFlagsJson` TEXT")
            }
        }

        /**
         * Migration(11, 12)：数据类型修复（中奖注数 count Int→Long）
         *
         * 注：SQLite 的 INTEGER 列是"动态类型"（列亲和力），同一列既可存 1 字节小整数，
         *   也可存 8 字节大整数。所以 Kotlin 侧把 PrizeTierEntity.count / LotteryDrawEntity.firstPrizeCount
         *   从 Int 改为 Long，**不需要重写表结构**（没有 ALTER COLUMN 这种语句）。
         *   Room 生成的 Cursor.getLong / ContentValues.put(key, longValue) 在所有 Android
         *   SQLite 版本上都能正确读/写 8 字节整数，旧版本 Int(4 字节) 的数据也会被
         *   SQLite 自动扩展，不会出错。Migration 留空仅 bump 版本号。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 无操作：SQLite INTEGER 动态类型自动兼容 Int→Long
            }
        }

        fun get(context: Context): LotteryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LotteryDatabase::class.java,
                    "lottery.db"
                ).addMigrations(MIGRATION_10_11, MIGRATION_11_12).build().also { instance = it }
            }
        }
    }
}
