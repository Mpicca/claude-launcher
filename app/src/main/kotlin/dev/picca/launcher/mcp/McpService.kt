package dev.picca.launcher.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.picca.launcher.LauncherApp
import dev.picca.launcher.R
import dev.picca.launcher.ui.HomeActivity
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.security.MessageDigest

/**
 * Foreground service hosting the MCP endpoint over the Streamable HTTP transport:
 *   POST /mcp  — one JSON-RPC message in, one JSON response out (or 202 for notifications)
 *   GET  /mcp  — server-initiated stream not supported → 405 (allowed by the spec)
 *   GET  /health — unauthenticated liveness check
 *
 * Auth: `Authorization: Bearer <token>` on every /mcp request. Constant-time comparison.
 */
class McpService : Service() {
    private var server: EmbeddedServer<*, *>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        if (server != null) return
        val app = LauncherApp.instance
        val port = app.pairing.port
        val expected = app.pairing.token
        running = true

        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                get("/health") {
                    call.respondText("""{"ok":true,"server":"claude-launcher","revision":${app.engine.current.revision}}""", ContentType.Application.Json)
                }
                get("/") {
                    call.respondText(
                        "Claude Launcher MCP endpoint. POST JSON-RPC to /mcp with 'Authorization: Bearer <token>'.",
                        ContentType.Text.Plain,
                    )
                }
                post("/mcp") {
                    if (!authorized(call.request.header("Authorization"), expected)) {
                        call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                        return@post
                    }
                    val body = call.receiveText()
                    if (body.length > MAX_BODY) {
                        call.respond(HttpStatusCode.PayloadTooLarge)
                        return@post
                    }
                    val response = try {
                        app.mcp.handle(body)
                    } catch (e: Throwable) {
                        Log.e(TAG, "handler crashed", e)
                        """{"jsonrpc":"2.0","id":null,"error":{"code":-32603,"message":"internal error"}}"""
                    }
                    if (response == null) call.respond(HttpStatusCode.Accepted)
                    else call.respondText(response, ContentType.Application.Json)
                }
                get("/mcp") { call.respond(HttpStatusCode.MethodNotAllowed) }
                delete("/mcp") { call.respond(HttpStatusCode.OK) } // stateless: nothing to tear down
            }
        }.also {
            try {
                it.start(wait = false)
                Log.i(TAG, "MCP server listening on :$port")
            } catch (e: Throwable) {
                Log.e(TAG, "failed to start MCP server on :$port", e)
                running = false
                server = null
                stopSelf()
            }
        }
    }

    private fun authorized(header: String?, expected: String): Boolean {
        val presented = header?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(presented.toByteArray(), expected.toByteArray())
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "MCP server", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Claude can connect to this launcher"
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, McpService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mcp)
            .setContentTitle("Claude can organize your home screen")
            .setContentText("MCP server running on port ${LauncherApp.instance.pairing.port}")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        server?.stop(500, 1000)
        server = null
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "McpService"
        private const val CHANNEL = "mcp"
        private const val NOTIF_ID = 1
        private const val MAX_BODY = 1_000_000
        const val ACTION_STOP = "dev.picca.launcher.STOP_MCP"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, McpService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, McpService::class.java).setAction(ACTION_STOP))
        }
    }
}
