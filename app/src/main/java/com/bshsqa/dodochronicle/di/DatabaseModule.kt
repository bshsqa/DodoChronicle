package com.bshsqa.dodochronicle.di

import android.content.Context
import androidx.room.Room
import com.bshsqa.dodochronicle.data.local.db.DodoDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DodoDatabase =
        Room.databaseBuilder(context, DodoDatabase::class.java, "dodo.db")
            .addMigrations(
                DodoDatabase.MIGRATION_1_2,
                DodoDatabase.MIGRATION_2_3,
                DodoDatabase.MIGRATION_3_4,
                DodoDatabase.MIGRATION_4_5,
                DodoDatabase.MIGRATION_5_6,
                DodoDatabase.MIGRATION_6_7,
                DodoDatabase.MIGRATION_7_8,
                DodoDatabase.MIGRATION_8_9,
                DodoDatabase.MIGRATION_9_10,
                DodoDatabase.MIGRATION_10_11,
                DodoDatabase.MIGRATION_11_12,
                DodoDatabase.MIGRATION_12_13
            )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun provideChildDao(db: DodoDatabase) = db.childDao()
    @Provides fun provideEventDao(db: DodoDatabase) = db.eventDao()
    @Provides fun providePhotoRecordDao(db: DodoDatabase) = db.photoRecordDao()
    @Provides fun providePendingPhotoDao(db: DodoDatabase) = db.pendingPhotoDao()
    @Provides fun provideRejectedPhotoDao(db: DodoDatabase) = db.rejectedPhotoDao()
    @Provides fun provideInitialScanDao(db: DodoDatabase) = db.initialScanDao()
    @Provides fun provideKakaoRoomDao(db: DodoDatabase) = db.kakaoRoomDao()
    @Provides fun provideKakaoMessageDao(db: DodoDatabase) = db.kakaoMessageDao()
    @Provides fun provideRetryChunkDao(db: DodoDatabase) = db.retryChunkDao()
}
