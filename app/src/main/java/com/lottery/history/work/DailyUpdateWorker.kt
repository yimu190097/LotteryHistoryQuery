package com.lottery.history.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lottery.history.data.LotteryDataManager

class DailyUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val r = LotteryDataManager.refresh(applicationContext)
        return if (r.success) Result.success() else Result.retry()
    }
}
