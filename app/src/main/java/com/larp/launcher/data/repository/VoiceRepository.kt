package com.larp.launcher.data.repository

import com.larp.launcher.data.remote.api.LarpApi
import com.larp.launcher.data.remote.dto.VoiceAnswerDto
import com.larp.launcher.data.remote.dto.VoiceIceCandidateDto
import com.larp.launcher.data.remote.dto.VoiceOfferDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceRepository @Inject constructor(
    private val api: LarpApi
) {

    suspend fun sendOffer(roomId: String, sdp: String, type: String): Boolean = withContext(Dispatchers.IO) {
        api.sendVoiceOffer(roomId, VoiceOfferDto(sdp = sdp, type = type, roomId = roomId)).isSuccessful
    }

    suspend fun sendAnswer(roomId: String, sdp: String, type: String): Boolean = withContext(Dispatchers.IO) {
        api.sendVoiceAnswer(roomId, VoiceAnswerDto(sdp = sdp, type = type, roomId = roomId)).isSuccessful
    }

    suspend fun sendIceCandidate(roomId: String, userId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?): Boolean =
        withContext(Dispatchers.IO) {
            api.sendIceCandidate(
                roomId,
                VoiceIceCandidateDto(
                    candidate = candidate,
                    sdpMid = sdpMid,
                    sdpMLineIndex = sdpMLineIndex,
                    roomId = roomId,
                    userId = userId
                )
            ).isSuccessful
        }
}
