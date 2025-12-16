package com.example.largeindexblog.di

import com.example.largeindexblog.data.dao.BlogDao
import com.example.largeindexblog.data.dao.PrefixIndexDao
import com.example.largeindexblog.data.index.PrefixIndexBuilder
import com.example.largeindexblog.repository.BlogRepository
import com.example.largeindexblog.repository.PrefixIndexRepository
import com.example.largeindexblog.repository.SyncRepository
import com.example.largeindexblog.sync.SyncManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for app-level dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePrefixIndexBuilder(
        blogDao: BlogDao,
        prefixIndexDao: PrefixIndexDao
    ): PrefixIndexBuilder {
        return PrefixIndexBuilder(blogDao, prefixIndexDao)
    }

    @Provides
    @Singleton
    fun provideSyncManager(
        blogRepository: BlogRepository,
        syncRepository: SyncRepository,
        prefixIndexRepository: PrefixIndexRepository
    ): SyncManager {
        return SyncManager(blogRepository, syncRepository, prefixIndexRepository)
    }
}
