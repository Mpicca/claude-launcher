package dev.picca.launcher.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs the ngrok agent that ships inside the APK (as `lib/arm64-v8a/libngrok.so`, which
 * Android extracts to an executable location) and keeps it alive while the MCP server runs.
 *
 * With an ngrok *static domain* the public URL never changes, so the Claude connector is
 * configured once and keeps working across reboots, Wi-Fi/cellular changes, and app updates.
 * No Termux, no laptop, no re-pasting.
 */
class NgrokTunnel(private val context: Context) {

    sealed class State {
        data object Off : State()
        data object Starting : State()
        data class Online(val url: String) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> get() = _state

    private val wantRunning = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    @Volatile private var supervisor: Thread? = null

    val binary: File get() = File(context.applicationInfo.nativeLibraryDir, "libngrok.so")
    val available: Boolean get() = binary.canExecute()

    private val recent = ArrayDeque<String>()
    private fun remember(line: String) = synchronized(recent) {
        recent.addLast(line.take(600))
        while (recent.size > 80) recent.removeFirst()
    }

    /** Everything needed to debug a tunnel problem from a pasted message. Secrets are redacted. */
    fun diagnostics(authtoken: String, domain: String): String = buildString {
        appendLine("claude-launcher tunnel diagnostics")
        appendLine("state: ${state.value}")
        appendLine("binary: ${binary.absolutePath} exists=${binary.exists()} exec=${binary.canExecute()} size=${binary.length()}")
        appendLine("abi: ${android.os.Build.SUPPORTED_ABIS.joinToString()}  sdk=${android.os.Build.VERSION.SDK_INT}")
        appendLine("domain: '$domain'  authtoken: ${if (authtoken.isBlank()) "(empty)" else "len=${authtoken.length} ${authtoken.take(4)}…"}")
        appendLine("--- last log lines ---")
        synchronized(recent) { recent.forEach { appendLine(it.replace(authtoken.ifBlank { "\u0000" }, "<authtoken>")) } }
    }

    /** Starts (or restarts) the agent forwarding `127.0.0.1:[port]` to [domain]. Idempotent. */
    @Synchronized
    fun start(port: Int, authtoken: String, domain: String) {
        if (!available) {
            _state.value = State.Error("ngrok binary missing from this build")
            return
        }
        if (wantRunning.getAndSet(true)) return
        _state.value = State.Starting
        supervisor = thread(name = "ngrok-supervisor", isDaemon = true) { supervise(port, authtoken, domain) }
    }

    @Synchronized
    fun stop() {
        wantRunning.set(false)
        process?.destroy()
        process = null
        supervisor?.interrupt()
        supervisor = null
        _state.value = State.Off
    }

    private fun supervise(port: Int, authtoken: String, domain: String) {
        var backoffMs = 2_000L
        while (wantRunning.get()) {
            val startedAt = System.currentTimeMillis()
            try {
                runOnce(port, authtoken, domain)
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                Log.e(TAG, "ngrok failed", e)
                remember("exception: $e")
                _state.value = State.Error(e.message ?: e.javaClass.simpleName)
            }
            if (!wantRunning.get()) return
            // Ran for a while before dying → treat as a fresh failure, reset backoff.
            if (System.currentTimeMillis() - startedAt > 60_000) backoffMs = 2_000L
            if (_state.value !is State.Error) _state.value = State.Error("ngrok exited; restarting")
            try { Thread.sleep(backoffMs) } catch (e: InterruptedException) { return }
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    private fun runOnce(port: Int, authtoken: String, domain: String) {
        val home = File(context.filesDir, "ngrok").apply { mkdirs() }
        val cfg = File(home, "ngrok.yml")
        // dns_resolver_ips: Go binaries can't read Android's DNS settings (no /etc/resolv.conf),
        // so give the agent resolvers explicitly.
        cfg.writeText(
            """
            version: "2"
            authtoken: "$authtoken"
            web_addr: false
            dns_resolver_ips: ["1.1.1.1", "8.8.8.8", "9.9.9.9"]
            """.trimIndent() + "\n",
        )
        val cmd = listOf(
            binary.absolutePath, "http", "127.0.0.1:$port",
            "--domain", domain,
            "--config", cfg.absolutePath,
            "--log", "stdout", "--log-format", "json",
        )
        val p = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = home.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }
            .start()
        process = p
        _state.value = State.Starting
        remember("exec: ${cmd.drop(1).joinToString(" ")}")
        Log.i(TAG, "ngrok started: ${cmd.drop(1).joinToString(" ")}")

        p.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (!wantRunning.get()) break
                handleLogLine(line)
            }
        }
        val code = p.waitFor()
        remember("exit: code=$code")
        Log.w(TAG, "ngrok exited with $code")
        if (wantRunning.get() && _state.value !is State.Error) _state.value = State.Error("ngrok exited ($code)")
    }

    private fun handleLogLine(line: String) {
        if (line.isBlank()) return
        remember(line)
        Log.d(TAG, line.take(400))
        val url = URL_RE.find(line)?.groupValues?.get(1)
        if (url != null && (line.contains("started tunnel") || line.contains("\"obj\":\"tunnels\""))) {
            _state.value = State.Online(url)
            return
        }
        if (line.contains("\"lvl\":\"eror\"") || line.contains("\"lvl\":\"crit\"")) {
            val msg = ERR_RE.find(line)?.groupValues?.get(1) ?: MSG_RE.find(line)?.groupValues?.get(1) ?: "ngrok error"
            _state.value = State.Error(msg.replace("\\n", " ").take(300))
        }
    }

    companion object {
        private const val TAG = "NgrokTunnel"
        private val URL_RE = Regex("\"url\":\"(https://[^\"]+)\"")
        private val ERR_RE = Regex("\"err\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        private val MSG_RE = Regex("\"msg\":\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}
