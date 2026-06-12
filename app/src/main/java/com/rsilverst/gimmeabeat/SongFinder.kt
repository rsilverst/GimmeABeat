package com.rsilverst.gimmeabeat

import android.util.Log
import com.rsilverst.gimmeabeat.bpm.BpmCandidate
import com.rsilverst.gimmeabeat.bpm.GetSongBpmClient
import com.rsilverst.gimmeabeat.spotify.SpotifyClient
import com.rsilverst.gimmeabeat.spotify.SpotifyTrack
import com.rsilverst.gimmeabeat.telemetry.Telemetry

private const val TAG = "SongFinder"

class SongFinder(
    private val bpmClient: GetSongBpmClient,
    private val spotify: SpotifyClient,
) {

    /**
     * Looks up candidates at the target BPM and returns the first that resolves
     * to a Spotify track not in [excludeTrackIds]. Pure lookup — the caller is
     * responsible for actually starting playback (either via local IPC through
     * [com.rsilverst.gimmeabeat.spotify.LocalSpotifyController] or the Web API).
     */
    suspend fun findCandidate(
        targetBpm: Int,
        genre: String?,
        excludeTrackIds: Set<String> = emptySet(),
        tolerance: Int = 5,
    ): FindResult {
        val candidates = bpmClient.findCandidates(
            targetBpm = targetBpm,
            genres = listOfNotNull(genre),
            tolerance = tolerance,
        )

        // Resolve to the first candidate that maps to a fresh Spotify track. The
        // search short-circuits on first hit, so we can't cheaply report a total
        // match count — `candidates` size plus the outcome is enough to surface
        // BPM/genre dead zones (0 candidates, or candidates but no Spotify match).
        val result: FindResult = if (candidates.isEmpty()) {
            FindResult.NoBpmCandidates
        } else {
            candidates.shuffled().firstNotNullOfOrNull { candidate ->
                val track = try {
                    spotify.searchTrack(candidate.title, candidate.artist)
                } catch (t: Throwable) {
                    Log.w(TAG, "spotify search failed for ${candidate.title}", t)
                    null
                }
                if (track != null && track.id !in excludeTrackIds) {
                    FindResult.Found(candidate, track)
                } else {
                    null
                }
            } ?: FindResult.NoSpotifyMatch
        }

        Telemetry.log(
            "song_finder",
            mapOf(
                "targetBpm" to targetBpm,
                "genre" to (genre ?: "any"),
                "tolerance" to tolerance,
                "candidates" to candidates.size,
                "outcome" to result.telemetryName,
                "trackId" to (result as? FindResult.Found)?.track?.id,
            ),
        )
        return result
    }
}

sealed interface FindResult {
    data object NoBpmCandidates : FindResult
    data object NoSpotifyMatch : FindResult
    data class Found(val candidate: BpmCandidate, val track: SpotifyTrack) : FindResult

    /** Stable, low-cardinality label for telemetry/aggregation. */
    val telemetryName: String
        get() = when (this) {
            NoBpmCandidates -> "no_bpm_candidates"
            NoSpotifyMatch -> "no_spotify_match"
            is Found -> "found"
        }
}
