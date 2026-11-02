package com.blek.blexsy.model

import kotlinx.serialization.Serializable

@Serializable
data class AuthUser(
    val id: Int = 0,
    val name: String = "",
    val email: String = ""
)
