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
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(SCAN_CHANNEL_ID, "사진 분석", NotificationManager.IMPORTANCE_LOW).apply {
                description = "초기화 시 사진 분석 진행 상황을 알립니다."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(SCAN_RESULT_CHANNEL_ID, "사진 분석 완료", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "사진 분석 완료 및 후속 작업 필요 여부를 알립니다."
                setShowBadge(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(IMPORT_CHANNEL_ID, "카카오 대화 가져오기", NotificationManager.IMPORTANCE_LOW).apply {
                description = "카카오 대화 가져오기 진행 상황을 알립니다."
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val SCAN_CHANNEL_ID   = "scan_channel"
        const val SCAN_RESULT_CHANNEL_ID = "scan_result_channel"
        const val IMPORT_CHANNEL_ID = "import_channel"
    }
}
