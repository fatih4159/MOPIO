package com.mopio

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class MopioApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_BUILD_FLASH,
            getString(R.string.notification_channel_build),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Build, flash, and bootstrap progress" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_BUILD_FLASH = "build_flash"
    }
}
