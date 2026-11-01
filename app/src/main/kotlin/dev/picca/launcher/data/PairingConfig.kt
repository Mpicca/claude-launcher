package dev.picca.launcher.data

import android.content.Context
import java.security.SecureRandom

/**
 * A bearer token that every MCP request must carry. Generated once per install,
 * shown in the app, rotatable from the app. Without it the endpoint returns 401 —
 * so nothing on your network can rearrange your home screen without being paired.
 */
class PairingConfig(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    val token: String
        get() = prefs.getString(KEY_TOKEN, null) ?: rotate()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) { prefs.edit().putInt(KEY_PORT, value).apply() }

    var serverEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    fun rotate(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_TOKEN, t).apply()
        return t
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val KEY_TOKEN = "token"
        private const val KEY_PORT = "port"
        private const val KEY_ENABLED = "enabled"
    }
}
