package com.bshsqa.dodochronicle.worker

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.bshsqa.dodochronicle.prefs.AppPrefsKeys
import com.bshsqa.dodochronicle.BuildConfig
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import com.bshsqa.dodochronicle.domain.usecase.SyncNewPhotosUseCase
import com.bshsqa.dodochronicle.domain.usecase.SyncNewPhotosUseCase.PhotoCandidate
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import java.util.concurrent.TimeUnit

@HiltWorker
class PhotoSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncUseCase: SyncNewPhotosUseCase,
    private val eventRepository: EventRepository,
    private val dataStore: DataStore<Preferences>
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val prefs = dataStore.data.first()
            val lastAddedAt = prefs[AppPrefsKeys.LAST_PHOTO_SYNC_ADDED_AT_SECONDS] ?: 0L
            val initialCutoffAt = (prefs[AppPrefsKeys.INITIAL_PHOTO_SYNC_CUTOFF_AT] ?: 0L) / 1000L
            val syncAfter = maxOf(lastAddedAt, initialCutoffAt)
            val newPhotos = queryNewPhotos(syncAfter)
            if (newPhotos.isEmpty()) return Result.success()
            syncUseCase.invoke(newPhotos).last()
            val maxAddedAt = newPhotos.maxOfOrNull { it.addedAtSeconds } ?: 0L
            if (maxAddedAt > 0L) {
                dataStore.edit { editPrefs ->
                    editPrefs[AppPrefsKeys.LAST_PHOTO_SYNC_ADDED_AT_SECONDS] = maxAddedAt
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun queryNewPhotos(afterAddedAtSeconds: Long): List<PhotoCandidate> {
        val uris = mutableListOf<PhotoCandidate>()
        val queryStart = (afterAddedAtSeconds - PHOTO_SYNC_OVERLAP_SECONDS).coerceAtLeast(0L)
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(queryStart.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val limit = BuildConfig.PHOTO_SCAN_LIMIT

        applicationContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED
            ),
            selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            var count = 0
            while (cursor.moveToNext() && (limit < 0 || count < limit)) {
                val id = cursor.getLong(idCol)
                val taken = cursor.getLong(takenCol)
                val added = cursor.getLong(addedCol)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                val takenAt = if (taken > 0L) taken else added * 1000L
                if (added > 0L && takenAt > 0L) {
                    uris.add(PhotoCandidate(uri.toString(), takenAt, added))
                }
                count++
            }
        }
        return uris
    }

    companion object {
        const val WORK_NAME = "photo_sync"
        private const val PHOTO_SYNC_OVERLAP_SECONDS = 86_400L

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
