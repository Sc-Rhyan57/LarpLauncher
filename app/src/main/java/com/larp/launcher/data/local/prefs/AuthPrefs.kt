package com.larp.launcher.data.local.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AuthPrefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "larp_auth",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveVerifier(verifier: String) {
        prefs.edit().putString(KEY_VERIFIER, verifier).apply()
    }

    fun consumeVerifier(): String? {
        val v = prefs.getString(KEY_VERIFIER, null)
        prefs.edit().remove(KEY_VERIFIER).apply()
        return v
    }

    fun saveSession(token: String, expiresAt: Long, userId: String, userName: String, isAdmin: Boolean, isOwner: Boolean, avatar: String?, discordId: String?) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_NAME, userName)
            .putBoolean(KEY_ADMIN, isAdmin)
            .putBoolean(KEY_OWNER, isOwner)
            .putString(KEY_AVATAR, avatar)
            .putString(KEY_DISCORD_ID, discordId)
            .apply()
    }

    fun token(): String? = prefs.getString(KEY_TOKEN, null)
        ?.takeIf { prefs.getLong(KEY_EXPIRES_AT, 0L) > System.currentTimeMillis() }

    fun rawToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun userName(): String = prefs.getString(KEY_USER_NAME, "").orEmpty()
    fun userId(): String = prefs.getString(KEY_USER_ID, "").orEmpty()
    fun avatar(): String? = prefs.getString(KEY_AVATAR, null)
    fun discordId(): String? = prefs.getString(KEY_DISCORD_ID, null)
    fun isAdmin(): Boolean = prefs.getBoolean(KEY_ADMIN, false)
    fun isOwner(): Boolean = prefs.getBoolean(KEY_OWNER, false)
    fun expiresAt(): Long = prefs.getLong(KEY_EXPIRES_AT, 0L)

    fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAt()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_VERIFIER = "verifier"
        private const val KEY_TOKEN = "token"
        private const val KEY_EXPIRES_AT = "expira"
        private const val KEY_USER_ID = "uid"
        private const val KEY_USER_NAME = "nome"
        private const val KEY_ADMIN = "admin"
        private const val KEY_OWNER = "dono"
        private const val KEY_AVATAR = "avatar"
        private const val KEY_DISCORD_ID = "discord_id"
    }
}
