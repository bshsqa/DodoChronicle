package com.bshsqa.dodochronicle.data.local.db.dao

import androidx.room.*
import com.bshsqa.dodochronicle.data.local.db.entity.ChildEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChildDao {
    @Query("SELECT * FROM children ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ChildEntity>>

    @Query("SELECT * FROM children WHERE id = :id")
    suspend fun getById(id: String): ChildEntity?

    @Query("SELECT * FROM children LIMIT 1")
    suspend fun getFirst(): ChildEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(child: ChildEntity)

    @Query("UPDATE children SET faceEmbeddingsJson = :json WHERE id = :id")
    suspend fun updateEmbeddings(id: String, json: String)

    @Delete
    suspend fun delete(child: ChildEntity)
}
