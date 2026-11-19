package com.larp.launcher.data.repository

import com.larp.launcher.data.remote.api.LarpApi
import com.larp.launcher.data.remote.dto.CreateAnnouncementRequest
import com.larp.launcher.domain.model.Announcement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnouncementsRepository @Inject constructor(
    private val api: LarpApi
) {

    suspend fun list(): List<Announcement> = withContext(Dispatchers.IO) {
        api.listAnnouncements().map { it.toDomain() }.sortedByDescending { it.pinned }
    }

    suspend fun publish(title: String, body: String, pinned: Boolean, color: String? = null, expiresAt: Long? = null): Announcement =
        withContext(Dispatchers.IO) {
            api.createAnnouncement(
                CreateAnnouncementRequest(
                    title = title,
                    body = body,
                    pinned = pinned,
                    color = color,
                    expiresAt = expiresAt
                )
            ).toDomain()
        }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        api.deleteAnnouncement(id).isSuccessful
    }

    private fun com.larp.launcher.data.remote.dto.AnnouncementDto.toDomain(): Announcement = Announcement(
        id = id,
        title = title,
        body = body,
        authorName = authorName,
        pinned = pinned,
        color = color,
        createdAt = createdAt,
        updatedAt = updatedAt,
        expiresAt = expiresAt
    )
}
