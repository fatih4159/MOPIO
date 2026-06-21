package com.mopio.container

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mopio.MopioApplication
import com.mopio.R

/**
 * Foreground service that hosts long-running container operations (bootstrap, build, flash).
 * Keeps the process alive during screen-off and signals progress via notifications.
 *
 * Callers bind to this service or send intents with the desired operation.
 * Actual implementation is wired in Phase 1 (build pipeline) and Phase 4 (flash).
 */
class ContainerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_NOTIFICATION_TITLE)
            ?: getString(R.string.notification_title_bootstrapping)
        startForeground(NOTIF_ID, buildNotification(title))
        return START_NOT_STICKY
    }

    fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, MopioApplication.CHANNEL_BUILD_FLASH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        const val EXTRA_NOTIFICATION_TITLE = "notif_title"
        private const val NOTIF_ID = 1

        fun startWithTitle(context: Context, title: String) {
            val intent = Intent(context, ContainerService::class.java)
                .putExtra(EXTRA_NOTIFICATION_TITLE, title)
            context.startForegroundService(intent)
        }
    }
}
