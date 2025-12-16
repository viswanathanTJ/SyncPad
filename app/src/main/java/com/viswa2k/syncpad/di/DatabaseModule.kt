package com.viswa2k.syncpad.di

import android.content.Context
import com.viswa2k.syncpad.data.AppDatabase
import com.viswa2k.syncpad.data.dao.BlogDao
import com.viswa2k.syncpad.data.dao.PrefixIndexDao
import com.viswa2k.syncpad.data.dao.SyncMetaDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for database dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideBlogDao(database: AppDatabase): BlogDao {
        return database.blogDao()
    }

    @Provides
    @Singleton
    fun providePrefixIndexDao(database: AppDatabase): PrefixIndexDao {
        return database.prefixIndexDao()
    }

    @Provides
    @Singleton
    fun provideSyncMetaDao(database: AppDatabase): SyncMetaDao {
        return database.syncMetaDao()
    }
}
