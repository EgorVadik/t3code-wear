package expo.modules.t3wearbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Holds the app in the foreground state so it can answer the watch with the screen off. */
class T3WearSyncService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val notification = notification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    // Restarted alone, the service would have no app behind it to answer the watch.
    return START_NOT_STICKY
  }

  private fun notification(): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Watch sync", NotificationManager.IMPORTANCE_MIN).apply {
          description = "Keeps T3 Code on your watch up to date"
          setShowBadge(false)
        },
      )
    }
    val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
      PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }
    return builder
      .setSmallIcon(applicationInfo.icon)
      .setContentTitle("Syncing to your watch")
      .setContentText("T3 Code on your watch shows what this phone sees")
      .setOngoing(true)
      .setShowWhen(false)
      .apply { open?.let(::setContentIntent) }
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "t3_watch_sync"
    private const val NOTIFICATION_ID = 7301

    fun start(context: Context) {
      val intent = Intent(context, T3WearSyncService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, T3WearSyncService::class.java))
    }
  }
}
