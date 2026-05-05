package com.bshsqa.dodochronicle.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import com.bshsqa.dodochronicle.BuildConfig
import com.bshsqa.dodochronicle.DodoApp
import com.bshsqa.dodochronicle.MainActivity
import com.bshsqa.dodochronicle.R
import com.bshsqa.dodochronicle.data.local.db.dao.InitialScanDao
import com.bshsqa.dodochronicle.data.local.db.entity.InitialScanClusterEntity
import com.bshsqa.dodochronicle.data.local.db.entity.InitialScanItemEntity
import com.bshsqa.dodochronicle.media.PhotoDateResolver
import com.bshsqa.dodochronicle.media.PhotoDateSource
import com.bshsqa.dodochronicle.ml.FaceCluster
import com.bshsqa.dodochronicle.ml.FaceDetectorHelper
import com.bshsqa.dodochronicle.ml.FaceEmbedder
import com.bshsqa.dodochronicle.ml.PhotoEmbedding
import com.bshsqa.dodochronicle.ml.cosineSimilarity
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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class ScanForegroundService : Service() {

    @Inject lateinit var faceDetector: FaceDetectorHelper
    @Inject lateinit var faceEmbedder: FaceEmbedder
    @Inject lateinit var stateHolder: ScanStateHolder
    @Inject lateinit var initialScanDao: InitialScanDao
    @Inject lateinit var photoDateResolver: PhotoDateResolver

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START = "com.bshsqa.dodochronicle.action.SCAN_START"
        const val ACTION_CANCEL = "com.bshsqa.dodochronicle.action.SCAN_CANCEL"
        const val EXTRA_SESSION_ID = "extra_session_id"

        private const val NOTIFICATION_ID = 1001
        private const val RESULT_NOTIFICATION_ID = 1003
        private const val TAG = "DodoInitialScan"
        private const val WAKELOCK_TAG = "DodoChronicle::ScanWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L
        private const val MAX_DECODE_DIMENSION = 1600
        private const val CHECKPOINT_SIZE = 500
        private const val CLUSTER_THRESHOLD = 0.68f
        private const val MAX_REPRESENTATIVE_URIS = 9

        private const val STATUS_PENDING = "PENDING"
        private const val STATUS_PROCESSING = "PROCESSING"
        private const val STATUS_PROCESSED = "PROCESSED"
        private const val STATUS_NO_FACE = "NO_FACE"
        private const val STATUS_FAILED = "FAILED"
        private const val STATUS_RUNNING = "RUNNING"
        private const val STATUS_PREPARING_ITEMS = "PREPARING_ITEMS"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startScan(intent.getStringExtra(EXTRA_SESSION_ID).orEmpty())
            ACTION_CANCEL -> cancelScan()
        }
        return START_REDELIVER_INTENT
    }

    private fun startScan(sessionId: String) {
        if (scanJob?.isActive == true) return
        if (sessionId.isBlank()) {
            stateHolder.emit(ScanState.Failed("사진 분석 세션을 시작할 수 없습니다. 다시 시도해주세요."))
            stopSelf()
            return
        }

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
                performScan(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Initial scan stopped by unexpected error. sessionId=$sessionId", t)
                stateHolder.emit(ScanState.Failed("사진 분석 중 오류가 발생했습니다. 앱을 다시 열면 이어서 진행할 수 있습니다."))
            } finally {
                stopSelf()
            }
        }
    }

    private fun cancelScan() {
        scanJob?.cancel()
        stateHolder.emit(ScanState.Cancelled)
        androidx.core.app.ServiceCompat.stopForeground(
            this,
            androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private suspend fun performScan(sessionId: String) {
        val session = initialScanDao.getSession(sessionId)
        if (session == null) {
            stateHolder.emit(ScanState.Failed("사진 분석 세션을 찾을 수 없습니다."))
            return
        }

        val runStartedAt = System.currentTimeMillis()
        ensureScanItems(sessionId)
        initialScanDao.resetProcessingItems(sessionId)

        val total = initialScanDao.countItems(sessionId)
        val clusters = initialScanDao.getClusters(sessionId)
            .mapNotNull { it.toMutableScanCluster() }
            .toMutableList()
        var processed = initialScanDao.countFinishedItems(sessionId)

        emitProgress(sessionId, total, processed, session.elapsedSeconds, checkpoint = false)
        updateProgressNotification(processed, total)
        Log.i(TAG, "Initial scan started. sessionId=$sessionId processed=$processed total=$total")

        while (true) {
            currentCoroutineContext().ensureActive()
            val items = initialScanDao.getNextPendingItems(sessionId, CHECKPOINT_SIZE)
            if (items.isEmpty()) break

            val now = System.currentTimeMillis()
            initialScanDao.updateItems(items.map { it.copy(status = STATUS_PROCESSING, updatedAt = now) })

            val processedItems = mutableListOf<InitialScanItemEntity>()
            for (item in items) {
                currentCoroutineContext().ensureActive()
                processedItems += processItem(item, clusters)
                val visibleProcessed = processed + processedItems.size
                val elapsedSeconds = session.elapsedSeconds + (System.currentTimeMillis() - runStartedAt) / 1000
                stateHolder.emit(ScanState.Running(visibleProcessed, total, elapsedSeconds, sessionId))
                updateProgressNotification(visibleProcessed, total)
            }

            processed += processedItems.size
            val elapsedSeconds = session.elapsedSeconds + (System.currentTimeMillis() - runStartedAt) / 1000
            initialScanDao.saveCheckpoint(
                sessionId = sessionId,
                totalCount = total,
                processedCount = processed,
                elapsedSeconds = elapsedSeconds,
                items = processedItems,
                clusters = clusters.map { it.toEntity(sessionId) }
            )
            Log.i(TAG, "Initial scan checkpoint. sessionId=$sessionId processed=$processed total=$total clusters=${clusters.size}")
            stateHolder.emit(ScanState.Running(processed, total, elapsedSeconds, sessionId))
            updateProgressNotification(processed, total)
        }

        val elapsedSeconds = session.elapsedSeconds + (System.currentTimeMillis() - runStartedAt) / 1000
        if (clusters.isEmpty()) {
            stateHolder.emit(ScanState.Failed("얼굴이 감지된 사진이 없습니다. 사진을 다시 선택해주세요."))
            androidx.core.app.ServiceCompat.stopForeground(
                this,
                androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE
            )
            return
        }

        initialScanDao.markCompleted(
            sessionId = sessionId,
            completedAt = System.currentTimeMillis(),
            totalCount = total,
            processedCount = processed,
            elapsedSeconds = elapsedSeconds
        )
        val done = buildDoneState(sessionId, elapsedSeconds)
        Log.i(TAG, "Initial scan completed. sessionId=$sessionId processed=$processed total=$total clusters=${clusters.size}")
        stateHolder.emit(done)
        showCompletedNotification(elapsedSeconds)
    }

    private suspend fun ensureScanItems(sessionId: String) {
        val session = initialScanDao.getSession(sessionId)
        val existingCount = initialScanDao.countItems(sessionId)
        if (existingCount > 0 && session != null && session.totalCount > 0) return
        if (existingCount > 0) {
            initialScanDao.deleteItems(sessionId)
            initialScanDao.deleteClusters(sessionId)
        }

        val cutoffDate = runCatching { LocalDate.parse(session?.birthDate.orEmpty()) }
            .getOrNull()
            ?.minusYears(1)
        val cutoffMillis = cutoffDate
            ?.atStartOfDay(ZoneId.systemDefault())
            ?.toInstant()
            ?.toEpochMilli()
            ?: 0L

        initialScanDao.updateProgress(sessionId, STATUS_PREPARING_ITEMS, 0, 0, 0L)
        stateHolder.emit(ScanState.Running(0, 0, session?.elapsedSeconds ?: 0L, sessionId))
        updateProgressNotification(0, 0)

        val photos = queryPhotos(cutoffMillis)
        photos.chunked(1000).forEach { chunk ->
            initialScanDao.insertItems(
                chunk.map { (uri, takenAt) ->
                    InitialScanItemEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        uri = uri,
                        takenAt = takenAt,
                        status = STATUS_PENDING,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            )
        }
        initialScanDao.updateProgress(sessionId, STATUS_RUNNING, photos.size, 0, 0L)
    }

    private suspend fun processItem(
        item: InitialScanItemEntity,
        clusters: MutableList<MutableScanCluster>
    ): InitialScanItemEntity {
        var bitmap: Bitmap? = null
        return try {
            bitmap = loadBitmap(item.uri)
                ?: return item.failed("bitmap load failed")
            val faces = faceDetector.detectFaces(bitmap)
            if (faces.isEmpty()) return item.copy(
                status = STATUS_NO_FACE,
                embeddingJson = "[]",
                clusterId = null,
                errorMessage = "",
                updatedAt = System.currentTimeMillis()
            )

            val embedding = faceEmbedder.embed(bitmap, faces.first())
                ?: return item.failed("embedding failed")
            val clusterId = assignToCluster(item.uri, embedding, clusters)
            item.copy(
                status = STATUS_PROCESSED,
                embeddingJson = Json.encodeToString(embedding.toList()),
                clusterId = clusterId,
                errorMessage = "",
                updatedAt = System.currentTimeMillis()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Initial scan item failed. uri=${item.uri}", t)
            item.failed(t.message ?: t::class.java.simpleName)
        } finally {
            bitmap?.recycle()
        }
    }

    private fun InitialScanItemEntity.failed(message: String): InitialScanItemEntity =
        copy(
            status = STATUS_FAILED,
            embeddingJson = "[]",
            clusterId = null,
            errorMessage = message,
            updatedAt = System.currentTimeMillis()
        )

    private fun assignToCluster(
        uri: String,
        embedding: FloatArray,
        clusters: MutableList<MutableScanCluster>
    ): Int {
        var bestCluster: MutableScanCluster? = null
        var bestSimilarity = CLUSTER_THRESHOLD

        clusters.forEach { cluster ->
            val sim = cosineSimilarity(cluster.centroid(), embedding)
            if (sim > bestSimilarity) {
                bestSimilarity = sim
                bestCluster = cluster
            }
        }

        val target = bestCluster ?: MutableScanCluster(
            clusterId = (clusters.maxOfOrNull { it.clusterId } ?: -1) + 1,
            embeddingSum = FloatArray(embedding.size),
            count = 0,
            representativeUris = mutableListOf()
        ).also { clusters.add(it) }

        target.add(uri, embedding)
        return target.clusterId
    }

    private suspend fun emitProgress(
        sessionId: String,
        total: Int,
        processed: Int,
        elapsedSeconds: Long,
        checkpoint: Boolean
    ) {
        if (checkpoint) {
            initialScanDao.updateProgress(sessionId, STATUS_RUNNING, total, processed, elapsedSeconds)
        }
        stateHolder.emit(ScanState.Running(processed, total, elapsedSeconds, sessionId))
    }

    private suspend fun buildDoneState(sessionId: String, elapsedSeconds: Long): ScanState.Done {
        val processedItems = initialScanDao.getProcessedItems(sessionId)
        val embeddings = processedItems.mapNotNull { item ->
            val embedding = item.embeddingJson.toFloatArrayOrNull() ?: return@mapNotNull null
            PhotoEmbedding(item.uri, item.takenAt, embedding)
        }
        val itemsByCluster = processedItems.groupBy { it.clusterId ?: -1 }
        val embeddingsByUri = embeddings.associateBy { it.uri }
        val clusters = initialScanDao.getClusters(sessionId).map { row ->
            val uris = row.representativeUrisJson.toStringListOrEmpty()
            val clusterItems = itemsByCluster[row.clusterId].orEmpty()
            FaceCluster(
                id = row.clusterId,
                embeddings = clusterItems.mapNotNull { embeddingsByUri[it.uri]?.embedding },
                representativeUris = uris.ifEmpty { clusterItems.take(MAX_REPRESENTATIVE_URIS).map { it.uri } }
            )
        }.filter { it.embeddings.isNotEmpty() }
            .sortedByDescending { it.embeddings.size }

        return ScanState.Done(clusters, embeddings, elapsedSeconds, sessionId)
    }

    private fun queryPhotos(cutoffMillis: Long): List<Pair<String, Long>> {
        val uris = mutableListOf<Pair<String, Long>>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val limit = BuildConfig.PHOTO_SCAN_LIMIT

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            var count = 0
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
                val resolvedDate = photoDateResolver.resolve(
                    contentResolver,
                    PhotoDateSource(
                        uri = uri,
                        dateTakenMillis = cursor.getLong(dateCol),
                        dateAddedSeconds = cursor.getLong(addedCol),
                        dateModifiedSeconds = cursor.getLong(modifiedCol)
                    )
                ) ?: continue
                if (resolvedDate.takenAtMillis < cutoffMillis) continue
                uris.add(uri.toString() to resolvedDate.takenAtMillis)
                count++
                if (limit >= 0 && count >= limit) break
            }
        }
        return uris.sortedByDescending { it.second }
    }

    private suspend fun loadBitmap(uri: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val parsedUri = Uri.parse(uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(parsedUri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            }
            contentResolver.openInputStream(parsedUri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Bitmap load failed. uri=$uri", t)
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height
        while (sampledWidth / 2 >= MAX_DECODE_DIMENSION || sampledHeight / 2 >= MAX_DECODE_DIMENSION) {
            sampleSize *= 2
            sampledWidth /= 2
            sampledHeight /= 2
        }
        return sampleSize
    }

    private fun mainActivityPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildProgressNotification(processed: Int, total: Int): Notification {
        val contentText = when {
            total == 0 -> "사진 목록을 불러오는 중..."
            else -> "${processed} / ${total}장 분석 중..."
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

    private fun showCompletedNotification(elapsedSeconds: Long) {
        val notification = Notification.Builder(this, DodoApp.SCAN_RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_scan)
            .setContentTitle("사진 분류 완료")
            .setContentText("총 ${formatDuration(elapsedSeconds)} 소요. 얼굴 그룹을 선택해주세요.")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(mainActivityPendingIntent())
            .build()

        androidx.core.app.ServiceCompat.stopForeground(
            this,
            androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE
        )
        getSystemService(NotificationManager::class.java)
            .notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun formatDuration(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}분 ${seconds}초" else "${seconds}초"
    }

    private data class MutableScanCluster(
        val clusterId: Int,
        val embeddingSum: FloatArray,
        var count: Int,
        val representativeUris: MutableList<String>
    ) {
        fun centroid(): FloatArray =
            if (count <= 0) embeddingSum else FloatArray(embeddingSum.size) { embeddingSum[it] / count }

        fun add(uri: String, embedding: FloatArray) {
            for (i in embedding.indices) embeddingSum[i] += embedding[i]
            count += 1
            if (representativeUris.size < MAX_REPRESENTATIVE_URIS) representativeUris += uri
        }

        fun toEntity(sessionId: String): InitialScanClusterEntity = InitialScanClusterEntity(
            sessionId = sessionId,
            clusterId = clusterId,
            embeddingSumJson = Json.encodeToString(embeddingSum.toList()),
            count = count,
            representativeUrisJson = Json.encodeToString(representativeUris),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun InitialScanClusterEntity.toMutableScanCluster(): MutableScanCluster? {
        val sum = embeddingSumJson.toFloatArrayOrNull() ?: return null
        return MutableScanCluster(
            clusterId = clusterId,
            embeddingSum = sum,
            count = count,
            representativeUris = representativeUrisJson.toStringListOrEmpty().toMutableList()
        )
    }

    private fun String.toFloatArrayOrNull(): FloatArray? =
        runCatching { Json.decodeFromString<List<Float>>(this).toFloatArray() }.getOrNull()

    private fun String.toStringListOrEmpty(): List<String> =
        runCatching { Json.decodeFromString<List<String>>(this) }.getOrDefault(emptyList())
}
