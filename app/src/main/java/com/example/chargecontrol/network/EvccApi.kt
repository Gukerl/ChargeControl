package com.example.chargecontrol.network

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable
data class LoadpointDto(
    val mode: String,
    val phasesConfigured: Int,
    val offeredCurrent: Double,
    val connected: Boolean,
    val charging: Boolean,
    val minCurrent: Double,
    val vehicleSoc: Double
)

@Serializable
data class EvccStateResponse(
    val loadpoints: List<LoadpointDto> = emptyList()
)

interface EvccApi {
    @GET("state")
    suspend fun getState(): EvccStateResponse

    @POST("loadpoints/{id}/mode/{mode}")
    suspend fun setMode(@Path("id") id: Int, @Path("mode") mode: String): Response<ResponseBody>

    @POST("loadpoints/{id}/phases/{phases}")
    suspend fun setPhases(@Path("id") id: Int, @Path("phases") phases: String): Response<ResponseBody>

    @POST("loadpoints/{id}/mincurrent/{current}")
    suspend fun setMinCurrent(@Path("id") id: Int, @Path("current") current: Int): Response<ResponseBody>
}
