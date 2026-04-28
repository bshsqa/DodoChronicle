package com.bshsqa.dodochronicle.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import com.bshsqa.dodochronicle.DodoApp
import com.bshsqa.dodochronicle.MainActivity
import com.bshsqa.dodochronicle.R
import com.bshsqa.dodochronicle.domain.usecase.ImportKakaoUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class KakaoImportService : Service() {

    @Inject lateinit var importKakaoUseCase: ImportKakaoUseCase
    @Inject lateinit var stateHolder: ImportStateHolder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var importJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var cancelled = false

    companion object {
        const val ACTION_START  = "com.bshsqa.dodochronicle.action.IMPORT_START"
        const val ACTION_CANCEL = "com.bshsqa.dodochronicle.action.IMPORT_CANCEL"
        const val EXTRA_URI        = "extra_uri"
        const val EXTRA_ROOM_ALIAS = "extra_room_alias"
        private const val NOTIFICATION_ID = 1002
        private const val WAKELOCK_TAG = "DodoChronicle::ImportWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L // 1시간
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val uri = intent.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY
                val roomAlias = intent.getStringExtra(EXTRA_ROOM_ALIAS) ?: return START_NOT_STICKY
                startImport(uri, roomAlias)
            }
            ACTION_CANCEL -> cancelImport()
        }
        return START_NOT_STICKY
    }

    private fun startImport(uriString: String, roomAlias: String) {
        if (importJob?.isActive == true) return
        cancelled = false

        startForeground(
            NOTIFICATION_ID,
            buildProgressNotification(0, 0, ""),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            .apply { acquire(WAKELOCK_TIMEOUT_MS) }

        importJob = serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                val stream = contentResolver.openInputStream(Uri.parse(uriString))
                if (stream == null) {
                    stateHolder.emit(ImportState.Error("파일을 열 수 없습니다"))
                    return@launch
                }

                stateHolder.emit(ImportState.Running(0, 0, ""))

                val result = stream.use {
                    importKakaoUseCase(
                        it, roomAlias,
                        onProgress = { progress ->
                            stateHolder.emit(ImportState.Running(
                                chunksDone = progress.chunkIndex,
                                totalChunks = progress.totalChunks,
                                dateRange = progress.dateRange
                            ))
                            updateProgressNotification(
                                progress.chunkIndex + 1,
                                progress.totalChunks,
                                progress.dateRange
                            )
                        },
                        isCancelled = { cancelled }
                    )
                }

                val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
                when (result) {
                    is ImportKakaoUseCase.Result.Success -> {
                        stateHolder.emit(ImportState.Done(
                            addedMessages = result.addedMessages,
                            addedEvents = result.addedEvents,
                            apiRequests = result.apiRequests,
                            totalTokens = result.totalTokens,
                            failedChunks = result.failedChunks,
                            elapsedSeconds = elapsedSeconds,
                            cancelled = result.cancelled
                        ))
                        showCompletedNotification(result.addedMessages, result.addedEvents)
                    }
                    is ImportKakaoUseCase.Result.Error -> {
                        stateHolder.emit(ImportState.Error(result.message))
                        showErrorNotification(result.message)
                    }
                }
            } finally {
                stopSelf()
            }
        }
    }

    private fun cancelImport() {
        cancelled = true
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildProgressNotification(chunksDone: Int, totalChunks: Int, dateRange: String): Notification {
        val contentText = when {
            totalChunks == 0 -> "분석 준비 중..."
            else -> "${chunksDone}/${totalChunks} 청크 · $dateRange"
        }
        return Notification.Builder(this, DodoApp.IMPORT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_scan)
            .setContentTitle("카카오 대화 가져오는 중")
            .setContentText(contentText)
            .setProgress(totalChunks, chunksDone, totalChunks == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainActivityIntent())
            .build()
    }

    private fun updateProgressNotification(chunksDone: Int, totalChunks: Int, dateRange: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildProgressNotification(chunksDone, totalChunks, dateRange))
    }

    private fun showCompletedNotification(addedMessages: Int, addedEvents: Int) {
        val notification = Notification.Builder(this, DodoApp.IMPORT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_scan)
            .setContentTitle("카카오 대화 가져오기 완료")
            .setContentText("메시지 ${addedMessages}건, 이벤트 ${addedEvents}건 추가")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(mainActivityIntent())
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
        androidx.core.app.ServiceCompat.stopForeground(this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_DETACH)
    }

    private fun showErrorNotification(message: String) {
        val notification = Notification.Builder(this, DodoApp.IMPORT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_scan)
            .setContentTitle("카카오 대화 가져오기 실패")
            .setContentText(message)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(mainActivityIntent())
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
        androidx.core.app.ServiceCompat.stopForeground(this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_DETACH)
    }
}
