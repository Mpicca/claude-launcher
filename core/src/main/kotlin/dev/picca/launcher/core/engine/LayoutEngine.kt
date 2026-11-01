package dev.picca.launcher.core.engine

import dev.picca.launcher.core.model.AppRef
import dev.picca.launcher.core.model.Item
import dev.picca.launcher.core.model.Layout
import dev.picca.launcher.core.model.Location
import dev.picca.launcher.core.model.Page
import java.util.concurrent.atomic.AtomicLong

/** Source of truth for what is installed. The engine never mutates it. */
fun interface AppCatalog {
    fun installedApps(): List<AppRef>
}

fun interface IdGenerator {
    fun next(prefix: String): String
}

class SequentialIds : IdGenerator {
    private val counter = AtomicLong(0)
    override fun next(prefix: String) = "$prefix-${counter.incrementAndGet()}"
}

/** Thrown for any rejected operation. The layout is left exactly as it was. */
class LayoutException(val code: Code, message: String) : RuntimeException(message) {
    enum class Code { NOT_FOUND, CONFLICT, INVALID, NOT_INSTALLED, NOTHING_TO_UNDO, INVARIANT_VIOLATION }
}

/** Selector for an app: a "package/activity" component key, or a bare package name (resolved against the catalog). */
typealias AppSelector = String

/**
 * Pure, synchronous, transactional layout engine.
 *
 * Every public mutation:
 *  1. computes a candidate layout from the current one (never mutating in place),
 *  2. validates every structural invariant,
 *  3. commits with a bumped revision, pushing the previous layout onto the undo stack.
 *
 * A failed operation throws [LayoutException] and leaves [current] untouched — there is
 * no partial state. [applyPlan] is the bulk version of this: a whole reorganization is
 * one transaction.
 *
 * This class has no I/O. Persistence is the caller's job via [onCommit].
 */
