package dev.picca.launcher.core

import dev.picca.launcher.core.engine.LayoutException.Code
import dev.picca.launcher.core.engine.OrganizationPlan
import dev.picca.launcher.core.engine.OrganizationPlan.FolderSpec
import dev.picca.launcher.core.model.Item
import dev.picca.launcher.core.model.Layout
import dev.picca.launcher.core.model.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEngineTest {

    // ---------------------------------------------------------------- folders

    @Test fun `create folder with apps moves them off the drawer`() {
        val e = engine()
        val f = e.createFolder("Social", listOf("com.instagram.android", "com.twitter.android", "com.reddit.frontpage"))
        assertEquals("Social", f.name)
        assertEquals(listOf("Instagram", "X", "Reddit"), f.apps.map { it.label })
        assertEquals(1, e.current.folders().size)
        val loc = e.current.locate(FakePhone.key("com.instagram.android"))
        assertTrue(loc is Location.InFolder && loc.folderName == "Social")
        assertEquals(1L, e.current.revision)
    }

    @Test fun `folder names are unique case-insensitively`() {
        val e = engine()
        e.createFolder("Work")
        expectFailure(Code.CONFLICT) { e.createFolder("work") }
        expectFailure(Code.CONFLICT) { e.createFolder("  WORK ") }
        assertEquals(1, e.current.folders().size)
        assertEquals(1L, e.current.revision)
    }

    @Test fun `blank or oversized folder names are rejected without side effects`() {
        val e = engine()
        expectFailure(Code.INVALID) { e.createFolder("   ") }
        expectFailure(Code.INVALID) { e.createFolder("x".repeat(41)) }
        assertEquals(0L, e.current.revision)
        assertEquals(0, e.undoAvailable)
    }

    @Test fun `rename folder, by id and by name`() {
        val e = engine()
        val f = e.createFolder("Misc")
        assertEquals("Tools", e.renameFolder(f.id, "Tools").name)
        assertEquals("Utilities", e.renameFolder("tools", "Utilities").name)
        assertNull(e.current.findFolderByName("Tools"))
        assertNotNull(e.current.findFolderByName("Utilities"))
    }

    @Test fun `rename to same name different case is allowed, to another folder's name is not`() {
        val e = engine()
        e.createFolder("Games")
        val f = e.createFolder("Media")
        assertEquals("MEDIA", e.renameFolder(f.id, "MEDIA").name)
        expectFailure(Code.CONFLICT) { e.renameFolder(f.id, "games") }
    }

    @Test fun `add to folder pulls from top level and from other folders`() {
        val e = engine()
        e.placeApp("com.spotify.music")
        val media = e.createFolder("Media", listOf("com.netflix.mediaclient"))
        e.createFolder("Music", listOf("com.spotify.music"))
        // Spotify: top-level -> Music; now move it into Media from Music.
        val merged = e.addToFolder(media.id, listOf("com.spotify.music", "com.google.android.youtube"))
        assertEquals(listOf("Netflix", "Spotify", "YouTube"), merged.apps.map { it.label })
        assertEquals(0, e.current.findFolderByName("Music")!!.apps.size)
        assertEquals(1, e.current.placedApps().count { it == FakePhone.key("com.spotify.music") })
    }

    @Test fun `adding an app already in the folder is a no-op for that app`() {
        val e = engine()
        val f = e.createFolder("Finance", listOf("com.venmo"))
        val f2 = e.addToFolder(f.id, listOf("com.venmo", "com.chase.sig.android"))
        assertEquals(listOf("Venmo", "Chase"), f2.apps.map { it.label })
    }

    @Test fun `remove from folder returns apps to top level`() {
        val e = engine()
        val f = e.createFolder("Finance", listOf("com.venmo", "com.chase.sig.android", "com.robinhood.android"))
        val after = e.removeFromFolder("Finance", listOf("com.venmo"))
        assertEquals(listOf("Chase", "Robinhood"), after.apps.map { it.label })
        assertTrue(e.current.locate(FakePhone.key("com.venmo")) is Location.TopLevel)
        expectFailure(Code.NOT_FOUND) { e.removeFromFolder(f.id, listOf("com.ubercab")) }
    }

    @Test fun `dissolve folder keeps every app placed`() {
        val e = engine()
        e.createFolder("Travel", listOf("com.ubercab", "com.airbnb.android", "com.google.android.apps.maps"))
        val before = e.current.placedApps()
        e.dissolveFolder("Travel")
        assertEquals(before, e.current.placedApps())
        assertTrue(e.current.folders().isEmpty())
        assertEquals(3, e.current.pages[0].items.size)
        expectFailure(Code.NOT_FOUND) { e.dissolveFolder("Travel") }
    }

    // ----------------------------------------------------------- app placement

    @Test fun `resolve by package, by component, by label and ambiguity is an error`() {
        val e = engine()
        assertEquals("Phone", e.resolve("com.android.dialer").label)
        assertEquals("Phone", e.resolve("com.android.dialer/com.android.dialer.MainActivity").label)
        assertEquals("Phone", e.resolve("phone").label)
        expectFailure(Code.NOT_INSTALLED) { e.resolve("com.does.not.exist") }
        // Samsung Notes has two activities under one package.
        expectFailure(Code.INVALID) { e.resolve("com.samsung.android.app.notes") }
        assertEquals("Samsung Notes", e.resolve("com.samsung.android.app.notes/com.samsung.android.app.notes.MainActivity").label)
    }

    @Test fun `place app on explicit page creates intermediate pages`() {
        val e = engine()
        val loc = e.placeApp("com.android.chrome", page = 2)
        assertEquals(3, e.current.pages.size)
        assertTrue(loc is Location.TopLevel && loc.page == 2)
        expectFailure(Code.INVALID) { e.placeApp("com.android.chrome", page = -1) }
    }

    @Test fun `placing an already placed app moves it instead of duplicating`() {
        val e = engine()
        e.placeApp("com.android.chrome", page = 0)
        e.placeApp("com.android.chrome", page = 1)
        assertEquals(1, e.current.placedApps().size)
        assertEquals(0, e.current.pages[0].items.size)
        assertEquals(1, e.current.pages[1].items.size)
    }

    @Test fun `hide app removes icon but app is still in catalog and resolvable`() {
        val e = engine()
        e.placeApp("com.android.chrome")
        e.hideApp("Chrome")
        assertNull(e.current.locate(FakePhone.key("com.android.chrome")))
        assertEquals("Chrome", e.resolve("com.android.chrome").label) // still installed
        expectFailure(Code.NOT_FOUND) { e.hideApp("Chrome") }
    }

    @Test fun `unplaced apps are exactly the catalog minus placed`() {
        val e = engine()
        assertEquals(FakePhone.apps.size, e.unplacedApps().size)
        e.createFolder("A", listOf("com.venmo", "com.strava"))
        e.placeApp("com.slack")
        assertEquals(FakePhone.apps.size - 3, e.unplacedApps().size)
        assertTrue(e.unplacedApps().none { it.label in setOf("Venmo", "Strava", "Slack") })
    }

    @Test fun `show all apps fills pages and overflows onto new ones`() {
        val e = engine(Layout(gridColumns = 3, gridRows = 3)) // 9 per page
        e.placeAllApps()
        assertEquals(FakePhone.apps.size, e.current.placedApps().size)
        val expectedPages = (FakePhone.apps.size + 8) / 9
        assertEquals(expectedPages, e.current.pages.size)
        assertTrue(e.current.pages.all { it.items.size <= 9 })
    }

    // ------------------------------------------------------------------ pages

    @Test fun `page capacity is enforced on explicit placement`() {
        val e = engine(Layout(gridColumns = 2, gridRows = 2))
        e.placeApp("com.venmo", 0); e.placeApp("com.slack", 0); e.placeApp("com.strava", 0); e.placeApp("com.ubercab", 0)
        expectFailure(Code.CONFLICT) { e.placeApp("com.chase.sig.android", 0) }
        e.placeApp("com.chase.sig.android") // no page given → spills to page 1
        assertEquals(2, e.current.pages.size)
    }

    @Test fun `remove page rules`() {
        val e = engine()
        expectFailure(Code.CONFLICT) { e.removePage(0) }
        val idx = e.addPage()
        assertEquals(1, idx)
        e.placeApp("com.venmo", 1)
        expectFailure(Code.CONFLICT) { e.removePage(1) }
        expectFailure(Code.NOT_FOUND) { e.removePage(7) }
        e.hideApp("com.venmo")
        e.removePage(1)
        assertEquals(1, e.current.pages.size)
        assertEquals(listOf(0), e.current.pages.map { it.index })
    }

    @Test fun `removing a middle page renumbers the rest`() {
        val e = engine()
        e.addPage(); e.addPage()
        e.placeApp("com.venmo", 2)
        e.removePage(1)
        assertEquals(listOf(0, 1), e.current.pages.map { it.index })
        assertEquals(1, (e.current.locate(FakePhone.key("com.venmo")) as Location.TopLevel).page)
    }

    // ------------------------------------------------------------------- plans

    private val plan = OrganizationPlan(
        folders = listOf(
            FolderSpec("Social", listOf("com.instagram.android", "com.twitter.android", "com.reddit.frontpage", "com.whatsapp")),
            FolderSpec("Work", listOf("com.slack", "com.microsoft.teams", "com.microsoft.office.outlook", "com.databricks.mobile")),
            FolderSpec("Finance", listOf("com.chase.sig.android", "com.venmo", "com.robinhood.android")),
            FolderSpec("Media", listOf("com.google.android.youtube", "com.spotify.music", "com.netflix.mediaclient")),
            FolderSpec("Travel", listOf("com.ubercab", "com.airbnb.android", "com.google.android.apps.maps")),
            FolderSpec("Fitness", listOf("com.strava", "com.fitbit.FitbitMobile")),
        ),
        pinToTopLevel = listOf("com.android.dialer", "com.google.android.apps.messaging", "com.android.camera2", "com.android.chrome"),
    )

    @Test fun `apply plan builds folders and pins in one revision`() {
        val e = engine()
        val layout = e.applyPlan(plan)
        assertEquals(1L, layout.revision)
        assertEquals(6, layout.folders().size)
        assertEquals(4, layout.folders().first { it.name == "Social" }.apps.size)
        assertEquals(23, layout.placedApps().size) // 19 in folders + 4 pinned
        assertTrue(layout.locate(FakePhone.key("com.android.dialer")) is Location.TopLevel)
        assertEquals(1, e.undoAvailable)
    }

    @Test fun `apply plan merges into existing folders and re-homes moved apps`() {
        val e = engine()
        e.createFolder("Work", listOf("com.slack", "com.venmo")) // Venmo wrongly in Work
        e.applyPlan(plan)
        val work = e.current.findFolderByName("Work")!!
        val finance = e.current.findFolderByName("Finance")!!
        assertEquals(listOf("Slack", "Teams", "Outlook", "Databricks"), work.apps.map { it.label })
        assertTrue(finance.apps.any { it.label == "Venmo" })
        assertEquals(6, e.current.folders().size)
    }

    @Test fun `apply plan is atomic - one bad app rejects everything`() {
        val e = engine()
        e.createFolder("Keep", listOf("com.strava"))
        val snapshot = e.current
        val bad = OrganizationPlan(plan.folders + FolderSpec("Broken", listOf("com.google.android.calculator", "com.not.installed")))
        expectFailure(Code.NOT_INSTALLED) { e.applyPlan(bad) }
        assertEquals(snapshot, e.current)
        assertEquals(1, e.undoAvailable)
    }

    @Test fun `apply plan rejects an app listed in two folders`() {
        val e = engine()
        val bad = OrganizationPlan(listOf(FolderSpec("A", listOf("com.slack")), FolderSpec("B", listOf("com.slack"))))
        val err = expectFailure(Code.INVALID) { e.applyPlan(bad) }
        assertTrue(err.message!!.contains("both"))
        assertEquals(0L, e.current.revision)
    }

    @Test fun `apply plan rejects duplicate and blank names`() {
        val e = engine()
        expectFailure(Code.INVALID) { e.applyPlan(OrganizationPlan(listOf(FolderSpec("A", listOf("com.slack")), FolderSpec("a ", listOf("com.venmo"))))) }
        expectFailure(Code.INVALID) { e.applyPlan(OrganizationPlan(listOf(FolderSpec(" ", listOf("com.slack"))))) }
        expectFailure(Code.INVALID) { e.applyPlan(OrganizationPlan(emptyList())) }
        assertEquals(0L, e.current.revision)
    }

    @Test fun `apply plan with replace_existing clears unrelated placements`() {
        val e = engine()
        e.placeApp("com.nianticlabs.pokemongo")
        e.createFolder("Old", listOf("com.google.android.calculator"))
        e.applyPlan(plan, replaceExisting = true)
        assertNull(e.current.locate(FakePhone.key("com.nianticlabs.pokemongo")))
        assertNull(e.current.findFolderByName("Old"))
        assertEquals(6, e.current.folders().size)
    }

    // -------------------------------------------------------------------- undo

    @Test fun `undo restores previous layout exactly and keeps revision monotonic`() {
        val e = engine()
        e.createFolder("A", listOf("com.slack"))
        val afterA = e.current
        e.createFolder("B", listOf("com.venmo"))
        val undone = e.undo()
        assertEquals(afterA.pages, undone.pages)
        assertEquals(3L, undone.revision)
        assertEquals(1, e.undoAvailable)
        e.undo()
        assertEquals(Layout().pages, e.current.pages)
        expectFailure(Code.NOTHING_TO_UNDO) { e.undo() }
    }

    @Test fun `undo depth is bounded`() {
        val e = engine()
        repeat(60) { e.addPage() }
        assertEquals(50, e.undoAvailable)
    }

    @Test fun `undo of apply plan reverts the whole plan`() {
        val e = engine()
        e.applyPlan(plan)
        e.undo()
        assertEquals(0, e.current.folders().size)
        assertEquals(0, e.current.placedApps().size)
    }

    // --------------------------------------------------------------- integrity

    @Test fun `every commit is persisted and every persisted layout validates`() {
        val saved = mutableListOf<Layout>()
        val e = engine(onCommit = { saved += it })
        e.applyPlan(plan)
        e.renameFolder("Media", "Entertainment")
        e.undo()
        e.hideApp("com.android.chrome")
        assertEquals(4, saved.size)
        assertTrue(saved.all { it.validate().isEmpty() })
        assertEquals(listOf(1L, 2L, 3L, 4L), saved.map { it.revision })
    }

    @Test fun `prune drops apps that were uninstalled`() {
        val remaining = FakePhone.apps.filter { it.packageName != "com.strava" }
        val e = engine(catalog = { remaining }).also { it.placeApp("com.slack") }
        // Simulate: layout still references Strava (uninstalled since).
        val withStale = e.current.copy(
            pages = listOf(e.current.pages[0].copy(items = e.current.pages[0].items + Item.App("stale", FakePhone.apps.first { it.label == "Strava" }))),
        )
        val e2 = engine(initial = withStale, catalog = { remaining })
        assertEquals(1, e2.pruneUninstalled())
        assertEquals(0, e2.pruneUninstalled())
        assertEquals(1, e2.current.placedApps().size)
    }

    @Test fun `engine never mutates the catalog`() {
        val before = FakePhone.apps.toList()
        val e = engine()
        e.applyPlan(plan); e.hideApp("com.android.dialer"); e.dissolveFolder("Social"); e.undo(); e.placeAllApps()
        assertEquals(before, FakePhone.installedApps())
    }
}
