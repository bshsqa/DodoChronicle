package com.bshsqa.dodochronicle.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bshsqa.dodochronicle.data.local.db.dao.*
import com.bshsqa.dodochronicle.data.local.db.entity.*

@Database(
    entities = [
        ChildEntity::class,
        EventEntity::class,
        PhotoRecordEntity::class,
        PendingPhotoEntity::class,
        RejectedPhotoEntity::class,
        InitialScanSessionEntity::class,
        InitialScanPhotoEmbeddingEntity::class,
        InitialScanItemEntity::class,
        InitialScanClusterEntity::class,
        KakaoRoomEntity::class,
        KakaoMessageEntity::class,
        RetryChunkEntity::class
    ],
    version = 13,
    exportSchema = false
)
abstract class DodoDatabase : RoomDatabase() {
    abstract fun childDao(): ChildDao
    abstract fun eventDao(): EventDao
    abstract fun photoRecordDao(): PhotoRecordDao
    abstract fun pendingPhotoDao(): PendingPhotoDao
    abstract fun rejectedPhotoDao(): RejectedPhotoDao
    abstract fun initialScanDao(): InitialScanDao
    abstract fun kakaoRoomDao(): KakaoRoomDao
    abstract fun kakaoMessageDao(): KakaoMessageDao
    abstract fun retryChunkDao(): RetryChunkDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE children ADD COLUMN gender TEXT NOT NULL DEFAULT 'MALE'"
                )
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE photo_records ADD COLUMN isExcludedFromModel INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN longContent TEXT")
                db.execSQL("ALTER TABLE events ADD COLUMN rawExcerpt TEXT")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS retry_chunks (
                        id TEXT NOT NULL PRIMARY KEY,
                        roomId TEXT NOT NULL,
                        roomAlias TEXT NOT NULL,
                        sentAtStart INTEGER NOT NULL,
                        sentAtEnd INTEGER NOT NULL,
                        dateRange TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE events ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE events ADD COLUMN textEmbeddingJson TEXT NOT NULL DEFAULT '[]'"
                )
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN searchSummary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE events ADD COLUMN searchTagsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE events ADD COLUMN searchAliasesJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE events ADD COLUMN relatedKeywordsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE events ADD COLUMN searchContextVersion INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_photos (
                        uri TEXT NOT NULL PRIMARY KEY,
                        childId TEXT NOT NULL,
                        takenAt INTEGER NOT NULL,
                        addedAtSeconds INTEGER NOT NULL,
                        similarity REAL NOT NULL,
                        faceEmbeddingJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(childId) REFERENCES children(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_photos_childId ON pending_photos(childId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_photos_createdAt ON pending_photos(createdAt)")
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photo_records ADD COLUMN isMissing INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE photo_records ADD COLUMN lastSeenAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE photo_records ADD COLUMN missingCheckedAt INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rejected_photos (
                        uri TEXT NOT NULL PRIMARY KEY,
                        childId TEXT NOT NULL,
                        addedAtSeconds INTEGER NOT NULL,
                        rejectedAt INTEGER NOT NULL,
                        FOREIGN KEY(childId) REFERENCES children(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rejected_photos_childId ON rejected_photos(childId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rejected_photos_addedAtSeconds ON rejected_photos(addedAtSeconds)")
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS initial_scan_sessions (
                        id TEXT NOT NULL PRIMARY KEY,
                        childName TEXT NOT NULL,
                        birthDate TEXT NOT NULL,
                        gender TEXT NOT NULL,
                        referencePhotoUri TEXT NOT NULL,
                        status TEXT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        completedAt INTEGER,
                        totalCount INTEGER NOT NULL,
                        processedCount INTEGER NOT NULL,
                        elapsedSeconds INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS initial_scan_photo_embeddings (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        uri TEXT NOT NULL,
                        takenAt INTEGER NOT NULL,
                        embeddingJson TEXT NOT NULL,
                        clusterId INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_initial_scan_photo_embeddings_sessionId ON initial_scan_photo_embeddings(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_initial_scan_photo_embeddings_clusterId ON initial_scan_photo_embeddings(clusterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_initial_scan_photo_embeddings_uri ON initial_scan_photo_embeddings(uri)")
            }
        }
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE initial_scan_sessions ADD COLUMN lastCheckpointAt INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS initial_scan_items (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        uri TEXT NOT NULL,
                        takenAt INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        embeddingJson TEXT NOT NULL,
                        clusterId INTEGER,
                        errorMessage TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_initial_scan_items_sessionId ON initial_scan_items(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_initial_scan_items_status ON initial_scan_items(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_initial_scan_items_clusterId ON initial_scan_items(clusterId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_initial_scan_items_sessionId_uri ON initial_scan_items(sessionId, uri)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS initial_scan_clusters (
                        sessionId TEXT NOT NULL,
                        clusterId INTEGER NOT NULL,
                        embeddingSumJson TEXT NOT NULL,
                        count INTEGER NOT NULL,
                        representativeUrisJson TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(sessionId, clusterId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_initial_scan_clusters_sessionId ON initial_scan_clusters(sessionId)")
            }
        }
    }
}
