package dev.picca.launcher.core

import dev.picca.launcher.core.engine.LayoutException
import dev.picca.launcher.core.mcp.McpServer
import dev.picca.launcher.core.store.InMemoryLayoutStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Simulates the UI thread and several HTTP request threads mutating the engine simultaneously. */
class ConcurrencyTest {
    @Test fun `parallel mutations serialize cleanly with no lost updates or invalid states`() {
        val store = InMemoryLayoutStore()
        val e = engine(initial = store.load(), onCommit = store::save)
        val server = McpServer(e)
        val threads = 8
        val perThread = 150
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val successes = AtomicLong()
        val pkgs = FakePhone.apps.map { it.componentKey }

        repeat(threads) { t ->
            pool.submit {
                start.await()
                repeat(perThread) { i ->
                    try {
                        when (i % 5) {
                            0 -> e.createFolder("T$t-$i", listOf(pkgs[(t * 7 + i) % pkgs.size]))
                            1 -> e.placeApp(pkgs[(t * 11 + i) % pkgs.size])
                            2 -> e.hideApp(pkgs[(t * 13 + i) % pkgs.size])
                            3 -> {
                                val resp = server.handle("""{"jsonrpc":"2.0","id":$i,"method":"tools/call","params":{"name":"add_page","arguments":{}}}""")!!
                                if (!resp.contains("\"isError\":true")) successes.incrementAndGet()
                                throw Skip
                            }
                            else -> e.undo()
                        }
                        successes.incrementAndGet()
                    } catch (_: LayoutException) {
                    } catch (_: Skip) {
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))

        assertTrue(e.current.validate().isEmpty())
        assertEquals(successes.get(), e.current.revision)
        assertEquals(e.current, store.load())
    }

    private object Skip : RuntimeException()
}
