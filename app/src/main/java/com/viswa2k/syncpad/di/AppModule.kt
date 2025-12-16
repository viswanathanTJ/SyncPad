package com.viswa2k.syncpad.di

import android.content.Context
import com.viswa2k.syncpad.data.dao.BlogDao
import com.viswa2k.syncpad.data.dao.PrefixIndexDao
import com.viswa2k.syncpad.data.index.PrefixIndexBuilder
import com.viswa2k.syncpad.repository.BlogRepository
import com.viswa2k.syncpad.repository.PrefixIndexRepository
import com.viswa2k.syncpad.repository.SyncRepository
import com.viswa2k.syncpad.sync.SupabaseApi
import com.viswa2k.syncpad.sync.SyncManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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
    fun provideSupabaseApi(): SupabaseApi {
        return SupabaseApi()
    }

    @Provides
    @Singleton
    fun provideSyncManager(
        @ApplicationContext context: Context,
        blogRepository: BlogRepository,
        syncRepository: SyncRepository,
        prefixIndexRepository: PrefixIndexRepository,
        supabaseApi: SupabaseApi
    ): SyncManager {
        return SyncManager(context, blogRepository, syncRepository, prefixIndexRepository, supabaseApi)
    }
}
