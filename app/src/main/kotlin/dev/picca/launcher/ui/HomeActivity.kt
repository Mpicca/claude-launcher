package dev.picca.launcher.ui

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.picca.launcher.core.model.AppRef
import dev.picca.launcher.core.model.Item
import dev.picca.launcher.core.model.Layout
import dev.picca.launcher.mcp.NgrokTunnel

class HomeActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A launcher must never exit on back: close sheets instead.
        onBackPressedDispatcher.addCallback(this) {
            vm.openFolder(null); vm.setDrawer(false); vm.setSettings(false)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeScreen(vm)
            }
        }
    }

    // Home button while already home → close any sheet, go to page 0.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        vm.openFolder(null); vm.setDrawer(false); vm.setSettings(false)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel) {
    val layout by vm.layout.collectAsStateWithLifecycle()
    val openFolder by vm.openFolder.collectAsStateWithLifecycle()
    val drawerOpen by vm.drawerOpen.collectAsStateWithLifecycle()
    val settingsOpen by vm.settingsOpen.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    LaunchedEffect(toast) {
        toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.clearToast() }
    }

    val pagerState = rememberPagerState(pageCount = { layout.pages.size })

    Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { pageIdx ->
                val page = layout.pages.getOrNull(pageIdx) ?: return@HorizontalPager
                LazyVerticalGrid(
                    columns = GridCells.Fixed(layout.gridColumns),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(page.items, key = { it.id }) { item ->
                        when (item) {
                            is Item.App -> AppCell(item.app, vm.icon(item.app), onClick = { vm.launch(item.app) }, onLongClick = { vm.hide(item.app) })
                            is Item.Folder -> FolderCell(item, vm, onClick = { vm.openFolder(item) })
                        }
                    }
                }
            }
            PageDots(layout, pagerState.currentPage)
            Dock(vm)
        }

        openFolder?.let { f ->
            ModalBottomSheet(onDismissRequest = { vm.openFolder(null) }) {
                FolderSheet(f, vm)
            }
        }
        if (drawerOpen) {
            ModalBottomSheet(onDismissRequest = { vm.setDrawer(false) }) {
                DrawerSheet(vm)
            }
        }
        if (settingsOpen) {
            ModalBottomSheet(onDismissRequest = { vm.setSettings(false) }) {
                SettingsSheet(vm)
            }
        }
    }
}

@Composable
private fun PageDots(layout: Layout, current: Int) {
    Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.Center) {
        layout.pages.forEach { p ->
            Box(
                Modifier.padding(3.dp).size(if (p.index == current) 8.dp else 6.dp)
                    .clip(RoundedCornerShape(50)).background(if (p.index == current) Color.White else Color(0x66FFFFFF)),
            )
        }
    }
}

