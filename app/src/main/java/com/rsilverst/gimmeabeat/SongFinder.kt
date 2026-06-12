package com.rsilverst.gimmeabeat

import android.util.Log
import com.rsilverst.gimmeabeat.bpm.BpmCandidate
import com.rsilverst.gimmeabeat.bpm.GetSongBpmClient
import com.rsilverst.gimmeabeat.spotify.SpotifyClient
import com.rsilverst.gimmeabeat.spotify.SpotifyTrack

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
        Log.d(TAG, "got ${candidates.size} BPM candidates for $targetBpm BPM, genre=$genre")
        if (candidates.isEmpty()) return FindResult.NoBpmCandidates

        candidates.shuffled().forEach { candidate ->
            val track = try {
                spotify.searchTrack(candidate.title, candidate.artist)
            } catch (t: Throwable) {
                Log.w(TAG, "spotify search failed for ${candidate.title}", t)
                null
            }
            if (track != null && track.id !in excludeTrackIds) {
                return FindResult.Found(candidate, track)
            }
        }
        return FindResult.NoSpotifyMatch
    }
}

sealed interface FindResult {
    data object NoBpmCandidates : FindResult
    data object NoSpotifyMatch : FindResult
    data class Found(val candidate: BpmCandidate, val track: SpotifyTrack) : FindResult
}
