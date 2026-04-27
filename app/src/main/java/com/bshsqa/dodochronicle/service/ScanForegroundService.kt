package com.bshsqa.dodochronicle.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import com.bshsqa.dodochronicle.BuildConfig
import com.bshsqa.dodochronicle.DodoApp
import com.bshsqa.dodochronicle.MainActivity
import com.bshsqa.dodochronicle.R
import com.bshsqa.dodochronicle.ml.FaceClusteringEngine
import com.bshsqa.dodochronicle.ml.FaceDetectorHelper
import com.bshsqa.dodochronicle.ml.FaceEmbedder
import com.bshsqa.dodochronicle.ml.PhotoEmbedding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class ScanForegroundService : Service() {

    @Inject lateinit var faceDetector: FaceDetectorHelper
    @Inject lateinit var faceEmbedder: FaceEmbedder
    @Inject lateinit var clusteringEngine: FaceClusteringEngine
    @Inject lateinit var stateHolder: ScanStateHolder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START  = "com.bshsqa.dodochronicle.action.SCAN_START"
        const val ACTION_CANCEL = "com.bshsqa.dodochronicle.action.SCAN_CANCEL"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "DodoChronicle::ScanWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L // 30분
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START  -> startScan()
            ACTION_CANCEL -> cancelScan()
        }
        return START_NOT_STICKY
    }

    private fun startScan() {
        if (scanJob?.isActive == true) return

        startForeground(
            NOTIFICATION_ID,
            buildProgressNotification(0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            .apply { acquire(WAKELOCK_TIMEOUT_MS) }

        scanJob = serviceScope.launch {
            try {
                performScan()
            } finally {
                stopSelf()
            }
        }
    }

    private fun cancelScan() {
        scanJob?.cancel()
        stateHolder.emit(ScanState.Cancelled)
        // WakeLock 해제 및 서비스 종료는 finally → stopSelf() → onDestroy() 에서 처리
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ─── 스캔 핵심 로직 ────────────────────────────────────────────────────────

    private suspend fun performScan() {
        val photoUris = queryPhotos()
        val total = photoUris.size
        stateHolder.emit(ScanState.Running(0, total))
        updateProgressNotification(0, total)

        val embeddings = mutableListOf<PhotoEmbedding>()
        var processed = 0
        var lastNotifiedPercent = -1

        for ((uri, takenAt) in photoUris) {
            currentCoroutineContext().ensureActive()

            val bmp = loadBitmap(uri)
            if (bmp != null) {
                val faces = faceDetector.detectFaces(bmp)
                if (faces.isNotEmpty()) {
                    val emb = faceEmbedder.embed(bmp, faces.first())
                    if (emb != null) embeddings.add(PhotoEmbedding(uri, takenAt, emb))
                }
            }
            processed++
            stateHolder.emit(ScanState.Running(processed, total))

            // 알림은 퍼센트 단위로 갱신해 Binder 호출 횟수를 최소화
            val currentPercent = if (total > 0) processed * 100 / total else 0
            if (currentPercent != lastNotifiedPercent) {
                lastNotifiedPercent = currentPercent
                updateProgressNotification(processed, total)
            }
        }

        val clusters = clusteringEngine.cluster(embeddings)
        if (clusters.isEmpty()) {
            stateHolder.emit(ScanState.Failed("아이 얼굴이 감지된 사진이 없습니다. 사진을 다시 선택해주세요"))
        } else {
            stateHolder.emit(ScanState.Done(clusters, embeddings.toList()))
            showCompletedNotification()
        }
    }

    // ─── MediaStore 조회 (InitViewModel 에서 이전) ────────────────────────────

    private fun queryPhotos(): List<Pair<String, Long>> {
        val uris = mutableListOf<Pair<String, Long>>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        val limit = BuildConfig.PHOTO_SCAN_LIMIT

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sortOrder
        )?.use { cursor ->
            val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            var count = 0
            while (cursor.moveToNext() && (limit < 0 || count < limit)) {
                val id     = cursor.getLong(idCol)
                val takenAt = cursor.getLong(dateCol)
                val uri    = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                )
                uris.add(uri.toString() to takenAt)
                count++
            }
        }
        return uris
    }

    private suspend fun loadBitmap(uri: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(Uri.parse(uri))?.use {
                android.graphics.BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) { null }
    }

    // ─── 알림 빌더 ─────────────────────────────────────────────────────────────

    private fun mainActivityPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildProgressNotification(processed: Int, total: Int): Notification {
        val contentText = when {
            total == 0 -> "사진 목록을 불러오는 중..."
            else       -> "${processed} / ${total}장 분석 중..."
        }
        return Notification.Builder(this, DodoApp.SCAN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_scan)
            .setContentTitle("사진 분석 중")
            .setContentText(contentText)
            .setProgress(total, processed, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainActivityPendingIntent())
            .build()
    }

    private fun updateProgressNotification(processed: Int, total: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildProgressNotification(processed, total))
    }

    private fun showCompletedNotification() {
        val notification = Notification.Builder(this, DodoApp.SCAN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_scan)
            .setContentTitle("사진 분류 완료")
            .setContentText("앱을 열어 아이 그룹을 선택해주세요.")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(mainActivityPendingIntent())
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}
