package dev.picca.launcher.data

import android.content.Context
import dev.picca.launcher.core.model.Layout
import dev.picca.launcher.core.store.LayoutCodec
import dev.picca.launcher.core.store.LayoutStore
import java.io.File

/**
 * Layout persisted as JSON in the app's private files directory, written atomically
 * (tmp + rename) so a crash mid-write can never corrupt the home screen. A rolling
 * backup of the previous file is kept too.
 *
 * Note: this lives in *this app's* sandbox. Nothing here touches another launcher's data.
 */
class FileLayoutStore(context: Context) : LayoutStore {
    private val file = File(context.filesDir, "layout.json")
    private val backup = File(context.filesDir, "layout.backup.json")
    private val tmp = File(context.filesDir, "layout.json.tmp")

    @Synchronized
    override fun load(): Layout {
        val primary = file.takeIf { it.exists() }?.readText()
        val decoded = LayoutCodec.decodeOrDefault(primary)
        if (primary != null && decoded == Layout() && primary.isNotBlank()) {
            // Primary unreadable — try the backup before giving up.
            backup.takeIf { it.exists() }?.readText()?.let { return LayoutCodec.decodeOrDefault(it) }
        }
        return decoded
    }

    @Synchronized
    override fun save(layout: Layout) {
        tmp.writeText(LayoutCodec.encode(layout))
        if (file.exists()) file.copyTo(backup, overwrite = true)
        if (!tmp.renameTo(file)) {
            // Rename across the same dir should always work; fall back to a plain write.
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
