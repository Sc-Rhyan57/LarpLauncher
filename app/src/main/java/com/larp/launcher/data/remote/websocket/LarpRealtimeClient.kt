package com.larp.launcher.data.remote.websocket

import com.larp.launcher.data.remote.dto.AnnouncementDto
import com.larp.launcher.data.remote.dto.BroadcastDto
import com.larp.launcher.data.remote.dto.RoomEventDto
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LarpRealtimeEvent {
    val type: String

    data class Room(val payload: RoomEventDto) : LarpRealtimeEvent { override val type = "room" }
    data class Broadcast(val payload: BroadcastDto) : LarpRealtimeEvent { override val type = "broadcast" }
    data class Announcement(val payload: AnnouncementDto) : LarpRealtimeEvent { override val type = "announcement" }
    data class VoiceOffer(val sdp: String, val type: String, val roomId: String, val userId: String) : LarpRealtimeEvent { override val type = "voice_offer" }
    data class VoiceAnswer(val sdp: String, val type: String, val roomId: String, val userId: String) : LarpRealtimeEvent { override val type = "voice_answer" }
    data class VoiceIce(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?, val roomId: String, val userId: String) : LarpRealtimeEvent { override val type = "voice_ice" }
    data class Presence(val userId: String, val online: Boolean) : LarpRealtimeEvent { override val type = "presence" }
    data class Raw(val raw: String) : LarpRealtimeEvent { override val type = "raw" }
    data object Open : LarpRealtimeEvent { override val type = "_open" }
    data object Closed : LarpRealtimeEvent { override val type = "_closed" }
    data class Failure(val throwable: Throwable) : LarpRealtimeEvent { override val type = "_failure" }
}

@Singleton
class LarpRealtimeClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json
) {

    fun connect(baseUrl: String, token: String): Flow<LarpRealtimeEvent> = callbackFlow {
        val wsUrl = baseUrl
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://")
            .trimEnd('/') + "/realtime?token=$token"

        val request = Request.Builder()
            .url(wsUrl)
            .header("Sec-WebSocket-Protocol", "larp.v1")
            .build()

        var webSocket: WebSocket? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(LarpRealtimeEvent.Open)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = json.decodeFromString<JsonObject>(text)
                    val type = obj["type"]?.jsonPrimitive?.content.orEmpty()
                    val payload = obj["payload"] ?: obj
                    val event: LarpRealtimeEvent = when (type) {
                        "room" -> LarpRealtimeEvent.Room(json.decodeFromJsonElement(payload))
                        "broadcast" -> LarpRealtimeEvent.Broadcast(json.decodeFromJsonElement(payload))
                        "announcement" -> LarpRealtimeEvent.Announcement(json.decodeFromJsonElement(payload))
                        "voice_offer" -> {
                            val o = payload as JsonObject
                            LarpRealtimeEvent.VoiceOffer(
                                o["sdp"]!!.jsonPrimitive.content,
                                o["type"]!!.jsonPrimitive.content,
                                o["roomId"]!!.jsonPrimitive.content,
                                o["userId"]!!.jsonPrimitive.content
                            )
                        }
                        "voice_answer" -> {
                            val o = payload as JsonObject
                            LarpRealtimeEvent.VoiceAnswer(
                                o["sdp"]!!.jsonPrimitive.content,
                                o["type"]!!.jsonPrimitive.content,
                                o["roomId"]!!.jsonPrimitive.content,
                                o["userId"]!!.jsonPrimitive.content
                            )
                        }
                        "voice_ice" -> {
                            val o = payload as JsonObject
                            LarpRealtimeEvent.VoiceIce(
                                o["candidate"]!!.jsonPrimitive.content,
                                o["sdpMid"]?.jsonPrimitive?.content,
                                o["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull(),
                                o["roomId"]!!.jsonPrimitive.content,
                                o["userId"]!!.jsonPrimitive.content
                            )
                        }
                        "presence" -> {
                            val o = payload as JsonObject
                            LarpRealtimeEvent.Presence(
                                o["userId"]!!.jsonPrimitive.content,
                                o["online"]!!.jsonPrimitive.content.toBoolean()
                            )
                        }
                        else -> LarpRealtimeEvent.Raw(text)
                    }
                    trySend(event)
                }.onFailure { trySend(LarpRealtimeEvent.Raw(text)) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(LarpRealtimeEvent.Closed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(LarpRealtimeEvent.Failure(t))
            }
        }

        webSocket = httpClient.newWebSocket(request, listener)

        awaitClose {
            webSocket?.cancel()
        }
    }

    fun send(baseUrl: String, token: String, message: String): Boolean {
        return false
    }
}
