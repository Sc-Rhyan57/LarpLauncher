package com.larp.launcher.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class AnnouncementDto(
    val id: String,
    val title: String,
    val body: String,
    val authorName: String,
    val pinned: Boolean = false,
    val color: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long? = null
)

@Serializable
data class CreateAnnouncementRequest(
    val title: String,
    val body: String,
    val pinned: Boolean = false,
    val color: String? = null,
    val expiresAt: Long? = null
)

@Serializable
data class BroadcastDto(
    val id: String,
    val message: String,
    val authorName: String,
    val severity: String = "info",
    val createdAt: Long
)

@Serializable
data class BroadcastRequest(
    val message: String,
    val severity: String = "info"
)

@Serializable
data class VoiceOfferDto(
    val sdp: String,
    val type: String,
    val roomId: String
)

@Serializable
data class VoiceAnswerDto(
    val sdp: String,
    val type: String,
    val roomId: String
)

@Serializable
data class VoiceIceCandidateDto(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int?,
    val roomId: String,
    val userId: String
)
