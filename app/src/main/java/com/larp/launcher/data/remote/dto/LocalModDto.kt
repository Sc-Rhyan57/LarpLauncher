package com.larp.launcher.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class LocalModMetadata(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val dllPath: String,
    val coverPath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val fileSize: Long,
    val checksum: String
)

@Serializable
data class GameEnvDto(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val nativeLibraryDir: String,
    val sourceDir: String,
    val isInstalled: Boolean
)
