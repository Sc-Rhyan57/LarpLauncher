package com.larp.launcher.core.di

import com.larp.launcher.core.LarpEnvProvider
import com.larp.launcher.data.repository.AnnouncementsRepository
import com.larp.launcher.data.repository.AuthRepository
import com.larp.launcher.data.repository.BroadcastRepository
import com.larp.launcher.data.repository.GameEnvRepository
import com.larp.launcher.data.repository.ModsRepository
import com.larp.launcher.data.repository.RoomsRepository
import com.larp.launcher.data.repository.VoiceRepository
import com.larp.launcher.patcher.GameLauncher
import com.larp.launcher.patcher.HashDoInterop
import com.larp.launcher.patcher.InteropPronto
import com.larp.launcher.patcher.ModRuntime
import com.larp.launcher.patcher.ModSync
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides @Singleton
    fun provideAuthRepository(api: com.larp.launcher.data.remote.api.LarpApi, prefs: com.larp.launcher.data.local.prefs.AuthPrefs): AuthRepository =
        AuthRepository(api, prefs)

    @Provides @Singleton
    fun provideModsRepository(
        api: com.larp.launcher.data.remote.api.LarpApi,
        cache: com.larp.launcher.data.local.cache.ModCacheManager,
        installedDao: com.larp.launcher.data.local.db.InstalledModDao,
        localDao: com.larp.launcher.data.local.db.LocalModDao,
        httpClient: okhttp3.OkHttpClient
    ): ModsRepository = ModsRepository(api, cache, installedDao, localDao, httpClient)

    @Provides @Singleton
    fun provideRoomsRepository(api: com.larp.launcher.data.remote.api.LarpApi): RoomsRepository = RoomsRepository(api)

    @Provides @Singleton
    fun provideAnnouncementsRepository(api: com.larp.launcher.data.remote.api.LarpApi): AnnouncementsRepository = AnnouncementsRepository(api)

    @Provides @Singleton
    fun provideBroadcastRepository(api: com.larp.launcher.data.remote.api.LarpApi): BroadcastRepository = BroadcastRepository(api)

    @Provides @Singleton
    fun provideVoiceRepository(api: com.larp.launcher.data.remote.api.LarpApi): VoiceRepository = VoiceRepository(api)

    @Provides @Singleton
    fun provideGameEnvRepository(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        envProvider: LarpEnvProvider
    ): GameEnvRepository = GameEnvRepository(context, envProvider)

    @Provides @Singleton
    fun provideHashDoInterop(): HashDoInterop = HashDoInterop()

    @Provides @Singleton
    fun provideInteropPronto(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        api: com.larp.launcher.data.remote.api.LarpApi,
        cache: com.larp.launcher.data.local.cache.ModCacheManager,
        httpClient: okhttp3.OkHttpClient,
        hashDoInterop: HashDoInterop,
        envProvider: LarpEnvProvider
    ): InteropPronto = InteropPronto(context, api, cache, httpClient, hashDoInterop, envProvider)

    @Provides @Singleton
    fun provideModSync(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        api: com.larp.launcher.data.remote.api.LarpApi,
        cache: com.larp.launcher.data.local.cache.ModCacheManager,
        httpClient: okhttp3.OkHttpClient,
        interop: InteropPronto
    ): ModSync = ModSync(context, api, cache, httpClient, interop)

    @Provides @Singleton
    fun provideModRuntime(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        cache: com.larp.launcher.data.local.cache.ModCacheManager,
        envProvider: LarpEnvProvider
    ): ModRuntime = ModRuntime(context, cache, envProvider)

    @Provides @Singleton
    fun provideGameLauncher(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        gameEnvRepo: GameEnvRepository,
        modRuntime: ModRuntime,
        modSync: ModSync,
        cache: com.larp.launcher.data.local.cache.ModCacheManager
    ): GameLauncher = GameLauncher(context, gameEnvRepo, modRuntime, modSync, cache)

    @Provides @Singleton
    fun provideFridaInjector(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        cache: com.larp.launcher.data.local.cache.ModCacheManager
    ): com.larp.launcher.patcher.frida.FridaInjector =
        com.larp.launcher.patcher.frida.FridaInjector(context, cache)
}
