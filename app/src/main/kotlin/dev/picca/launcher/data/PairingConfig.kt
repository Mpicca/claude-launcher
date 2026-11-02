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

    /** ngrok authtoken (one-time paste from dashboard.ngrok.com). Empty = tunnel disabled. */
    var ngrokAuthtoken: String
        get() = prefs.getString(KEY_NGROK_TOKEN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_NGROK_TOKEN, value.trim()).apply() }

    /** ngrok static domain, e.g. `quiet-otter-123.ngrok-free.app` (no scheme, no path). */
    var ngrokDomain: String
        get() = hostnameIn(prefs.getString(KEY_NGROK_DOMAIN, "") ?: "") // re-clean values saved by older builds
        set(value) { prefs.edit().putString(KEY_NGROK_DOMAIN, hostnameIn(value)).apply() }

    /**
     * Accept whatever was pasted — a bare domain, a URL with a path, or the dashboard's whole
     * sample command ("ngrok http 8080 --url https://x.ngrok-free.dev") — and keep just the
     * hostname. Prefers an ngrok host if several hostnames appear.
     */
    private fun hostnameIn(value: String): String {
        val hosts = HOST_RE.findAll(value.lowercase()).map { it.value }.toList()
        return hosts.firstOrNull { "ngrok" in it } ?: hosts.firstOrNull() ?: ""
    }

    val tunnelConfigured: Boolean get() = ngrokAuthtoken.isNotBlank() && ngrokDomain.isNotBlank()

    /** The URL to paste into Claude → Connectors. Stable for as long as the domain and token are. */
    val connectorUrl: String? get() = if (ngrokDomain.isBlank()) null else "https://$ngrokDomain/t/$token/mcp"

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
        private const val KEY_NGROK_TOKEN = "ngrok_authtoken"
        private const val KEY_NGROK_DOMAIN = "ngrok_domain"
        private val HOST_RE = Regex("""\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}\b""")
    }
}
