package com.lottery.history.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LotteryDrawEntity::class,
        QueryRecordEntity::class,
        UserEntity::class,
        QuotaEntity::class,
        PendingSyncEntity::class,
        ChatMessageEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class LotteryDatabase : RoomDatabase() {
    abstract fun lotteryDao(): LotteryDao
    abstract fun queryRecordDao(): QueryRecordDao
    abstract fun userDao(): UserDao
    abstract fun quotaDao(): QuotaDao
    abstract fun pendingSyncDao(): PendingSyncDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var instance: LotteryDatabase? = null

        fun get(context: Context): LotteryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LotteryDatabase::class.java,
                    "lottery.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}
