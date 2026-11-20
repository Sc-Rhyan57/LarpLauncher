package com.larp.launcher.core

import android.content.Context
import com.larp.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class LarpEnv(
    val apiUrl: String = DEFAULT_API_URL,
    val wsUrl: String = DEFAULT_WS_URL,
    val discordClientId: String = "",
    val discordRedirectUri: String = "com.larp.launcher://oauth",
    val discordScope: String = "identify email guilds",
    val webrtcIceServers: List<String> = listOf("stun:stun.larp.workers.dev:3478"),
    val turnUrl: String = "",
    val turnUser: String = "",
    val turnCredential: String = "",
    val gamePackage: String = "com.innersloth.spacemafia",
    val fridaGadgetBundled: Boolean = BuildConfig.FRIDA_GADGET_BUNDLED,
    val sentryDsn: String = "",
    val fcmSenderId: String = "",
    val runtimeDotnetUrl: String = DEFAULT_RUNTIME_DOTNET_URL,
    val runtimeBepinexUrl: String = DEFAULT_RUNTIME_BEPINEX_URL
) {
    companion object {
        const val DEFAULT_API_URL = "https://larp-relaxxx.rhyanlus501-b24.workers.dev"
        const val DEFAULT_WS_URL = "wss://larp-relaxxx.rhyanlus501-b24.workers.dev"
        const val DEFAULT_RUNTIME_DOTNET_URL = ""
        const val DEFAULT_RUNTIME_BEPINEX_URL = ""
    }
}

class LarpEnvProvider private constructor(
    private val context: Context,
    private val json: Json
) {
    private val _state = MutableStateFlow(loadInitial())
    val state: StateFlow<LarpEnv> = _state.asStateFlow()

    fun current(): LarpEnv = _state.value

    suspend fun reload() {
        withContext(Dispatchers.IO) {
            _state.value = loadFromDisk()
        }
    }

    suspend fun update(env: LarpEnv) {
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "larp-env.json")
            file.writeText(json.encodeToString(LarpEnv.serializer(), env))
            _state.value = env
        }
    }

    suspend fun resetToDefaults() {
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "larp-env.json")
            file.delete()
            _state.value = loadInitial()
        }
    }

    private fun loadInitial(): LarpEnv = loadFromDisk()

    private fun loadFromDisk(): LarpEnv {
        val userFile = File(context.filesDir, "larp-env.json")
        if (userFile.isFile) {
            runCatching {
                val text = userFile.readText()
                return json.decodeFromString(LarpEnv.serializer(), text)
            }
        }
        runCatching {
            val text = context.assets.open("larp-env.json").bufferedReader().use { it.readText() }
            return json.decodeFromString(LarpEnv.serializer(), text)
        }
        return LarpEnv()
    }

    companion object {
        @Volatile private var instance: LarpEnvProvider? = null

        fun get(context: Context, json: Json): LarpEnvProvider =
            instance ?: synchronized(this) {
                instance ?: LarpEnvProvider(context.applicationContext, json).also { instance = it }
            }
    }
}
