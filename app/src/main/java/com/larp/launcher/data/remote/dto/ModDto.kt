package com.larp.launcher.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class ModDto(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val size: Long,
    val checksum: String,
    val coverUrl: String? = null,
    val tags: List<String> = emptyList(),
    val isOfficial: Boolean = false,
    val updatedAt: Long
)

@Serializable
data class ModTicketDto(
    val id: String,
    val name: String,
    val downloadUrl: String,
    val key: String,
    val checksum: String,
    val size: Long
)

@Serializable
data class ModInstallRequest(
    val mods: List<String>
)

@Serializable
data class ModInstallResponse(
    val enabled: List<ModDto>,
    val disabled: List<String>
)

@Serializable
data class ModListResponse(
    val mods: List<ModDto>,
    val total: Int,
    val cursor: String? = null
)
