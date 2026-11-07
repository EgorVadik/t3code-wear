package com.t3tools.t3code.wear.link

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.t3tools.t3code.wear.MainActivity
import com.t3tools.t3code.wear.R

/**
 * Shows working threads on the watch face as an ongoing activity, like a playing video or a
 * running timer. Tapping it opens the thread, or the thread list when several are working.
 * The phone reports working threads, so this updates with the watch app closed.
 */
class OngoingThreads(private val context: Context) {
    /** What the phone last reported, kept so it can be shown once notifications are allowed. */
    private var reported: List<WorkingThread> = emptyList()
    private var shown: List<WorkingThread> = emptyList()

    @Synchronized
    fun update(working: List<WorkingThread>) {
        reported = working
        // An empty list always cancels: a restarted process may have lost track of a posted one.
        if (working.isNotEmpty() && working == shown) return
        val manager = NotificationManagerCompat.from(context)
        if (working.isEmpty()) {
            manager.cancel(NOTIFICATION_ID)
            shown = working
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Working threads", NotificationManager.IMPORTANCE_LOW),
        )
        val single = working.singleOrNull()
        val summary = single?.title?.ifBlank { null } ?: "${working.size} threads working"
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply {
                    single?.let {
                        putExtra(MainActivity.EXTRA_ENVIRONMENT_ID, it.environmentId)
                        putExtra(MainActivity.EXTRA_THREAD_ID, it.threadId)
                    }
                },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_working)
            .setContentTitle("Working")
            .setContentText(summary)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        OngoingActivity.Builder(context, NOTIFICATION_ID, notification)
            .setStaticIcon(R.drawable.ic_working)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(summary).build())
            .build()
            .apply(context)
        manager.notify(NOTIFICATION_ID, notification.build())
        shown = working
    }

    /** Posts what the phone last reported, e.g. once notifications are allowed. */
    @Synchronized
    fun repost() {
        shown = emptyList()
        update(reported)
    }

    private companion object {
        const val CHANNEL_ID = "t3_working_threads"
        const val NOTIFICATION_ID = 3301
    }
}
