package dev.picca.launcher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.picca.launcher.LauncherApp
import dev.picca.launcher.core.engine.LayoutException
import dev.picca.launcher.core.model.AppRef
import dev.picca.launcher.core.model.Item
import dev.picca.launcher.core.model.Layout
import dev.picca.launcher.mcp.McpService
import dev.picca.launcher.mcp.NgrokTunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Thin UI adapter over the engine. Every mutation goes through the engine so it's undoable. */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val launcherApp = app as LauncherApp
    val layout: StateFlow<Layout> = launcherApp.layoutFlow

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    private val _openFolder = MutableStateFlow<Item.Folder?>(null)
    val openFolder: StateFlow<Item.Folder?> = _openFolder

    private val _drawerOpen = MutableStateFlow(false)
    val drawerOpen: StateFlow<Boolean> = _drawerOpen

    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen

    val serverRunning: Boolean get() = McpService.running
    val pairingToken: String get() = launcherApp.pairing.token
    val port: Int get() = launcherApp.pairing.port

    fun installedApps(): List<AppRef> = launcherApp.catalog.installedApps()
    fun icon(app: AppRef) = launcherApp.catalog.icon(app)

    fun launch(app: AppRef) {
        if (!launcherApp.catalog.launch(app)) _toast.value = "Couldn't open ${app.label}"
    }

    fun openFolder(f: Item.Folder?) { _openFolder.value = f }
    fun setDrawer(open: Boolean) { _drawerOpen.value = open }
    fun setSettings(open: Boolean) { _settingsOpen.value = open }
    fun clearToast() { _toast.value = null }

    fun placeFromDrawer(app: AppRef) = mutate { launcherApp.engine.placeApp(app.componentKey); "Added ${app.label}" }
    fun hide(app: AppRef) = mutate { launcherApp.engine.hideApp(app.componentKey); "Removed ${app.label} from home (still in drawer)" }
    fun removeFromFolder(folder: Item.Folder, app: AppRef) = mutate {
        launcherApp.engine.removeFromFolder(folder.id, listOf(app.componentKey)); "Moved ${app.label} out of ${folder.name}"
    }
    fun dissolve(folder: Item.Folder) = mutate { launcherApp.engine.dissolveFolder(folder.id); "Dissolved ${folder.name}" }
    fun rename(folder: Item.Folder, name: String) = mutate { launcherApp.engine.renameFolder(folder.id, name); "Renamed" }
    fun undo() = mutate { launcherApp.engine.undo(); "Undone" }
    fun showAll() = mutate { launcherApp.engine.placeAllApps(); "All apps placed" }

    fun toggleServer() {
        if (McpService.running) { McpService.stop(getApplication<Application>()); launcherApp.pairing.serverEnabled = false }
        else { McpService.start(getApplication<Application>()); launcherApp.pairing.serverEnabled = true }
    }

    fun rotateToken(): String = launcherApp.pairing.rotate().also {
        if (McpService.running) { McpService.stop(getApplication<Application>()); McpService.start(getApplication<Application>()) }
    }

    val tunnelState: StateFlow<NgrokTunnel.State> get() = launcherApp.tunnel.state
    val tunnelAvailable: Boolean get() = launcherApp.tunnel.available
    val ngrokAuthtoken: String get() = launcherApp.pairing.ngrokAuthtoken
    val ngrokDomain: String get() = launcherApp.pairing.ngrokDomain
    val connectorUrl: String? get() = launcherApp.pairing.connectorUrl

    /** One-time setup: save the ngrok credentials, turn the server on, and (re)start it so the tunnel comes up. */
    fun saveTunnel(authtoken: String, domain: String) {
        val p = launcherApp.pairing
        p.ngrokAuthtoken = authtoken
        p.ngrokDomain = domain
        val ctx = getApplication<Application>()
        if (McpService.running) McpService.stop(ctx)
        if (p.tunnelConfigured) {
            p.serverEnabled = true
            McpService.start(ctx)
            _toast.value = "Tunnel saved — starting"
        } else {
            _toast.value = "Tunnel cleared"
        }
    }

    private fun mutate(block: () -> String) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.Default) {
                try { block() } catch (e: LayoutException) { e.message ?: e.code.name }
            }
            _toast.value = msg
            // keep the open-folder sheet in sync with the committed layout
            _openFolder.value = _openFolder.value?.let { launcherApp.engine.current.findFolder(it.id) }
        }
    }
}
