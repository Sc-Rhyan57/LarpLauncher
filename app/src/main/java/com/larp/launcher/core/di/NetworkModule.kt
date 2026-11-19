package com.larp.launcher.core.di

import com.larp.launcher.BuildConfig
import com.larp.launcher.data.remote.api.LarpApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient {
        val logger = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(logger)
            .build()
    }

    @Provides
    @Singleton
    @Named("apiUrl")
    fun provideApiUrl(): String = BuildConfig.API_BASE_URL.trimEnd('/')

    @Provides
    @Singleton
    fun provideLarpApi(
        client: OkHttpClient,
        json: Json,
        @Named("apiUrl") baseUrl: String
    ): LarpApi {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(LarpApi::class.java)
    }
}
