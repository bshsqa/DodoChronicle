package com.bshsqa.dodochronicle.domain.usecase

import android.content.Context
import android.net.Uri
import com.bshsqa.dodochronicle.domain.model.Event
import com.bshsqa.dodochronicle.domain.model.EventCategory
import com.bshsqa.dodochronicle.domain.model.EventSource
import com.bshsqa.dodochronicle.domain.model.PhotoRecord
import com.bshsqa.dodochronicle.domain.repository.ChildRepository
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import com.bshsqa.dodochronicle.ml.FaceDetectorHelper
import com.bshsqa.dodochronicle.ml.FaceEmbedder
import com.bshsqa.dodochronicle.ml.cosineSimilarity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

private const val AUTO_ADD_THRESHOLD = 0.75f
private const val AUTO_SKIP_THRESHOLD = 0.4f

class SyncNewPhotosUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val childRepository: ChildRepository,
    private val eventRepository: EventRepository,
    private val faceDetector: FaceDetectorHelper,
    private val faceEmbedder: FaceEmbedder
) {
    data class SyncProgress(
        val processed: Int,
        val total: Int,
        val autoAdded: Int,
        val needsConfirmation: List<PendingPhoto>
    )

    data class PendingPhoto(
        val uri: String,
        val takenAt: Long,
        val similarity: Float,
        val faceEmbedding: FloatArray
    )

    fun invoke(newPhotoUris: List<Pair<String, Long>>): Flow<SyncProgress> = flow {
        val child = childRepository.getFirst() ?: return@flow
        if (child.faceEmbeddings.isEmpty()) return@flow

        val existingUris = eventRepository.getAllPhotoUris().toSet()
        val toProcess = newPhotoUris.filter { (uri, _) -> uri !in existingUris }

        var processed = 0
        var autoAdded = 0
        val needsConfirmation = mutableListOf<PendingPhoto>()

        for ((uri, takenAt) in toProcess) {
            val bitmap = loadBitmap(uri) ?: run {
                processed++
                emit(SyncProgress(processed, toProcess.size, autoAdded, needsConfirmation.toList()))
                continue
            }

            val faces = faceDetector.detectFaces(bitmap)
            if (faces.isEmpty()) {
                processed++
                emit(SyncProgress(processed, toProcess.size, autoAdded, needsConfirmation.toList()))
                continue
            }

            val embedding = faceEmbedder.embed(bitmap, faces.first()) ?: run {
                processed++
                emit(SyncProgress(processed, toProcess.size, autoAdded, needsConfirmation.toList()))
                continue
            }

            val maxSimilarity = child.faceEmbeddings.maxOf { ref -> cosineSimilarity(ref, embedding) }

            when {
                maxSimilarity >= AUTO_ADD_THRESHOLD -> {
                    savePhotoEvent(child.id, uri, takenAt, embedding, maxSimilarity)
                    autoAdded++
                }
                maxSimilarity >= AUTO_SKIP_THRESHOLD -> {
                    needsConfirmation.add(PendingPhoto(uri, takenAt, maxSimilarity, embedding))
                }
                // < AUTO_SKIP_THRESHOLD: 완전히 다른 사람 — 조용히 skip
            }

            processed++
            emit(SyncProgress(processed, toProcess.size, autoAdded, needsConfirmation.toList()))
        }
    }

    suspend fun confirmPhoto(pending: PendingPhoto, accept: Boolean, childId: String) {
        if (accept) {
            savePhotoEvent(childId, pending.uri, pending.takenAt, pending.faceEmbedding, pending.similarity)
        }
    }

    private suspend fun savePhotoEvent(
        childId: String,
        uri: String,
        takenAt: Long,
        embedding: FloatArray,
        similarity: Float
    ) {
        val date = Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val eventId = UUID.randomUUID().toString()
        eventRepository.insert(
            Event(
                id = eventId,
                childId = childId,
                date = date,
                category = EventCategory.PHOTO,
                content = uri,
                source = EventSource.PHOTO
            )
        )
        eventRepository.insertPhotoRecord(
            PhotoRecord(
                id = UUID.randomUUID().toString(),
                eventId = eventId,
                localUri = uri,
                takenAt = takenAt,
                faceEmbedding = embedding,
                similarityScore = similarity
            )
        )
    }

    private fun loadBitmap(uri: String): android.graphics.Bitmap? = try {
        val inputStream = context.contentResolver.openInputStream(Uri.parse(uri))
        android.graphics.BitmapFactory.decodeStream(inputStream)
    } catch (e: Exception) {
        null
    }
}
