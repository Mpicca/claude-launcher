package dev.picca.launcher.core.model

/**
 * The launcher's own layout model.
 *
 * SAFETY CONTRACT: this model describes how *this launcher* arranges icons. It is
 * purely cosmetic bookkeeping owned by the app. Nothing in here references, modifies,
 * uninstalls, disables, or moves any other application. Dropping an app from the
 * layout only hides its icon from this launcher's home screen; the app remains
 * installed and reachable from the app drawer.
 */

/** A launchable app as seen by the launcher (package + activity component). */
data class AppRef(
    val packageName: String,
    val activityName: String,
    val label: String,
) {
    val componentKey: String get() = "$packageName/$activityName"

    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(activityName.isNotBlank()) { "activityName must not be blank" }
        require(PACKAGE_RE.matches(packageName)) { "invalid packageName: $packageName" }
    }

    companion object {
        // Java-style dotted identifier; what Android enforces for package names.
        private val PACKAGE_RE = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
    }
}

/** Something that occupies a slot on a home-screen page. */
sealed class Item {
    abstract val id: String
    data class App(override val id: String, val app: AppRef) : Item()
    data class Folder(
        override val id: String,
        val name: String,
        val apps: List<AppRef> = emptyList(),
    ) : Item() {
        init {
            require(name.isNotBlank()) { "folder name must not be blank" }
            require(name.length <= MAX_FOLDER_NAME) { "folder name too long (max $MAX_FOLDER_NAME)" }
            val keys = apps.map { it.componentKey }
            require(keys.size == keys.toSet().size) { "folder '$name' contains duplicate apps" }
        }

        companion object {
            const val MAX_FOLDER_NAME = 40
        }
    }
}
data class Page(
    val index: Int,
    val items: List<Item> = emptyList(),
)
data class Layout(
    val pages: List<Page> = listOf(Page(0)),
    val gridColumns: Int = 4,
    val gridRows: Int = 6,
    /** Monotonic revision — bumps on every successful mutation. Used by undo and by clients to detect drift. */
    val revision: Long = 0,
) {
    val capacityPerPage: Int get() = gridColumns * gridRows

    fun allItems(): Sequence<Pair<Page, Item>> =
        pages.asSequence().flatMap { p -> p.items.asSequence().map { p to it } }

    fun folders(): List<Item.Folder> = allItems().map { it.second }.filterIsInstance<Item.Folder>().toList()

    fun findItem(id: String): Pair<Page, Item>? = allItems().firstOrNull { it.second.id == id }

    fun findFolder(id: String): Item.Folder? = findItem(id)?.second as? Item.Folder

    fun findFolderByName(name: String): Item.Folder? =
        folders().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Every app placed anywhere on the home screen (top-level or inside folders). */
    fun placedApps(): Set<String> = allItems().flatMap { (_, item) ->
        when (item) {
            is Item.App -> sequenceOf(item.app.componentKey)
            is Item.Folder -> item.apps.asSequence().map { it.componentKey }
        }
    }.toSet()

    /** Where a given app currently lives, if placed. */
    fun locate(componentKey: String): Location? {
        for (page in pages) {
            for (item in page.items) {
                when (item) {
                    is Item.App -> if (item.app.componentKey == componentKey) return Location.TopLevel(page.index, item.id)
                    is Item.Folder -> if (item.apps.any { it.componentKey == componentKey }) return Location.InFolder(page.index, item.id, item.name)
                }
            }
        }
        return null
    }

    /**
     * Structural invariants every Layout must satisfy. The engine checks this after
     * every mutation and refuses to commit a layout that violates it.
     */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (pages.isEmpty()) problems += "layout has no pages"
        pages.forEachIndexed { i, p ->
            if (p.index != i) problems += "page at position $i has index ${p.index}"
            if (p.items.size > capacityPerPage) problems += "page $i has ${p.items.size} items, capacity is $capacityPerPage"
        }
        val ids = allItems().map { it.second.id }.toList()
        if (ids.size != ids.toSet().size) problems += "duplicate item ids"
        val keys = allItems().flatMap { (_, item) ->
            when (item) {
                is Item.App -> sequenceOf(item.app.componentKey)
                is Item.Folder -> item.apps.asSequence().map { it.componentKey }
            }
        }.toList()
        if (keys.size != keys.toSet().size) problems += "an app is placed more than once"
        val names = folders().map { it.name.lowercase() }
        if (names.size != names.toSet().size) problems += "duplicate folder names"
        return problems
    }
}
sealed class Location {
    data class TopLevel(val page: Int, val itemId: String) : Location()
    data class InFolder(val page: Int, val folderId: String, val folderName: String) : Location()
}
