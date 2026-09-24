package com.example.chargecontrol.network

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** `trx` is `null` when the wallbox has no active authorization, otherwise the
 *  trx/RFID value it was last authorized with (0 = generic, 1-10 = that RFID account). */
@Serializable
data class GoeStatusDto(val trx: Int? = null)

interface GoeApi {
    @GET("api/set")
    suspend fun authorize(@Query("trx") trx: Int = 1): Response<ResponseBody>

    @GET("api/status")
    suspend fun getStatus(@Query("filter") filter: String = "trx"): GoeStatusDto
}
