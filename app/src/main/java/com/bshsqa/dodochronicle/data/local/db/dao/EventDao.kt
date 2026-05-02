package com.bshsqa.dodochronicle.data.local.db.dao

import androidx.room.*
import com.bshsqa.dodochronicle.data.local.db.entity.EventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Query("""
        SELECT * FROM events
        WHERE childId = :childId
        AND isHidden = 0
        AND (:category IS NULL OR category = :category)
        AND (:onlyFavorite = 0 OR isFavorite = 1)
        ORDER BY date ASC
    """)
    fun observe(
        childId: String,
        category: String? = null,
        onlyFavorite: Boolean = false
    ): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: String): EventEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<EventEntity>)

    @Query("""
        SELECT * FROM events
        WHERE childId = :childId
        AND category != 'PHOTO'
        ORDER BY date ASC
    """)
    suspend fun getAllTextEvents(childId: String): List<EventEntity>

    @Query("UPDATE events SET textEmbeddingJson = :textEmbeddingJson WHERE id = :id")
    suspend fun updateTextEmbedding(id: String, textEmbeddingJson: String)

    @Query("UPDATE events SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE events SET isHidden = :isHidden WHERE id = :id")
    suspend fun setHidden(id: String, isHidden: Boolean)

    @Query("SELECT * FROM events WHERE childId = :childId AND isHidden = 1 ORDER BY date ASC")
    fun observeHidden(childId: String): Flow<List<EventEntity>>

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM events WHERE childId = :childId")
    suspend fun deleteAllForChild(childId: String)
}
