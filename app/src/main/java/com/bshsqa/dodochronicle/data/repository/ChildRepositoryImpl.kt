package com.bshsqa.dodochronicle.data.repository

import com.bshsqa.dodochronicle.data.local.db.dao.ChildDao
import com.bshsqa.dodochronicle.data.local.db.entity.ChildEntity
import com.bshsqa.dodochronicle.domain.model.Child
import com.bshsqa.dodochronicle.domain.model.Gender
import com.bshsqa.dodochronicle.domain.repository.ChildRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChildRepositoryImpl @Inject constructor(
    private val dao: ChildDao
) : ChildRepository {

    override fun observeAll(): Flow<List<Child>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getFirst(): Child? = dao.getFirst()?.toDomain()

    override suspend fun save(child: Child) = dao.upsert(child.toEntity())

    override suspend fun updateEmbeddings(childId: String, embeddings: List<FloatArray>) {
        val json = Json.encodeToString(embeddings.map { it.toList() })
        dao.updateEmbeddings(childId, json)
    }

    override suspend fun delete(child: Child) = dao.delete(child.toEntity())

    private fun ChildEntity.toDomain(): Child {
        val embeddings = try {
            Json.decodeFromString<List<List<Float>>>(faceEmbeddingsJson)
                .map { it.toFloatArray() }
        } catch (e: Exception) { emptyList() }
        return Child(
            id = id,
            name = name,
            birthDate = LocalDate.ofEpochDay(birthDate),
            gender = try { Gender.valueOf(gender) } catch (e: Exception) { Gender.MALE },
            referencePhotoUri = referencePhotoUri,
            faceEmbeddings = embeddings
        )
    }

    private fun Child.toEntity() = ChildEntity(
        id = id,
        name = name,
        birthDate = birthDate.toEpochDay(),
        gender = gender.name,
        referencePhotoUri = referencePhotoUri,
        faceEmbeddingsJson = Json.encodeToString(faceEmbeddings.map { it.toList() })
    )
}
