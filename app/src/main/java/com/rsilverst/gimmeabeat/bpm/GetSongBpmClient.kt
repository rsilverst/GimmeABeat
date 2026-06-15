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

// Hard upper bound on cached per-BPM results. In practice keys are integer BPMs
// the caller already coerces to ~[35,225] (≈190 possible keys), so this rarely
// evicts — it's a safety net against ever caching an un-coerced value, not a
// response to real growth.
private const val MAX_CACHE_ENTRIES = 256

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
     * Cache key: single BPM value (genre filtering is applied client-side after
     * lookup, and tolerance only widens the *range* of BPMs queried — each BPM
     * is fetched and cached independently — so neither belongs in the key).
     * Bounded, access-ordered LRU; access is serialized via [synchronizedMap].
     */
    private val perBpmCache: MutableMap<Int, List<BpmCandidate>> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<Int, List<BpmCandidate>>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<Int, List<BpmCandidate>>,
                ): Boolean = size > MAX_CACHE_ENTRIES
            }
        )

    /**
     * Returns candidates near [targetBpm], optionally filtered to artists whose
     * genres include any of [genres] (case-insensitive substring match).
     * Per-BPM results are cached in-memory for the lifetime of this client.
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

        val candidates = (targetBpm - tolerance..targetBpm + tolerance).flatMap { bpm ->
            perBpmCache.getOrPut(bpm) {
                runCatching { api.tempo(apiKey, bpm) }
                    .onFailure { Log.w(TAG, "tempo($bpm) failed", it) }
                    .getOrNull()
                    ?.let { (it.tempo ?: it.search).orEmpty() }
                    ?.mapNotNull { it.toCandidate() }
                    .orEmpty()
            }
        }

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
