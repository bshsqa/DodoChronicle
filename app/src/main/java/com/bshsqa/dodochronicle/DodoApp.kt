package com.bshsqa.dodochronicle

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DodoApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val scanChannel = NotificationChannel(
            SCAN_CHANNEL_ID,
            "사진 분석",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "초기화 시 사진 분석 진행 상황을 알립니다."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(scanChannel)
    }

    companion object {
        const val SCAN_CHANNEL_ID = "scan_channel"
    }
}
