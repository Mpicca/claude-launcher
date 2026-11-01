package dev.picca.launcher.core

import dev.picca.launcher.core.engine.LayoutException
import dev.picca.launcher.core.engine.OrganizationPlan
import dev.picca.launcher.core.model.Layout
import dev.picca.launcher.core.store.InMemoryLayoutStore
import dev.picca.launcher.core.store.LayoutCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CodecTest {
    @Test fun `round trip preserves the layout exactly`() {
        val e = engine()
        e.applyPlan(OrganizationPlan(listOf(OrganizationPlan.FolderSpec("Work", listOf("com.slack", "com.microsoft.teams"))), listOf("Phone")))
        e.placeApp("com.strava", 2)
        val json = LayoutCodec.encode(e.current)
        val back = LayoutCodec.decode(json)
        assertEquals(e.current, back)
        assertEquals(json, LayoutCodec.encode(back))
    }

    @Test fun `empty layout round trips`() {
        assertEquals(Layout(), LayoutCodec.decode(LayoutCodec.encode(Layout())))
    }

    @Test fun `corrupt or invalid files degrade to a fresh layout instead of crashing`() {
        assertEquals(Layout(), LayoutCodec.decodeOrDefault(null))
        assertEquals(Layout(), LayoutCodec.decodeOrDefault(""))
        assertEquals(Layout(), LayoutCodec.decodeOrDefault("{\"format\":99}"))
        assertEquals(Layout(), LayoutCodec.decodeOrDefault("garbage"))
        // Structurally parseable but violates invariants (duplicate app) -> fresh layout.
        val dup = """{"format":1,"revision":5,"pages":[{"index":0,"items":[
            {"type":"app","id":"a","app":{"package":"com.x.y","activity":"com.x.y.Main","label":"Y"}},
            {"type":"app","id":"b","app":{"package":"com.x.y","activity":"com.x.y.Main","label":"Y"}}]}]}"""
        assertEquals(Layout(), LayoutCodec.decodeOrDefault(dup))
    }

    @Test fun `store persists through the engine commit hook`() {
        val store = InMemoryLayoutStore()
        val e = engine(initial = store.load(), onCommit = store::save)
        e.createFolder("A", listOf("com.slack"))
        e.createFolder("B", listOf("com.venmo"))
        assertEquals(2, store.saveCount)
        val reloaded = engine(initial = store.load(), onCommit = store::save)
        assertEquals(e.current, reloaded.current)
        assertEquals(2, reloaded.current.folders().size)
    }
}

/**
 * Randomized stress test: thousands of random operations against the engine. After
 * every single step the layout must validate, the revision must be exactly the number
 * of successful mutations, and no app may be placed twice. Any failure prints the seed.
 */
class FuzzTest {
    @Test fun `random operation storm keeps every invariant`() {
        for (seed in 1L..25L) runStorm(seed, steps = 400)
    }

    private fun runStorm(seed: Long, steps: Int) {
        val rnd = Random(seed)
        val store = InMemoryLayoutStore(Layout(gridColumns = 3, gridRows = 3)) // small pages to force overflow paths
        val e = engine(initial = store.load(), onCommit = store::save)
        var successes = 0L
        val pkgs = FakePhone.apps.map { it.componentKey }
        fun somePkgs() = List(rnd.nextInt(1, 5)) { pkgs.random(rnd) }
        fun folderName() = "F" + rnd.nextInt(0, 12)

        repeat(steps) { step ->
            val before = e.current
            val ok = try {
                when (rnd.nextInt(13)) {
                    0 -> e.createFolder(folderName(), somePkgs(), if (rnd.nextBoolean()) rnd.nextInt(0, 4) else null)
                    1 -> e.renameFolder(folderName(), folderName())
                    2 -> e.addToFolder(folderName(), somePkgs())
                    3 -> e.removeFromFolder(folderName(), somePkgs())
                    4 -> e.dissolveFolder(folderName())
                    5 -> e.placeApp(pkgs.random(rnd), if (rnd.nextBoolean()) rnd.nextInt(0, 5) else null)
                    6 -> e.hideApp(pkgs.random(rnd))
                    7 -> e.addPage()
                    8 -> e.removePage(rnd.nextInt(0, 5))
                    9 -> e.undo()
                    10 -> e.applyPlan(
                        OrganizationPlan(
                            List(rnd.nextInt(1, 4)) { OrganizationPlan.FolderSpec(folderName(), somePkgs()) },
                            if (rnd.nextBoolean()) somePkgs() else emptyList(),
                        ),
                        replaceExisting = rnd.nextInt(5) == 0,
                    )
                    11 -> e.placeAllApps()
                    else -> if (e.pruneUninstalled() == 0) throw LayoutException(LayoutException.Code.NOT_FOUND, "nothing to prune")
                }
                true
            } catch (ex: LayoutException) {
                // Rejections must be side-effect free.
                assertEquals("seed=$seed step=$step: failed op mutated state (${ex.message})", before, e.current)
                false
            }
            if (ok) successes++
            val problems = e.current.validate()
            assertTrue("seed=$seed step=$step: $problems", problems.isEmpty())
            assertEquals("seed=$seed step=$step: revision drift", successes, e.current.revision)
            // Persisted copy always matches live state.
            assertEquals("seed=$seed step=$step: store out of sync", e.current, store.load())
        }
        // And the catalog was never touched.
        assertEquals(FakePhone.apps, FakePhone.installedApps())
    }
}
