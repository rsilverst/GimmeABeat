package com.rsilverst.gimmeabeat.bpm

import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * GetSongBPM API surface. Base URL: https://api.getsong.co/
 * Docs: https://getsongbpm.com/api  (attribution link required in the UI)
 *
 * The `tempo` endpoint returns songs near a given BPM. The actual response
 * shape is loose — we deserialize defensively into [BpmCandidate].
 */
interface GetSongBpmApi {

    @GET("tempo/")
    suspend fun tempo(
        @Query("api_key") apiKey: String,
        @Query("bpm") bpm: Int,
        @Query("limit") limit: Int = 25,
    ): TempoResponse
}

@JsonClass(generateAdapter = true)
data class TempoResponse(
    /** Could be `tempo` list, `search` list, or wrapped in `result`. Defensive parse. */
    val tempo: List<TempoSong>? = null,
    val search: List<TempoSong>? = null,
    val error: String? = null,
)

@JsonClass(generateAdapter = true)
data class TempoSong(
    val id: String? = null,
    val title: String? = null,
    val tempo: String? = null,
    val artist: TempoArtist? = null,
)

@JsonClass(generateAdapter = true)
data class TempoArtist(
    val id: String? = null,
    val name: String? = null,
    val genres: List<String>? = null,
)

/** Normalized candidate the rest of the app uses. */
data class BpmCandidate(
    val title: String,
    val artist: String,
    val bpm: Int,
    val genres: List<String>,
)
