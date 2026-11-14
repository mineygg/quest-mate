package com.mineygg.questmate.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mineygg.questmate.QuestMateApp
import com.mineygg.questmate.core.Battery
import com.mineygg.questmate.core.Connectivity
import com.mineygg.questmate.core.GatewayPool
import com.mineygg.questmate.core.Notifier
import com.mineygg.questmate.core.QuestRunner
import com.mineygg.questmate.core.RunBus
import com.mineygg.questmate.data.Settings
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val TICK = "quest-tick"
    private const val RUN = "quest-run"
    private const val KEY_TRIGGER = "trigger"

    /** Sets up (or cancels) the automatic run. [replace] restarts the timer, otherwise an existing one is kept. */
    fun schedule(ctx: Context, s: Settings, replace: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!s.autoRun) {
            wm.cancelUniqueWork(TICK)
            return
        }
        val req = PeriodicWorkRequestBuilder<TickWorker>(s.intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(
            TICK,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }

    /**
     * Starts a run now (expedited, so Android lets it run in the foreground). A run already going is left alone.
     * A worker that is only keeping the connection open after its run is replaced, and the new run reuses it.
     */
    fun runNow(ctx: Context, trigger: String) {
        if (RunBus.running.value) return
        if (!Battery.isUnrestricted(ctx)) return // the app shows a screen asking for this; nothing runs until then
        val req = OneTimeWorkRequestBuilder<QuestWorker>()
            .setInputData(Data.Builder().putString(KEY_TRIGGER, trigger).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(RUN, ExistingWorkPolicy.REPLACE, req)
    }

    fun stop(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(RUN)
    }

    /** Fires on the chosen interval (3 days by default) and hands the real work to an expedited run. */
    class TickWorker(ctx: Context, params: WorkerParameters) : androidx.work.Worker(ctx, params) {
        override fun doWork(): Result {
            val app = applicationContext as QuestMateApp
            if (app.store.token != null && app.store.settings.value.autoRun) runNow(applicationContext, "scheduled")
            return Result.success()
        }
    }

    class QuestWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun getForegroundInfo(): ForegroundInfo = info()

        private fun info(text: String? = null): ForegroundInfo {
            val n = if (text == null) Notifier.working(applicationContext) else Notifier.working(applicationContext, text)
            return if (Build.VERSION.SDK_INT >= 29) {
                ForegroundInfo(Notifier.WORK_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(Notifier.WORK_ID, n)
            }
        }

        override suspend fun doWork(): Result {
            val app = applicationContext as QuestMateApp
            if (app.store.token == null || RunBus.running.value) return Result.success()
            // Keeps the run alive while the app is closed. Android may refuse from the background: carry on anyway.
            runCatching { setForeground(info()) }
            if (!Connectivity.awaitOnline(applicationContext, 90_000)) return Result.retry()
            val trigger = inputData.getString(KEY_TRIGGER) ?: "manual"
            val done = QuestRunner(applicationContext, app.store).run(trigger)
            if (done > 0 && app.store.settings.value.notify && trigger == "scheduled") Notifier.runFinished(applicationContext, done)
            // The connection stays open for a couple of minutes so the next run can reuse it. Staying in the
            // foreground (with the notification) until it closes is what keeps Android from freezing the app.
            if (GatewayPool.isOpen) {
                runCatching { setForeground(info("Connected to Discord, closing if idle")) }
                GatewayPool.awaitClosed()
            }
            return Result.success()
        }
    }
}
