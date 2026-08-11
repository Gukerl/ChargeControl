package com.example.chargecontrol.network

import com.example.chargecontrol.Config
import com.example.chargecontrol.SettingsRepository
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

private const val PLACEHOLDER_EVCC_BASE_URL = "http://chargecontrol.invalid:7070/api/"
private const val PLACEHOLDER_GOE_BASE_URL = "http://chargecontrol.invalid/"

internal class DynamicHostInterceptor(
    private val hostProvider: () -> String,
    private val portProvider: () -> Int
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val newUrl = original.url.newBuilder()
            .host(hostProvider())
            .port(portProvider())
            .build()
        return chain.proceed(original.newBuilder().url(newUrl).build())
    }
}

object NetworkModule {
    val json = Json { ignoreUnknownKeys = true }

    private fun baseClientBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .connectTimeout(Config.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(Config.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private val evccOkHttpClient = baseClientBuilder()
        .addInterceptor(
            DynamicHostInterceptor(
                hostProvider = { SettingsRepository.evccHost },
                portProvider = { SettingsRepository.evccPort }
            )
        )
        .build()

    private val goeOkHttpClient = baseClientBuilder()
        .addInterceptor(
            DynamicHostInterceptor(
                hostProvider = { SettingsRepository.goeHost },
                portProvider = { 80 }
            )
        )
        .build()

    val evccApi: EvccApi by lazy {
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_EVCC_BASE_URL)
            .client(evccOkHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(EvccApi::class.java)
    }

    val goeApi: GoeApi by lazy {
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_GOE_BASE_URL)
            .client(goeOkHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GoeApi::class.java)
    }
}
