package com.bshsqa.dodochronicle.media

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class PhotoDateSource(
    val uri: Uri,
    val dateTakenMillis: Long? = null,
    val dateAddedSeconds: Long? = null,
    val dateModifiedSeconds: Long? = null
)

data class ResolvedPhotoDate(
    val takenAtMillis: Long,
    val source: PhotoDateSourceType
)

enum class PhotoDateSourceType {
    MEDIASTORE_DATE_TAKEN,
    EXIF_DATE_TIME_ORIGINAL,
    EXIF_DATE_TIME_DIGITIZED,
    MEDIASTORE_DATE_ADDED,
    MEDIASTORE_DATE_MODIFIED
}

@Singleton
class PhotoDateResolver @Inject constructor() {
    fun resolve(
        contentResolver: ContentResolver,
        source: PhotoDateSource
    ): ResolvedPhotoDate? {
        validMillis(source.dateTakenMillis)?.let {
            return ResolvedPhotoDate(it, PhotoDateSourceType.MEDIASTORE_DATE_TAKEN)
        }

        readExifMillis(contentResolver, source.uri, ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
            return ResolvedPhotoDate(it, PhotoDateSourceType.EXIF_DATE_TIME_ORIGINAL)
        }
        readExifMillis(contentResolver, source.uri, ExifInterface.TAG_DATETIME_DIGITIZED)?.let {
            return ResolvedPhotoDate(it, PhotoDateSourceType.EXIF_DATE_TIME_DIGITIZED)
        }

        validSeconds(source.dateAddedSeconds)?.let {
            return ResolvedPhotoDate(it * 1000L, PhotoDateSourceType.MEDIASTORE_DATE_ADDED)
        }
        validSeconds(source.dateModifiedSeconds)?.let {
            return ResolvedPhotoDate(it * 1000L, PhotoDateSourceType.MEDIASTORE_DATE_MODIFIED)
        }

        return null
    }

    private fun readExifMillis(
        contentResolver: ContentResolver,
        uri: Uri,
        tag: String
    ): Long? = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val value = ExifInterface(input).getAttribute(tag).orEmpty()
            parseExifDate(value)
        }
    } catch (t: Throwable) {
        Log.d(TAG, "EXIF date read failed. uri=$uri tag=$tag", t)
        null
    }

    private fun parseExifDate(value: String): Long? {
        if (value.isBlank()) return null
        return runCatching {
            LocalDateTime.parse(value, EXIF_FORMATTER)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()?.let(::validMillis)
    }

    private fun validMillis(value: Long?): Long? =
        value?.takeIf { it >= MIN_REASONABLE_PHOTO_TIME_MILLIS }

    private fun validSeconds(value: Long?): Long? =
        value?.takeIf { it * 1000L >= MIN_REASONABLE_PHOTO_TIME_MILLIS }

    companion object {
        private const val TAG = "DodoPhotoDate"
        private val EXIF_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
        private val MIN_REASONABLE_PHOTO_TIME_MILLIS: Long =
            LocalDateTime.of(2000, 1, 1, 0, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
    }
}
