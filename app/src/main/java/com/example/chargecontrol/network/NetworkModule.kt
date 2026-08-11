package com.example.chargecontrol.network

import com.example.chargecontrol.Config
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {
    val json = Json { ignoreUnknownKeys = true }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(Config.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(Config.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val evccApi: EvccApi by lazy {
        Retrofit.Builder()
            .baseUrl(Config.EVCC_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(EvccApi::class.java)
    }

    val goeApi: GoeApi by lazy {
        Retrofit.Builder()
            .baseUrl(Config.GOE_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GoeApi::class.java)
    }
}
