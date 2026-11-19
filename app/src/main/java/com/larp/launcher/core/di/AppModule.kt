package com.larp.launcher.core.di

import android.content.Context
import com.larp.launcher.core.LarpEnvProvider
import com.larp.launcher.data.local.cache.ModCacheManager
import com.larp.launcher.data.local.db.LarpDatabase
import com.larp.launcher.data.local.prefs.AuthPrefs
import com.larp.launcher.data.local.prefs.SettingsPrefs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAuthPrefs(@ApplicationContext context: Context): AuthPrefs = AuthPrefs(context)

    @Provides
    @Singleton
    fun provideSettingsPrefs(@ApplicationContext context: Context): SettingsPrefs = SettingsPrefs(context)

    @Provides
    @Singleton
    fun provideModCacheManager(@ApplicationContext context: Context): ModCacheManager = ModCacheManager(context)

    @Provides
    @Singleton
    fun provideLarpDatabase(@ApplicationContext context: Context): LarpDatabase =
        androidx.room.Room.databaseBuilder(context, LarpDatabase::class.java, "larp.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideLocalModDao(db: LarpDatabase) = db.localModDao()

    @Provides
    @Singleton
    fun provideInstalledModDao(db: LarpDatabase) = db.installedModDao()

    @Provides
    @Singleton
    fun provideLarpEnvProvider(
        @ApplicationContext context: Context,
        json: kotlinx.serialization.json.Json
    ): LarpEnvProvider = LarpEnvProvider.get(context, json)
}
