package com.mineygg.questmate.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mineygg.questmate.MainActivity
import com.mineygg.questmate.R

object Notifier {
    private const val CH_RESULT = "result"
    private const val CH_ALERT = "alert"
    private const val CH_WORK = "work"

    const val WORK_ID = 7

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "Finished quests", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Sign-in alerts", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_WORK, "Quest run", NotificationManager.IMPORTANCE_LOW))
    }

    fun questDone(ctx: Context, name: String, reward: String?) =
        post(ctx, 1000 + (name.hashCode() and 0x7FFF), CH_RESULT, "Quest completed", listOfNotNull(name, reward).joinToString(" · "))

    fun runFinished(ctx: Context, done: Int) =
        post(ctx, 3, CH_RESULT, "Quest run finished", if (done == 0) "Nothing to do right now." else "$done quest${if (done == 1) "" else "s"} completed.")

    fun alert(ctx: Context, title: String, text: String) = post(ctx, 2, CH_ALERT, title, text)

    /** The ongoing notification that keeps a long run alive in the foreground. */
    fun working(ctx: Context, text: String = "Working on your quests…"): Notification =
        NotificationCompat.Builder(ctx, CH_WORK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Quest Mate")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .build()

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx,
        0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun post(ctx: Context, id: Int, channel: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
