package com.example.chargecontrol.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface GoeApi {
    @GET("api/set")
    suspend fun release(@Query("frc") frc: Int): Response<ResponseBody>
}
