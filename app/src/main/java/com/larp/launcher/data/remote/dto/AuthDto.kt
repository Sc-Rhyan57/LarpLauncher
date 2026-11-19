package com.larp.launcher.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthChallengeDto(
    val challenge: String
)

@Serializable
data class DiscordLoginUrlDto(
    val url: String,
    val state: String
)

@Serializable
data class AuthTokenRequest(
    val grant: String,
    val verifier: String
)

@Serializable
data class AuthTokenResponse(
    val token: String,
    val expiresAt: Long,
    val user: UserDto
)

@Serializable
data class UserDto(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val discordId: String? = null,
    val owner: Boolean = false,
    val admin: Boolean = false,
    val moderator: Boolean = false,
    val guilds: List<GuildDto> = emptyList()
)

@Serializable
data class GuildDto(
    val id: String,
    val name: String,
    val icon: String? = null,
    val owner: Boolean = false
)

@Serializable
data class ApiErrorDto(
    val error: String,
    val message: String? = null,
    @SerialName("error_code") val code: String? = null
)
