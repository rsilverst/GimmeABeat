package com.rsilverst.gimmeabeat

import android.util.Log
import com.rsilverst.gimmeabeat.bpm.BpmCandidate
import com.rsilverst.gimmeabeat.bpm.GetSongBpmClient
import com.rsilverst.gimmeabeat.spotify.PlayResult
import com.rsilverst.gimmeabeat.spotify.SpotifyClient
import com.rsilverst.gimmeabeat.spotify.SpotifyTrack

private const val TAG = "SongFinder"

class SongFinder(
    private val bpmClient: GetSongBpmClient,
    private val spotify: SpotifyClient,
) {

    /**
     * Looks up candidates at the target BPM, picks the first that resolves to a
     * Spotify track not in [excludeTrackIds], plays it.
     */
    suspend fun findAndPlay(
        targetBpm: Int,
        genre: String?,
        excludeTrackIds: Set<String> = emptySet(),
        tolerance: Int = 5,
    ): FindAndPlayResult {
        val candidates = bpmClient.findCandidates(
            targetBpm = targetBpm,
            genres = listOfNotNull(genre),
            tolerance = tolerance,
        )
        Log.d(TAG, "got ${candidates.size} BPM candidates for $targetBpm BPM, genre=$genre")
        if (candidates.isEmpty()) return FindAndPlayResult.NoBpmCandidates

        candidates.shuffled().forEach { candidate ->
            val track = try {
                spotify.searchTrack(candidate.title, candidate.artist)
            } catch (t: Throwable) {
                Log.w(TAG, "spotify search failed for ${candidate.title}", t)
                null
            }
            if (track != null && track.id !in excludeTrackIds) {
                val result = spotify.playTrack(track.uri)
                return FindAndPlayResult.Resolved(candidate, track, result)
            }
        }
        return FindAndPlayResult.NoSpotifyMatch
    }
}

sealed interface FindAndPlayResult {
    data object NoBpmCandidates : FindAndPlayResult
    data object NoSpotifyMatch : FindAndPlayResult
    data class Resolved(
        val candidate: BpmCandidate,
        val track: SpotifyTrack,
        val playResult: PlayResult,
    ) : FindAndPlayResult
}
