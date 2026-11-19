package com.larp.launcher.data.repository

import com.larp.launcher.data.local.prefs.AuthPrefs
import com.larp.launcher.data.remote.api.LarpApi
import com.larp.launcher.data.remote.dto.AuthTokenRequest
import com.larp.launcher.data.remote.dto.DiscordLoginUrlDto
import com.larp.launcher.domain.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: LarpApi,
    private val prefs: AuthPrefs
) {

    suspend fun beginLogin(): String = withContext(Dispatchers.IO) {
        val verifier = randomBase64Url(32)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        prefs.saveVerifier(verifier)
        challenge
    }

    suspend fun discordLoginUrl(state: String): DiscordLoginUrlDto = withContext(Dispatchers.IO) {
        api.discordLoginUrl(state)
    }

    suspend fun finishLogin(grant: String): User = withContext(Dispatchers.IO) {
        val verifier = prefs.consumeVerifier() ?: error("Login não iniciado")
        val response = api.finishLogin(AuthTokenRequest(grant = grant, verifier = verifier))
        prefs.saveSession(
            token = response.token,
            expiresAt = response.expiresAt,
            userId = response.user.id,
            userName = response.user.name,
            isAdmin = response.user.admin,
            isOwner = response.user.owner,
            avatar = response.user.avatar,
            discordId = response.user.discordId
        )
        response.user.toDomain()
    }

    fun currentUser(): User? {
        val token = prefs.token() ?: return null
        return User(
            id = prefs.userId(),
            name = prefs.userName(),
            avatar = prefs.avatar(),
            discordId = prefs.discordId(),
            isOwner = prefs.isOwner(),
            isAdmin = prefs.isAdmin()
        )
    }

    fun token(): String? = prefs.token()

    fun rawToken(): String? = prefs.rawToken()

    fun isLoggedIn(): Boolean = token() != null

    fun isAdmin(): Boolean = prefs.isAdmin() || prefs.isOwner()

    fun logout() {
        prefs.clear()
    }

    private fun randomBase64Url(len: Int): String {
        val bytes = ByteArray(len)
        SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun base64Url(bytes: ByteArray): String {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }

    private fun com.larp.launcher.data.remote.dto.UserDto.toDomain(): User = User(
        id = id,
        name = name,
        avatar = avatar,
        discordId = discordId,
        isOwner = owner,
        isAdmin = admin,
        isModerator = moderator,
        guilds = guilds.map { com.larp.launcher.domain.model.Guild(it.id, it.name, it.icon, it.owner) }
    )
}
