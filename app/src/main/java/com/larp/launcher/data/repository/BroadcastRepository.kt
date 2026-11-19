package com.larp.launcher.data.repository

import com.larp.launcher.data.remote.api.LarpApi
import com.larp.launcher.data.remote.dto.BroadcastRequest
import com.larp.launcher.domain.model.BroadcastMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BroadcastRepository @Inject constructor(
    private val api: LarpApi
) {

    suspend fun recent(limit: Int = 20): List<BroadcastMessage> = withContext(Dispatchers.IO) {
        api.recentBroadcasts(limit).map { it.toDomain() }
    }

    suspend fun send(message: String, severity: BroadcastMessage.Severity = BroadcastMessage.Severity.Info): BroadcastMessage =
        withContext(Dispatchers.IO) {
            api.sendBroadcast(BroadcastRequest(message = message, severity = severity.name.lowercase()))
                .toDomain()
        }

    private fun com.larp.launcher.data.remote.dto.BroadcastDto.toDomain(): BroadcastMessage = BroadcastMessage(
        id = id,
        message = message,
        authorName = authorName,
        severity = runCatching { BroadcastMessage.Severity.valueOf(severity.replaceFirstChar { it.uppercase() }) }
            .getOrDefault(BroadcastMessage.Severity.Info),
        createdAt = createdAt
    )
}
