package com.blek.blexsy.model

import kotlinx.serialization.Serializable

@Serializable
data class Playlist(
    val id: String = "",
    val name: String = "Playlist",
    val tracks: List<Track> = emptyList(),
    val updatedAt: Long = 0L
)
