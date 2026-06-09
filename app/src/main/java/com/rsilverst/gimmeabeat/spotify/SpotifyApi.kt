package com.rsilverst.gimmeabeat.spotify

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT

interface SpotifyApi {

    @GET("v1/me")
    suspend fun me(@Header("Authorization") auth: String): SpotifyUser

    @GET("v1/me/player/devices")
    suspend fun devices(@Header("Authorization") auth: String): SpotifyDevicesResponse

    @PUT("v1/me/player/play")
    suspend fun play(
        @Header("Authorization") auth: String,
        @Body body: PlayRequest,
        @retrofit2.http.Query("device_id") deviceId: String? = null,
    ): Response<Unit>

    @GET("v1/search")
    suspend fun search(
        @Header("Authorization") auth: String,
        @retrofit2.http.Query("q") query: String,
        @retrofit2.http.Query("type") type: String = "track",
        @retrofit2.http.Query("limit") limit: Int = 5,
    ): SpotifySearchResponse

    @GET("v1/me/player")
    suspend fun playbackState(
        @Header("Authorization") auth: String,
    ): Response<SpotifyPlaybackState>
}
