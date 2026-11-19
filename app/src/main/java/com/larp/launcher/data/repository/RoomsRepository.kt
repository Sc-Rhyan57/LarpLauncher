package com.larp.launcher.data.repository

import com.larp.launcher.data.remote.api.LarpApi
import com.larp.launcher.data.remote.dto.CreateRoomRequest
import com.larp.launcher.data.remote.dto.JoinRoomRequest
import com.larp.launcher.domain.model.IceServer
import com.larp.launcher.domain.model.Room
import com.larp.launcher.domain.model.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomsRepository @Inject constructor(
    private val api: LarpApi
) {

    suspend fun listRooms(): List<Room> = withContext(Dispatchers.IO) {
        api.listRooms().rooms.map { it.toDomain() }
    }

    suspend fun createRoom(
        name: String,
        game: String,
        mods: List<String>,
        maxPlayers: Int,
        locked: Boolean,
        voiceEnabled: Boolean
    ): Room = withContext(Dispatchers.IO) {
        api.createRoom(
            CreateRoomRequest(
                name = name,
                game = game,
                mods = mods,
                maxPlayers = maxPlayers,
                locked = locked,
                voiceEnabled = voiceEnabled
            )
        ).toDomain()
    }

    suspend fun joinRoom(roomId: String, password: String? = null): VoiceConfig = withContext(Dispatchers.IO) {
        val response = api.joinRoom(roomId, JoinRoomRequest(password = password))
        VoiceConfig(
            roomId = response.roomId,
            voiceToken = response.voiceToken,
            iceServers = response.iceServers.map {
                IceServer(it.urls, it.username, it.credential)
            }
        )
    }

    suspend fun leaveRoom(roomId: String): Boolean = withContext(Dispatchers.IO) {
        api.leaveRoom(roomId).isSuccessful
    }

    suspend fun deleteRoom(roomId: String): Boolean = withContext(Dispatchers.IO) {
        api.deleteRoom(roomId).isSuccessful
    }

    private fun com.larp.launcher.data.remote.dto.RoomDto.toDomain(): Room = Room(
        id = id,
        name = name,
        game = game,
        ownerId = ownerId,
        ownerName = ownerName,
        mods = mods,
        maxPlayers = maxPlayers,
        playerCount = playerCount,
        locked = locked,
        createdAt = createdAt,
        voiceEnabled = voiceEnabled,
        joined = false
    )
}
