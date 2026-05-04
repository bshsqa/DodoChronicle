package com.bshsqa.dodochronicle.domain.usecase

import android.content.Context
import android.net.Uri
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class CheckMissingPhotosUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventRepository: EventRepository
) {
    data class Result(
        val checked: Int,
        val missing: Int,
        val restored: Int
    )

    suspend operator fun invoke(): Result = withContext(Dispatchers.IO) {
        val records = eventRepository.getAllPhotoRecords()
        val checkedAt = System.currentTimeMillis()
        var missing = 0
        var restored = 0

        records.forEach { record ->
            val canOpen = canOpen(record.localUri)
            if (canOpen) {
                if (record.isMissing) restored++
                eventRepository.updatePhotoMissingState(
                    photoRecordId = record.id,
                    isMissing = false,
                    checkedAt = checkedAt,
                    lastSeenAt = checkedAt
                )
            } else {
                missing++
                eventRepository.updatePhotoMissingState(
                    photoRecordId = record.id,
                    isMissing = true,
                    checkedAt = checkedAt,
                    lastSeenAt = record.lastSeenAt
                )
            }
        }

        Result(
            checked = records.size,
            missing = missing,
            restored = restored
        )
    }

    private fun canOpen(uri: String): Boolean =
        try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
}
