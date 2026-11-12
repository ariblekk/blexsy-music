package com.blek.blexsy.model

import kotlinx.serialization.Serializable

@Serializable
data class ChartFeed(
    val title: String = "Top 50 Indonesia",
    val tracks: List<Track> = emptyList()
)
