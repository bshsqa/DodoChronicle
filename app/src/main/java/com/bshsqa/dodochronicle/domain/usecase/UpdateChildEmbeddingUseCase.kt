package com.bshsqa.dodochronicle.domain.usecase

import com.bshsqa.dodochronicle.domain.repository.ChildRepository
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 타임라인에 사진이 추가/삭제되거나 학습 제외 상태가 바뀔 때마다 호출.
 * 학습 제외가 아닌 최신 50장의 임베딩 평균을 계산하여 Child의 기준 벡터를 갱신한다.
 */
class UpdateChildEmbeddingUseCase @Inject constructor(
    private val childRepository: ChildRepository,
    private val eventRepository: EventRepository
) {
    suspend operator fun invoke(childId: String) = withContext(Dispatchers.Default) {
        val embeddings = eventRepository.getLatest50Embeddings(childId)
        if (embeddings.isEmpty()) {
            childRepository.updateEmbeddings(childId, emptyList())
            return@withContext
        }

        val dim = embeddings.first().size
        val average = FloatArray(dim) { idx ->
            embeddings.sumOf { it[idx].toDouble() }.toFloat() / embeddings.size
        }

        // 단일 평균 벡터로 교체 (기존 멀티 임베딩 목록 대신 최신 평균 1개로 유지)
        childRepository.updateEmbeddings(childId, listOf(average))
    }
}
