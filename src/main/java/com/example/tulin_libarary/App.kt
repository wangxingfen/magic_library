package com.example.tulin_libarary

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val progressChannel = NotificationChannel(
                "generation_progress",
                "创作进度",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示AI书籍创作的实时进度"
                setShowBadge(false)
            }

            val resultChannel = NotificationChannel(
                "generation_result",
                "创作结果",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "AI书籍创作完成或失败的通知"
            }

            manager.createNotificationChannel(progressChannel)
            manager.createNotificationChannel(resultChannel)
        }
    }
}
