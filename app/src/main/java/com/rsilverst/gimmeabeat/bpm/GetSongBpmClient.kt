package com.rsilverst.gimmeabeat.bpm

import android.util.Log
import com.rsilverst.gimmeabeat.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

private const val TAG = "GetSongBpmClient"

class GetSongBpmClient {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    private val api: GetSongBpmApi = Retrofit.Builder()
        .baseUrl("https://api.getsong.co/")
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GetSongBpmApi::class.java)

    /**
     * Returns candidates near [targetBpm], optionally filtered to artists whose
     * genres include any of [genres] (case-insensitive substring match).
     */
    suspend fun findCandidates(
        targetBpm: Int,
        genres: List<String> = emptyList(),
        tolerance: Int = 5,
    ): List<BpmCandidate> {
        val apiKey = BuildConfig.GET_SONG_BPM_API_KEY
        if (apiKey.isBlank()) {
            Log.w(TAG, "missing GET_SONG_BPM_API_KEY")
            return emptyList()
        }

        val responses = (targetBpm - tolerance..targetBpm + tolerance).mapNotNull { bpm ->
            runCatching { api.tempo(apiKey, bpm) }
                .onFailure { Log.w(TAG, "tempo($bpm) failed", it) }
                .getOrNull()
        }

        val raw = responses.flatMap { (it.tempo ?: it.search).orEmpty() }
        val candidates = raw.mapNotNull { it.toCandidate() }
        return if (genres.isEmpty()) candidates else candidates.filter { c ->
            c.genres.any { g -> genres.any { wanted -> g.contains(wanted, ignoreCase = true) } }
        }
    }

    private fun TempoSong.toCandidate(): BpmCandidate? {
        val title = title?.trim().orEmpty()
        val artistName = artist?.name?.trim().orEmpty()
        val bpm = tempo?.toIntOrNull()
        if (title.isEmpty() || artistName.isEmpty() || bpm == null) return null
        return BpmCandidate(
            title = title,
            artist = artistName,
            bpm = bpm,
            genres = artist?.genres.orEmpty(),
        )
    }
}
