package com.blek.blexsy.model

import kotlinx.serialization.Serializable

@Serializable
data class FeaturedItem(
    val id: String = "",
    val kicker: String = "FEATURED",
    val title: String = "",
    val subtitle: String = "",
    val imageUrl: String? = null,
    val type: String = "track", // "track", "album", "playlist", "url"
    val target: String = "",    // ID atau web URL
    val active: Boolean = true,
    val layout: String = "track" // "banner", "track"
)
