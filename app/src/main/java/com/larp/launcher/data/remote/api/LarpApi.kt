package com.larp.launcher.data.remote.api

import com.larp.launcher.data.remote.dto.AnnouncementDto
import com.larp.launcher.data.remote.dto.AuthChallengeDto
import com.larp.launcher.data.remote.dto.AuthTokenRequest
import com.larp.launcher.data.remote.dto.AuthTokenResponse
import com.larp.launcher.data.remote.dto.BroadcastDto
import com.larp.launcher.data.remote.dto.BroadcastRequest
import com.larp.launcher.data.remote.dto.CreateAnnouncementRequest
import com.larp.launcher.data.remote.dto.CreateRoomRequest
import com.larp.launcher.data.remote.dto.DiscordLoginUrlDto
import com.larp.launcher.data.remote.dto.IceServerDto
import com.larp.launcher.data.remote.dto.JoinRoomRequest
import com.larp.launcher.data.remote.dto.JoinRoomResponse
import com.larp.launcher.data.remote.dto.ModDto
import com.larp.launcher.data.remote.dto.ModInstallRequest
import com.larp.launcher.data.remote.dto.ModInstallResponse
import com.larp.launcher.data.remote.dto.ModListResponse
import com.larp.launcher.data.remote.dto.ModTicketDto
import com.larp.launcher.data.remote.dto.RoomDto
import com.larp.launcher.data.remote.dto.RoomListResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface LarpApi {

    @GET("auth/login")
    suspend fun beginLogin(@Query("challenge") challenge: String): AuthChallengeDto

    @GET("auth/discord")
    suspend fun discordLoginUrl(@Query("state") state: String): DiscordLoginUrlDto

    @POST("auth/token")
    suspend fun finishLogin(@Body body: AuthTokenRequest): AuthTokenResponse

    @POST("auth/refresh")
    suspend fun refresh(
        @Header("Authorization") token: String
    ): AuthTokenResponse

    @GET("auth/me")
    suspend fun getMe(@Header("Authorization") token: String): Response<com.larp.launcher.data.remote.dto.UserDto>

    @GET("mods")
    suspend fun listMods(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50
    ): ModListResponse

    @GET("mods/{id}")
    suspend fun getMod(@Path("id") id: String): ModDto

    @POST("mods/{id}/ticket")
    suspend fun getModTicket(@Path("id") id: String): ModTicketDto

    @POST("mods/install")
    suspend fun installMods(@Body body: ModInstallRequest): ModInstallResponse

    @GET("rooms")
    suspend fun listRooms(): RoomListResponse

    @POST("rooms")
    suspend fun createRoom(@Body body: CreateRoomRequest): RoomDto

    @GET("rooms/{id}")
    suspend fun getRoom(@Path("id") id: String): RoomDto

    @POST("rooms/{id}/join")
    suspend fun joinRoom(
        @Path("id") id: String,
        @Body body: JoinRoomRequest = JoinRoomRequest()
    ): JoinRoomResponse

    @POST("rooms/{id}/leave")
    suspend fun leaveRoom(@Path("id") id: String): Response<Unit>

    @DELETE("rooms/{id}")
    suspend fun deleteRoom(@Path("id") id: String): Response<Unit>

    @POST("rooms/{id}/voice/offer")
    suspend fun sendVoiceOffer(@Path("id") id: String, @Body body: com.larp.launcher.data.remote.dto.VoiceOfferDto): Response<Unit>

    @POST("rooms/{id}/voice/answer")
    suspend fun sendVoiceAnswer(@Path("id") id: String, @Body body: com.larp.launcher.data.remote.dto.VoiceAnswerDto): Response<Unit>

    @POST("rooms/{id}/voice/ice")
    suspend fun sendIceCandidate(@Path("id") id: String, @Body body: com.larp.launcher.data.remote.dto.VoiceIceCandidateDto): Response<Unit>

    @GET("rooms/{id}/voice/ice-servers")
    suspend fun getIceServers(@Path("id") id: String): List<IceServerDto>

    @GET("announcements")
    suspend fun listAnnouncements(): List<AnnouncementDto>

    @POST("announcements")
    suspend fun createAnnouncement(@Body body: CreateAnnouncementRequest): AnnouncementDto

    @DELETE("announcements/{id}")
    suspend fun deleteAnnouncement(@Path("id") id: String): Response<Unit>

    @GET("broadcast/recent")
    suspend fun recentBroadcasts(@Query("limit") limit: Int = 20): List<BroadcastDto>

    @POST("broadcast")
    suspend fun sendBroadcast(@Body body: BroadcastRequest): BroadcastDto
}
