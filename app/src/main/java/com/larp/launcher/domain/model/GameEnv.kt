package com.larp.launcher.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class GameEnv(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val nativeLibraryDir: String,
    val sourceDir: String,
    val isInstalled: Boolean,
    val isExternal: Boolean = false
) : Parcelable

@Parcelize
data class PatchProgress(
    val stage: Stage,
    val message: String,
    val progress: Float = 0f,
    val error: Throwable? = null
) : Parcelable {
    enum class Stage { Idle, Downloading, Decrypting, Installing, Preparing, Ready, Failed }
}