@Composable
private fun Dock(vm: HomeViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        OutlinedButton(onClick = { vm.setDrawer(true) }) { Text("Apps") }
        OutlinedButton(onClick = { vm.undo() }) { Text("Undo") }
        OutlinedButton(onClick = { vm.setSettings(true) }) {
            Text(if (vm.serverRunning) "Claude ●" else "Claude ○")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(app: AppRef, icon: Drawable?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconImage(icon, 52.dp)
        Spacer(Modifier.height(4.dp))
        Text(app.label, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCell(folder: Item.Folder, vm: HomeViewModel, onClick: () -> Unit) {
    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = { vm.dissolve(folder) }).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x33FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            // 2x2 preview of the first four icons
            Column(verticalArrangement = Arrangement.Center) {
                folder.apps.take(4).chunked(2).forEach { row ->
                    Row { row.forEach { IconImage(vm.icon(it), 20.dp, Modifier.padding(1.dp)) } }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(folder.name, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun IconImage(icon: Drawable?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val bmp = remember(icon) { icon?.toBitmap(128, 128)?.asImageBitmap() }
    if (bmp != null) Image(bmp, contentDescription = null, modifier = modifier.size(size).clip(RoundedCornerShape(12.dp)))
    else Box(modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color(0x44FFFFFF)))
}

@Composable
private fun FolderSheet(folder: Item.Folder, vm: HomeViewModel) {
    var renaming by remember { mutableStateOf(false) }
    var name by remember(folder.id) { mutableStateOf(folder.name) }
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(folder.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { renaming = true }) { Text("Rename") }
            TextButton(onClick = { vm.dissolve(folder); vm.openFolder(null) }) { Text("Dissolve") }
        }
        Text("Tap to open · long-press to move out of folder", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.height(360.dp)) {
            items(folder.apps, key = { it.componentKey }) { app ->
                AppCell(app, vm.icon(app), onClick = { vm.launch(app); vm.openFolder(null) }, onLongClick = { vm.removeFromFolder(folder, app) })
            }
        }
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            confirmButton = { TextButton(onClick = { vm.rename(folder, name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
            title = { Text("Rename folder") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        )
    }
}

@Composable
private fun DrawerSheet(vm: HomeViewModel) {
    val layout by vm.layout.collectAsStateWithLifecycle()
    val placed = remember(layout) { layout.placedApps() }
    val apps = remember(layout) { vm.installedApps() }
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) { apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) } }
    Column(Modifier.padding(16.dp)) {
        OutlinedTextField(query, { query = it }, placeholder = { Text("Search ${apps.size} apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Tap to open · long-press to add to home screen", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.height(480.dp)) {
            items(shown, key = { it.componentKey }) { app ->
                Box {
                    AppCell(app, vm.icon(app), onClick = { vm.launch(app); vm.setDrawer(false) }, onLongClick = { vm.placeFromDrawer(app) })
                    if (app.componentKey in placed) {
                        Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(8.dp).clip(RoundedCornerShape(50)).background(Color(0xFF4CAF50)))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSheet(vm: HomeViewModel) {
    var running by remember { mutableStateOf(vm.serverRunning) }
    var token by remember { mutableStateOf(vm.pairingToken) }
    val ip = remember { LocalIp.get() }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Claude connection", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "When the server is on, Claude can rearrange icons and folders on this home screen. " +
                "It cannot install, uninstall, or change any app, and every change is undoable.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.toggleServer(); running = !running }) { Text(if (running) "Stop MCP server" else "Start MCP server") }
        Spacer(Modifier.height(12.dp))
        val clipboard = LocalClipboardManager.current
        val tunnelState by vm.tunnelState.collectAsStateWithLifecycle()
        var authtoken by remember { mutableStateOf(vm.ngrokAuthtoken) }
        var domain by remember { mutableStateOf(vm.ngrokDomain) }
        val connectorUrl = if (domain.isBlank()) null else "https://${domain.trim().removePrefix("https://").trimEnd('/')}/t/$token/mcp"

        // ── Permanent public URL via the bundled ngrok agent ──────────────────────────────
        Surface(color = Color(0x22FFFFFF), shape = RoundedCornerShape(10.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Permanent URL (ngrok, built in)", fontWeight = FontWeight.Bold)
                Text(
                    "One-time setup: at dashboard.ngrok.com copy your Authtoken and claim a free static Domain. " +
                        "The app then runs the tunnel itself, on boot, forever.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = authtoken, onValueChange = { authtoken = it }, singleLine = true,
                    label = { Text("ngrok authtoken") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = domain, onValueChange = { domain = it }, singleLine = true,
                    label = { Text("ngrok static domain (e.g. name.ngrok-free.app)") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    Button(onClick = { vm.saveTunnel(authtoken, domain); running = true }) { Text("Save & start") }
                    Spacer(Modifier.size(8.dp))
                    if (connectorUrl != null) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(connectorUrl)) }) { Text("Copy connector URL") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val (status, color) = when (val s = tunnelState) {
                    is NgrokTunnel.State.Off -> (if (vm.tunnelAvailable) "Tunnel off" else "ngrok not bundled in this build") to Color(0xFFBDBDBD)
                    is NgrokTunnel.State.Starting -> "Tunnel starting…" to Color(0xFFFFC107)
                    is NgrokTunnel.State.Online -> "Online: ${s.url}" to Color(0xFF4CAF50)
                    is NgrokTunnel.State.Error -> "Tunnel error: ${s.message}" to Color(0xFFEF5350)
                }
                Text(status, color = color, fontSize = 12.sp)
                if (connectorUrl != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("Claude connector URL (no auth fields needed)", fontWeight = FontWeight.Bold)
                    Text(connectorUrl, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // ── Manual / LAN fallback ─────────────────────────────────────────────────────────
        Surface(color = Color(0x22FFFFFF), shape = RoundedCornerShape(10.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Local endpoint (any other tunnel)", fontWeight = FontWeight.Bold)
                Text("http://${ip ?: "<phone-ip>"}:${vm.port}/mcp", fontSize = 12.sp)
                Text("Path form: /t/$token/mcp   ·   Header form: Authorization: Bearer $token", fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = { clipboard.setText(AnnotatedString("/t/$token/mcp")) }) { Text("Copy path") }
            TextButton(onClick = { token = vm.rotateToken() }) { Text("Rotate token") }
            TextButton(onClick = { vm.showAll() }) { Text("Place all apps") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Rotating the token invalidates the old connector URL. The server and tunnel restart automatically after a reboot.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

object LocalIp {
    fun get(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    }.getOrNull()
}
