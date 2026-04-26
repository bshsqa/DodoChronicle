package com.bshsqa.dodochronicle.domain.repository

import com.bshsqa.dodochronicle.domain.model.Child
import kotlinx.coroutines.flow.Flow

interface ChildRepository {
    fun observeAll(): Flow<List<Child>>
    suspend fun getFirst(): Child?
    suspend fun save(child: Child)
    suspend fun updateEmbeddings(childId: String, embeddings: List<FloatArray>)
    suspend fun delete(child: Child)
    suspend fun deleteAll()
}
