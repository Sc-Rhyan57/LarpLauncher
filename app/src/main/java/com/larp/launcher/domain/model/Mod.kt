package com.larp.launcher.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ServerMod(
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
    val updatedAt: Long,
    val installed: Boolean = false
) : Parcelable

@Parcelize
data class LocalMod(
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
    val checksum: String,
    val enabled: Boolean = false
) : Parcelable

@Parcelize
data class ModTicket(
    val id: String,
    val name: String,
    val version: String = "",
    val downloadUrl: String,
    val key: String,
    val checksum: String,
    val size: Long
) : Parcelable
