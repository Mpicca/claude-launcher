package dev.picca.launcher.core.devserver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.picca.launcher.core.engine.AppCatalog
import dev.picca.launcher.core.engine.LayoutEngine
import dev.picca.launcher.core.mcp.McpServer
import dev.picca.launcher.core.model.AppRef
import dev.picca.launcher.core.model.Layout
import dev.picca.launcher.core.store.LayoutCodec
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * Desktop stand-in for the Android MCP service: same McpServer, same auth, same routes,
 * backed by a fake app catalog and a JSON file. Use it to try the connector from Claude
 * before installing the APK, or to debug the protocol.
 *
 *   java -cp core.jar dev.picca.launcher.core.devserver.DevServerKt [port] [token] [layout.json]
 */
fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 8765
    val token = args.getOrNull(1) ?: "dev-token"
    val file = File(args.getOrNull(2) ?: "dev-layout.json")

    val catalog = AppCatalog { FAKE_APPS }
    val engine = LayoutEngine(LayoutCodec.decodeOrDefault(file.takeIf { it.exists() }?.readText()), catalog, onCommit = {
        file.writeText(LayoutCodec.encode(it))
    })
    val mcp = McpServer(engine, serverName = "claude-launcher-dev")

    val server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
    server.createContext("/health") { ex ->
        ex.send(200, """{"ok":true,"server":"claude-launcher-dev","revision":${engine.current.revision}}""")
    }
    server.createContext("/mcp") { ex ->
        when (ex.requestMethod) {
            "POST" -> {
                val auth = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()
                if (auth == null || !MessageDigest.isEqual(auth.toByteArray(), token.toByteArray())) {
                    ex.send(401, """{"error":"unauthorized"}"""); return@createContext
                }
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val resp = mcp.handle(body)
                if (resp == null) ex.send(202, "") else ex.send(200, resp)
            }
            "DELETE" -> ex.send(200, "")
            else -> ex.send(405, "")
        }
    }
    server.createContext("/") { ex -> ex.send(200, "Claude Launcher dev MCP server. POST /mcp with Authorization: Bearer <token>") }
    server.start()
    println("claude-launcher dev MCP server on http://0.0.0.0:$port/mcp  (token: $token, layout: ${file.absolutePath})")
}

private fun HttpExchange.send(status: Int, body: String) {
    val bytes = body.toByteArray()
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) } else close()
}

private val FAKE_APPS = listOf(
    "com.android.dialer" to "Phone", "com.google.android.apps.messaging" to "Messages", "com.android.camera2" to "Camera",
    "com.android.chrome" to "Chrome", "com.google.android.gm" to "Gmail", "com.google.android.apps.maps" to "Maps",
    "com.google.android.youtube" to "YouTube", "com.spotify.music" to "Spotify", "com.netflix.mediaclient" to "Netflix",
    "com.instagram.android" to "Instagram", "com.reddit.frontpage" to "Reddit", "com.whatsapp" to "WhatsApp",
    "com.slack" to "Slack", "com.microsoft.teams" to "Teams", "com.microsoft.office.outlook" to "Outlook",
    "com.chase.sig.android" to "Chase", "com.venmo" to "Venmo", "com.ubercab" to "Uber", "com.airbnb.android" to "Airbnb",
    "com.google.android.apps.photos" to "Photos", "com.android.settings" to "Settings", "com.strava" to "Strava",
    "com.anthropic.claude" to "Claude",
).map { (p, l) -> AppRef(p, "$p.MainActivity", l) }
