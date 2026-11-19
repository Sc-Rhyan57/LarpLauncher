package com.larp.launcher.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RoomDto(
    val id: String,
    val name: String,
    val game: String,
    val ownerId: String,
    val ownerName: String,
    val mods: List<String> = emptyList(),
    val maxPlayers: Int = 16,
    val playerCount: Int = 0,
    val locked: Boolean = false,
    val createdAt: Long,
    @SerialName("voice_enabled") val voiceEnabled: Boolean = false
)

@Serializable
data class CreateRoomRequest(
    val name: String,
    val game: String,
    val mods: List<String> = emptyList(),
    val maxPlayers: Int = 16,
    val locked: Boolean = false,
    @SerialName("voice_enabled") val voiceEnabled: Boolean = false
)

@Serializable
data class RoomListResponse(
    val rooms: List<RoomDto>,
    val total: Int
)

@Serializable
data class JoinRoomRequest(
    val password: String? = null
)

@Serializable
data class JoinRoomResponse(
    val roomId: String,
    val voiceToken: String? = null,
    val iceServers: List<IceServerDto> = emptyList()
)

@Serializable
data class IceServerDto(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

@Serializable
data class RoomParticipantDto(
    val userId: String,
    val name: String,
    val avatar: String? = null,
    val muted: Boolean = true,
    val speaking: Boolean = false,
    val isAdmin: Boolean = false
)

@Serializable
data class RoomEventDto(
    val type: String,
    val roomId: String,
    val userId: String? = null,
    val user: UserDto? = null,
    val participant: RoomParticipantDto? = null,
    val mods: List<String>? = null,
    val timestamp: Long
)
