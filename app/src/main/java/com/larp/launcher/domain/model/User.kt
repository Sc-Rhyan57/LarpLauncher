package com.larp.launcher.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class User(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val discordId: String? = null,
    val isOwner: Boolean = false,
    val isAdmin: Boolean = false,
    val isModerator: Boolean = false,
    val guilds: List<Guild> = emptyList()
) : Parcelable

@Parcelize
data class Guild(
    val id: String,
    val name: String,
    val icon: String? = null,
    val isOwner: Boolean = false
) : Parcelable
