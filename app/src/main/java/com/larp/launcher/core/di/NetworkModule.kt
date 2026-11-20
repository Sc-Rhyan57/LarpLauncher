package com.larp.launcher.core.di

import com.larp.launcher.core.LarpEnvProvider
import com.larp.launcher.data.local.prefs.AuthPrefs
import com.larp.launcher.data.remote.api.LarpApi
import com.larp.launcher.data.remote.websocket.LarpRealtimeClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
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
    fun provideBaseUrlInterceptor(envProvider: LarpEnvProvider): BaseUrlInterceptor =
        BaseUrlInterceptor(envProvider)

    @Provides
    @Singleton
    fun provideAuthInterceptor(authPrefs: AuthPrefs): AuthInterceptor = AuthInterceptor(authPrefs)

    @Provides
    @Singleton
    fun provideOkHttp(
        baseUrlInterceptor: BaseUrlInterceptor,
        authInterceptor: AuthInterceptor
    ): OkHttpClient {
        val logger = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(authInterceptor)
            .addInterceptor(baseUrlInterceptor)
            .addInterceptor(logger)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideLarpApi(
        client: OkHttpClient,
        json: Json
    ): LarpApi {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl("https://larp-relaxxx.placeholder.workers.dev/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(LarpApi::class.java)
    }

    @Provides
    @Singleton
    fun provideRealtimeClient(
        client: OkHttpClient,
        json: Json,
        envProvider: LarpEnvProvider
    ): LarpRealtimeClient = LarpRealtimeClient(client, json, envProvider)
}

class BaseUrlInterceptor(private val envProvider: LarpEnvProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val env = envProvider.current()
        val newBaseUrl = env.apiUrl.trimEnd('/').toHttpUrlOrNull() ?: return chain.proceed(original)
        if (original.url.host == "larp-relaxxx.placeholder.workers.dev") {
            val newUrl = original.url.newBuilder()
                .scheme(newBaseUrl.scheme)
                .host(newBaseUrl.host)
                .port(newBaseUrl.port)
                .build()
            return chain.proceed(original.newBuilder().url(newUrl).build())
        }
        return chain.proceed(original)
    }
}

class AuthInterceptor(private val authPrefs: AuthPrefs) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val skipPaths = setOf("auth/login", "auth/discord", "auth/token")
        if (skipPaths.any { original.url.encodedPath.contains(it) }) {
            return chain.proceed(original)
        }
        val token = authPrefs.rawToken()
        val response = if (token != null) {
            chain.proceed(original.newBuilder().addHeader("Authorization", "Bearer $token").build())
        } else {
            chain.proceed(original)
        }
        if (response.code == 401) {
            authPrefs.clear()
        }
        return response
    }
}
