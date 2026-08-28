package com.lottery.history

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lottery.history.data.AuthRepository
import com.lottery.history.data.QuotaRepository
import com.lottery.history.db.LotteryDatabase
import com.lottery.history.db.UserEntity
import com.lottery.history.work.DailyUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.mindrot.jbcrypt.BCrypt
import java.util.Calendar
import java.util.concurrent.TimeUnit

class LotteryApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppContext.init(this)
        setupGlobalExceptionHandler()
        ensureAdminAccount()
        scheduleDailyUpdate()
    }

    /**
     * 全局未捕获异常处理器：避免闪退白屏，显示友好提示后退出。
     * 仅兜底非预期崩溃，正常 try-catch 不会被拦截。
     */
    private fun setupGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("LotteryApp", "未捕获异常", throwable)
            try {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        this,
                        "应用遇到异常，请重启应用\n${throwable.message ?: "未知错误"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                // 短暂延迟让 Toast 显示出来
                Thread.sleep(2000)
            } catch (_: Exception) { }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 确保管理权限账号存在：手机号 13440113882，密码 hy190097。
     * 若已存在则跳过，不覆盖密码；首次启动时自动创建并初始化配额。
     */
    private fun ensureAdminAccount() {
        appScope.launch {
            try {
                val dao = LotteryDatabase.get(this@LotteryApp).userDao()
                if (dao.countByPhone(ADMIN_PHONE) == 0) {
                    val now = System.currentTimeMillis()
                    dao.upsert(
                        UserEntity(
                            phone = ADMIN_PHONE,
                            passwordHash = BCrypt.hashpw(ADMIN_PASSWORD, BCrypt.gensalt(12)),
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                    // 管理员按月租用户初始化配额（有效期一年）
                    QuotaRepository(this@LotteryApp).initForAdmin(ADMIN_PHONE)
                }
            } catch (_: Exception) {
                // 静默失败，不影响主流程
            }
        }
    }

    private fun scheduleDailyUpdate() {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val initialDelay = calendar.timeInMillis - System.currentTimeMillis()
        val request = PeriodicWorkRequestBuilder<DailyUpdateWorker>(
            1, TimeUnit.DAYS
        ).setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .addTag("daily_lottery_update")
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_lottery_update",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        /** 管理权限账号：手机号 */
        const val ADMIN_PHONE = "13440113882"
        /** 管理权限账号：密码 */
        const val ADMIN_PASSWORD = "hy190097"
    }
}

object AppContext {
    private var app: Application? = null
    fun init(app: Application) { this.app = app }
    val get: Application get() = app ?: error("AppContext not initialized")
}
