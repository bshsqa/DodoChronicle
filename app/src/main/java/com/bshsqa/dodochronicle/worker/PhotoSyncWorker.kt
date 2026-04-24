package com.bshsqa.dodochronicle.worker

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.bshsqa.dodochronicle.BuildConfig
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import com.bshsqa.dodochronicle.domain.usecase.SyncNewPhotosUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.last
import java.util.concurrent.TimeUnit

@HiltWorker
class PhotoSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncUseCase: SyncNewPhotosUseCase,
    private val eventRepository: EventRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val lastScanAt = eventRepository.getLatestPhotoTakenAt() ?: 0L
            val newPhotos = queryNewPhotos(lastScanAt)
            if (newPhotos.isEmpty()) return Result.success()
            syncUseCase.invoke(newPhotos).last()
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun queryNewPhotos(after: Long): List<Pair<String, Long>> {
        val uris = mutableListOf<Pair<String, Long>>()
        val selection = "${MediaStore.Images.Media.DATE_TAKEN} > ?"
        val selectionArgs = arrayOf(after.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        val limit = BuildConfig.PHOTO_SCAN_LIMIT

        applicationContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
            selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            var count = 0
            while (cursor.moveToNext() && (limit < 0 || count < limit)) {
                val id = cursor.getLong(idCol)
                val taken = cursor.getLong(dateCol)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                uris.add(uri.toString() to taken)
                count++
            }
        }
        return uris
    }

    companion object {
        const val WORK_NAME = "photo_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PhotoSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
