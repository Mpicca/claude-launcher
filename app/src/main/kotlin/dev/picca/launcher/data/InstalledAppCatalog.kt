package dev.picca.launcher.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import androidx.core.content.ContextCompat
import dev.picca.launcher.core.engine.AppCatalog
import dev.picca.launcher.core.model.AppRef
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only view of the launchable apps on the device, via [LauncherApps] — the same
 * API every third-party launcher uses. It cannot install, uninstall, or alter apps.
 */
class InstalledAppCatalog(private val context: Context) : AppCatalog {
    private val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val user: UserHandle = Process.myUserHandle()

    @Volatile
    private var cache: List<AppRef> = emptyList()
    private val iconCache = ConcurrentHashMap<String, Drawable>()
    var onChanged: () -> Unit = {}

    override fun installedApps(): List<AppRef> {
        if (cache.isEmpty()) refresh()
        return cache
    }

    @Synchronized
    fun refresh() {
        val self = context.packageName
        cache = launcherApps.getActivityList(null, user)
            .filter { it.applicationInfo.packageName != self }
            .map { info ->
                AppRef(
                    packageName = info.applicationInfo.packageName,
                    activityName = info.componentName.className,
                    label = info.label?.toString()?.ifBlank { info.applicationInfo.packageName } ?: info.applicationInfo.packageName,
                )
            }
            .sortedBy { it.label.lowercase() }
        iconCache.clear()
    }

    fun icon(app: AppRef): Drawable? = iconCache.getOrPut(app.componentKey) {
        runCatching {
            launcherApps.getActivityList(app.packageName, user)
                .firstOrNull { it.componentName.className == app.activityName }
                ?.getIcon(0)
        }.getOrNull() ?: return null
    }

    /** Launch an app. This is the only thing the launcher ever *does* to another app: open it. */
    fun launch(app: AppRef): Boolean = runCatching {
        launcherApps.startMainActivity(ComponentName(app.packageName, app.activityName), user, null, null)
        true
    }.getOrDefault(false)

    fun startWatching() {
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) = changed()
            override fun onPackageAdded(packageName: String?, user: UserHandle?) = changed()
            override fun onPackageChanged(packageName: String?, user: UserHandle?) = changed()
            override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = changed()
            override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = changed()
        })
        // Belt and braces for OEMs that don't deliver LauncherApps callbacks reliably.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) = changed()
            },
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun changed() { refresh(); onChanged() }
}