class LayoutEngine(
    initial: Layout,
    private val catalog: AppCatalog,
    private val ids: IdGenerator = SequentialIds(),
    private val undoDepth: Int = 50,
    private val onCommit: (Layout) -> Unit = {},
) {
    @Volatile
    var current: Layout = initial
        private set

    private val undoStack = ArrayDeque<Layout>()
    private val lock = Any()

    val undoAvailable: Int get() = synchronized(lock) { undoStack.size }

    // ---------------------------------------------------------------- queries

    fun installedApps(): List<AppRef> = catalog.installedApps()

    /** Installed apps that are not placed anywhere on the home screen (i.e. only in the drawer). */
    fun unplacedApps(): List<AppRef> {
        val placed = current.placedApps()
        return installedApps().filter { it.componentKey !in placed }
    }

    fun resolve(selector: AppSelector): AppRef {
        val apps = catalog.installedApps()
        apps.firstOrNull { it.componentKey == selector }?.let { return it }
        val byPackage = apps.filter { it.packageName == selector }
        return when (byPackage.size) {
            0 -> {
                val byLabel = apps.filter { it.label.equals(selector, ignoreCase = true) }
                when (byLabel.size) {
                    1 -> byLabel.single()
                    0 -> throw LayoutException(LayoutException.Code.NOT_INSTALLED, "no installed app matches '$selector'")
                    else -> throw LayoutException(
                        LayoutException.Code.INVALID,
                        "label '$selector' is ambiguous; use a package name: ${byLabel.joinToString { it.packageName }}",
                    )
                }
            }
            1 -> byPackage.single()
            else -> throw LayoutException(
                LayoutException.Code.INVALID,
                "package '$selector' has ${byPackage.size} launchable activities; use package/activity: ${byPackage.joinToString { it.componentKey }}",
            )
        }
    }

    private fun resolveFolder(layout: Layout, idOrName: String): Item.Folder =
        layout.findFolder(idOrName)
            ?: layout.findFolderByName(idOrName)
            ?: throw LayoutException(LayoutException.Code.NOT_FOUND, "no folder '$idOrName'")

    // ------------------------------------------------------------- mutations

    fun createFolder(name: String, apps: List<AppSelector> = emptyList(), page: Int? = null): Item.Folder {
        val trimmed = name.trim()
        if (trimmed.isBlank()) throw LayoutException(LayoutException.Code.INVALID, "folder name must not be blank")
        if (trimmed.length > Item.Folder.MAX_FOLDER_NAME) throw LayoutException(LayoutException.Code.INVALID, "folder name too long")
        var created: Item.Folder? = null
        mutate { layout ->
            if (layout.findFolderByName(trimmed) != null) {
                throw LayoutException(LayoutException.Code.CONFLICT, "folder '$trimmed' already exists")
            }
            val refs = apps.map(::resolve).distinctBy { it.componentKey }
            var next = refs.fold(layout) { acc, ref -> withoutApp(acc, ref.componentKey) }
            val folder = Item.Folder(ids.next("folder"), trimmed, refs)
            created = folder
            next = placeTopLevel(next, folder, page)
            next
        }
        return created!!
    }

    fun renameFolder(folder: String, newName: String): Item.Folder {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) throw LayoutException(LayoutException.Code.INVALID, "folder name must not be blank")
        var renamed: Item.Folder? = null
        mutate { layout ->
            val target = resolveFolder(layout, folder)
            layout.findFolderByName(trimmed)?.let { clash ->
                if (clash.id != target.id) throw LayoutException(LayoutException.Code.CONFLICT, "folder '$trimmed' already exists")
            }
            val updated = target.copy(name = trimmed)
            renamed = updated
            replaceItem(layout, updated)
        }
        return renamed!!
    }

    /** Move apps into a folder. Apps may come from the drawer, the top level, or another folder. */
    fun addToFolder(folder: String, apps: List<AppSelector>): Item.Folder {
        if (apps.isEmpty()) throw LayoutException(LayoutException.Code.INVALID, "no apps given")
        var result: Item.Folder? = null
        mutate { layout ->
            val target = resolveFolder(layout, folder)
            val refs = apps.map(::resolve).distinctBy { it.componentKey }
            var next = refs.fold(layout) { acc, ref -> withoutApp(acc, ref.componentKey) }
            val currentFolder = next.findFolder(target.id)!!
            val merged = currentFolder.copy(apps = currentFolder.apps + refs.filter { r -> currentFolder.apps.none { it.componentKey == r.componentKey } })
            result = merged
            next = replaceItem(next, merged)
            next
        }
        return result!!
    }

    /** Move apps out of a folder back onto the top level (first page with room, or a new page). */
    fun removeFromFolder(folder: String, apps: List<AppSelector>): Item.Folder {
        if (apps.isEmpty()) throw LayoutException(LayoutException.Code.INVALID, "no apps given")
        var result: Item.Folder? = null
        mutate { layout ->
            val target = resolveFolder(layout, folder)
            val keys = apps.map { resolve(it).componentKey }.toSet()
            val leaving = target.apps.filter { it.componentKey in keys }
            if (leaving.isEmpty()) throw LayoutException(LayoutException.Code.NOT_FOUND, "none of those apps are in folder '${target.name}'")
            var next = replaceItem(layout, target.copy(apps = target.apps.filterNot { it.componentKey in keys }))
            for (ref in leaving) next = placeTopLevel(next, Item.App(ids.next("app"), ref), null)
            result = next.findFolder(target.id)
            next
        }
        return result!!
    }

    /** Delete the folder; its apps return to the top level. No app is uninstalled or hidden. */
    fun dissolveFolder(folder: String) {
        mutate { layout ->
            val target = resolveFolder(layout, folder)
            var next = removeItem(layout, target.id)
            for (ref in target.apps) next = placeTopLevel(next, Item.App(ids.next("app"), ref), null)
            next
        }
    }

    /** Place an app on the home screen (from the drawer), or move it to another page if already placed. */
    fun placeApp(app: AppSelector, page: Int? = null): Location {
        val ref = resolve(app)
        val committed = mutate { layout ->
            val stripped = withoutApp(layout, ref.componentKey)
            placeTopLevel(stripped, Item.App(ids.next("app"), ref), page)
        }
        return committed.locate(ref.componentKey)!!
    }

    /** Remove an app's icon from the home screen. The app stays installed and in the drawer. */
    fun hideApp(app: AppSelector) {
        val ref = resolve(app)
        mutate { layout ->
            if (layout.locate(ref.componentKey) == null) {
                throw LayoutException(LayoutException.Code.NOT_FOUND, "'${ref.label}' is not on the home screen")
            }
            withoutApp(layout, ref.componentKey)
        }
    }

    fun addPage(): Int {
        val committed = mutate { layout -> layout.copy(pages = layout.pages + Page(layout.pages.size)) }
        return committed.pages.size - 1
    }

    /** Remove a page — only if it is empty, and never the last page. */
    fun removePage(index: Int) {
        mutate { layout ->
            val page = layout.pages.getOrNull(index) ?: throw LayoutException(LayoutException.Code.NOT_FOUND, "no page $index")
            if (page.items.isNotEmpty()) throw LayoutException(LayoutException.Code.CONFLICT, "page $index is not empty")
            if (layout.pages.size == 1) throw LayoutException(LayoutException.Code.CONFLICT, "cannot remove the last page")
            renumber(layout.copy(pages = layout.pages.filterIndexed { i, _ -> i != index }))
        }
    }

    /**
     * Apply an entire organization plan atomically. If any folder in the plan is invalid
     * (unknown app, duplicate name, blank name) the whole plan is rejected and nothing changes.
     *
     * Semantics: for each folder spec, create-or-update a folder of that name and move the listed
     * apps into it. Apps not mentioned keep their current placement. With [replaceExisting] the
     * current home screen is cleared first and only the plan's apps end up placed.
     */
    fun applyPlan(plan: OrganizationPlan, replaceExisting: Boolean = false): Layout {
        if (plan.folders.isEmpty()) throw LayoutException(LayoutException.Code.INVALID, "plan has no folders")
        val names = plan.folders.map { it.name.trim().lowercase() }
        if (names.any { it.isBlank() }) throw LayoutException(LayoutException.Code.INVALID, "plan contains a blank folder name")
        if (names.size != names.toSet().size) throw LayoutException(LayoutException.Code.INVALID, "plan contains duplicate folder names")
        val claimed = mutableMapOf<String, String>()
        for (f in plan.folders) for (sel in f.apps) {
            val key = resolve(sel).componentKey
            claimed.put(key, f.name)?.let { prev ->
                if (prev != f.name) throw LayoutException(LayoutException.Code.INVALID, "'$sel' is listed in both '$prev' and '${f.name}'")
            }
        }
        return mutate { layout ->
            var next = if (replaceExisting) layout.copy(pages = listOf(Page(0))) else layout
            for (spec in plan.folders) {
                val name = spec.name.trim()
                val refs = spec.apps.map(::resolve).distinctBy { it.componentKey }
                next = refs.fold(next) { acc, ref -> withoutApp(acc, ref.componentKey) }
                val existing = next.findFolderByName(name)
                next = if (existing == null) {
                    placeTopLevel(next, Item.Folder(ids.next("folder"), name, refs), null)
                } else {
                    val merged = existing.copy(apps = existing.apps + refs.filter { r -> existing.apps.none { it.componentKey == r.componentKey } })
                    replaceItem(next, merged)
                }
            }
            if (plan.pinToTopLevel.isNotEmpty()) {
                for (sel in plan.pinToTopLevel) {
                    val ref = resolve(sel)
                    next = placeTopLevel(withoutApp(next, ref.componentKey), Item.App(ids.next("app"), ref), null)
                }
            }
            next
        }
    }

    /** Put every installed app on the home screen as a loose icon. Useful for a "show everything" reset. */
    fun placeAllApps() {
        mutate { layout ->
            unplacedApps().fold(layout) { acc, ref -> placeTopLevel(acc, Item.App(ids.next("app"), ref), null) }
        }
    }

    fun undo(): Layout {
        synchronized(lock) {
            val previous = undoStack.removeLastOrNull()
                ?: throw LayoutException(LayoutException.Code.NOTHING_TO_UNDO, "nothing to undo")
            // Keep the revision monotonic so clients never see it go backwards.
            current = previous.copy(revision = current.revision + 1)
            onCommit(current)
            return current
        }
    }

    /** Drop placements that refer to apps no longer installed (e.g. after an uninstall). */
    fun pruneUninstalled(): Int {
        val installed = catalog.installedApps().map { it.componentKey }.toSet()
        val stale = current.placedApps() - installed
        if (stale.isEmpty()) return 0
        mutate { layout -> stale.fold(layout) { acc, key -> withoutApp(acc, key) } }
        return stale.size
    }

    // ------------------------------------------------------------- internals

    private fun mutate(fn: (Layout) -> Layout): Layout {
        synchronized(lock) {
            val before = current
            val candidate = fn(before)
            val problems = candidate.validate()
            if (problems.isNotEmpty()) {
                throw LayoutException(LayoutException.Code.INVARIANT_VIOLATION, "refusing to commit: ${problems.joinToString("; ")}")
            }
            undoStack.addLast(before)
            while (undoStack.size > undoDepth) undoStack.removeFirst()
            current = candidate.copy(revision = before.revision + 1)
            onCommit(current)
            return current
        }
    }

    private fun withoutApp(layout: Layout, componentKey: String): Layout = layout.copy(
        pages = layout.pages.map { page ->
            page.copy(items = page.items.mapNotNull { item ->
                when (item) {
                    is Item.App -> if (item.app.componentKey == componentKey) null else item
                    is Item.Folder -> item.copy(apps = item.apps.filterNot { it.componentKey == componentKey })
                }
            })
        },
    )

    private fun removeItem(layout: Layout, itemId: String): Layout = layout.copy(
        pages = layout.pages.map { p -> p.copy(items = p.items.filterNot { it.id == itemId }) },
    )

    private fun replaceItem(layout: Layout, item: Item): Layout {
        var found = false
        val pages = layout.pages.map { p ->
            p.copy(items = p.items.map { if (it.id == item.id) { found = true; item } else it })
        }
        if (!found) throw LayoutException(LayoutException.Code.NOT_FOUND, "item ${item.id} not found")
        return layout.copy(pages = pages)
    }

    private fun placeTopLevel(layout: Layout, item: Item, preferredPage: Int?): Layout {
        if (preferredPage != null) {
            if (preferredPage < 0) throw LayoutException(LayoutException.Code.INVALID, "page must be >= 0")
            var l = layout
            while (l.pages.size <= preferredPage) l = l.copy(pages = l.pages + Page(l.pages.size))
            val page = l.pages[preferredPage]
            if (page.items.size >= l.capacityPerPage) {
                throw LayoutException(LayoutException.Code.CONFLICT, "page $preferredPage is full (${l.capacityPerPage} slots)")
            }
            return l.copy(pages = l.pages.map { if (it.index == preferredPage) it.copy(items = it.items + item) else it })
        }
        val target = layout.pages.firstOrNull { it.items.size < layout.capacityPerPage }
        return if (target != null) {
            layout.copy(pages = layout.pages.map { if (it.index == target.index) it.copy(items = it.items + item) else it })
        } else {
            layout.copy(pages = layout.pages + Page(layout.pages.size, listOf(item)))
        }
    }

    private fun renumber(layout: Layout) = layout.copy(pages = layout.pages.mapIndexed { i, p -> p.copy(index = i) })
}

/** A whole-home-screen reorganization, typically produced by Claude from the app list. */
data class OrganizationPlan(
    val folders: List<FolderSpec>,
    /** Apps to keep as loose icons at the top level (e.g. Phone, Messages, Camera). */
    val pinToTopLevel: List<AppSelector> = emptyList(),
) {
        data class FolderSpec(val name: String, val apps: List<AppSelector>)
}
