package com.rsilverst.gimmeabeat.spotify

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SpotifyUser(
    val id: String,
    val display_name: String?,
    val email: String?,
    val product: String?,
)

@JsonClass(generateAdapter = true)
data class PlayRequest(
    val uris: List<String>? = null,
    val context_uri: String? = null,
    val position_ms: Long? = null,
)

@JsonClass(generateAdapter = true)
data class SpotifyDevicesResponse(
    val devices: List<SpotifyDevice>,
)

@JsonClass(generateAdapter = true)
data class SpotifyDevice(
    val id: String?,
    val name: String,
    val type: String,
    val is_active: Boolean,
    val is_private_session: Boolean = false,
    val is_restricted: Boolean = false,
    val volume_percent: Int? = null,
)

@JsonClass(generateAdapter = true)
data class SpotifySearchResponse(
    val tracks: SpotifyTrackPage?,
)

@JsonClass(generateAdapter = true)
data class SpotifyTrackPage(
    val items: List<SpotifyTrack>,
)

@JsonClass(generateAdapter = true)
data class SpotifyTrack(
    val id: String,
    val name: String,
    val uri: String,
    val artists: List<SpotifyArtistRef>,
    val duration_ms: Long? = null,
)

@JsonClass(generateAdapter = true)
data class SpotifyPlaybackState(
    val device: SpotifyDevice?,
    val is_playing: Boolean,
    val progress_ms: Long,
    val item: SpotifyTrack?,
)

@JsonClass(generateAdapter = true)
data class SpotifyArtistRef(
    val id: String?,
    val name: String,
)
