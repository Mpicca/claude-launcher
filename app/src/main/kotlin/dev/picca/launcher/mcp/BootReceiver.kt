package dev.picca.launcher.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.picca.launcher.LauncherApp

/**
 * Brings the MCP server (and its tunnel) back after a reboot, so Claude's connector keeps
 * working without anyone opening the app. BOOT_COMPLETED is one of the exemptions that
 * allows starting a foreground service from the background on Android 12+.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!LauncherApp.instance.pairing.serverEnabled) return
        Log.i("BootReceiver", "${intent.action}: restarting MCP server")
        McpService.start(context)
    }
}
