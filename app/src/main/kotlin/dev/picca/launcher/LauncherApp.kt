package dev.picca.launcher

import android.app.Application
import dev.picca.launcher.core.engine.LayoutEngine
import dev.picca.launcher.core.mcp.McpServer
import dev.picca.launcher.data.FileLayoutStore
import dev.picca.launcher.data.InstalledAppCatalog
import dev.picca.launcher.data.PairingConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide singletons. The engine is the single source of truth; the UI observes
 * [layoutFlow] and the MCP server mutates through the same engine, so what Claude does
 * shows up on screen immediately.
 */
class LauncherApp : Application() {
    lateinit var catalog: InstalledAppCatalog private set
    lateinit var store: FileLayoutStore private set
    lateinit var engine: LayoutEngine private set
    lateinit var mcp: McpServer private set
    lateinit var pairing: PairingConfig private set

    private val _layoutFlow = MutableStateFlow(dev.picca.launcher.core.model.Layout())
    val layoutFlow: StateFlow<dev.picca.launcher.core.model.Layout> get() = _layoutFlow

    override fun onCreate() {
        super.onCreate()
        instance = this
        catalog = InstalledAppCatalog(this)
        store = FileLayoutStore(this)
        pairing = PairingConfig(this)
        val initial = store.load()
        engine = LayoutEngine(initial, catalog, onCommit = { layout ->
            store.save(layout)
            _layoutFlow.value = layout
        })
        _layoutFlow.value = initial
        // Drop references to anything uninstalled since last run (harmless if nothing changed).
        runCatching { engine.pruneUninstalled() }
        mcp = McpServer(engine, serverVersion = BuildConfig.VERSION_NAME)
        catalog.onChanged = { runCatching { engine.pruneUninstalled() }; _layoutFlow.value = engine.current }
        catalog.startWatching()
    }

    companion object {
        lateinit var instance: LauncherApp private set
    }
}
