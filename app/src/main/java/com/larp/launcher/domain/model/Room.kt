package com.larp.launcher.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Room(
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
    val voiceEnabled: Boolean = false,
    val joined: Boolean = false
) : Parcelable

@Parcelize
data class RoomParticipant(
    val userId: String,
    val name: String,
    val avatar: String? = null,
    val muted: Boolean = true,
    val speaking: Boolean = false,
    val isAdmin: Boolean = false
) : Parcelable

@Parcelize
data class VoiceConfig(
    val roomId: String,
    val voiceToken: String? = null,
    val iceServers: List<IceServer> = emptyList()
) : Parcelable

@Parcelize
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
) : Parcelable

@Parcelize
data class Announcement(
    val id: String,
    val title: String,
    val body: String,
    val authorName: String,
    val pinned: Boolean = false,
    val color: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long? = null
) : Parcelable

@Parcelize
data class BroadcastMessage(
    val id: String,
    val message: String,
    val authorName: String,
    val severity: Severity = Severity.Info,
    val createdAt: Long
) : Parcelable {
    enum class Severity { Info, Warn, Critical }
}
