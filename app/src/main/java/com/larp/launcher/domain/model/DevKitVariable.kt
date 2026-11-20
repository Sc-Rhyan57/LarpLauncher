package com.larp.launcher.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class DevKitVariable(
    val id: String,
    val name: String,
    val address: String,
    val module: String = "libil2cpp.so",
    val size: Int = 8,
    val format: String = "hex",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class DevKitSnapshot(
    val id: String,
    val timestamp: Long,
    val values: List<DevKitSnapshotValue>
)

@Serializable
data class DevKitSnapshotValue(
    val variableId: String,
    val name: String,
    val address: String,
    val module: String,
    val rawHex: String,
    val decoded: String,
    val absoluteAddress: String,
    val error: String? = null
)
