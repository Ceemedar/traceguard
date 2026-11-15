package com.traceguard.antitheft.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        ReportSync.sync(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
