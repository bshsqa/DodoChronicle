package com.bshsqa.dodochronicle.di

import com.bshsqa.dodochronicle.data.repository.ChildRepositoryImpl
import com.bshsqa.dodochronicle.data.repository.EventRepositoryImpl
import com.bshsqa.dodochronicle.data.repository.KakaoRepositoryImpl
import com.bshsqa.dodochronicle.data.repository.RetryChunkRepositoryImpl
import com.bshsqa.dodochronicle.domain.repository.ChildRepository
import com.bshsqa.dodochronicle.domain.repository.EventRepository
import com.bshsqa.dodochronicle.domain.repository.KakaoRepository
import com.bshsqa.dodochronicle.domain.repository.RetryChunkRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton abstract fun bindChildRepository(impl: ChildRepositoryImpl): ChildRepository
    @Binds @Singleton abstract fun bindEventRepository(impl: EventRepositoryImpl): EventRepository
    @Binds @Singleton abstract fun bindKakaoRepository(impl: KakaoRepositoryImpl): KakaoRepository
    @Binds @Singleton abstract fun bindRetryChunkRepository(impl: RetryChunkRepositoryImpl): RetryChunkRepository
}
