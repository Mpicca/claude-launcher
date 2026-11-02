package dev.picca.launcher.hands

import dev.picca.launcher.core.mcp.McpServer
import dev.picca.launcher.core.mcp.ToolSpec
import dev.picca.launcher.core.mcp.schema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** MCP tools that expose [HandsService] so Claude can drive One UI Home (or any app) remotely. */
object HandsTools {

    const val INSTRUCTIONS =
        "The hands_* tools drive the phone's screen through an accessibility service (enabled by the user in " +
            "Settings → Accessibility → Claude Launcher Hands). Use them to organize apps inside Samsung One UI Home, " +
            "which has no API: hands_open_drawer, then look (hands_screenshot / hands_dump), then act " +
            "(hands_click_text, hands_long_press, hands_swipe, hands_type, hands_key). Always look before acting, " +
            "and act one step at a time; One UI needs ~300–800 ms between steps."

    private fun hands(): HandsService = HandsService.current
        ?: throw IllegalArgumentException("Hands accessibility service is not enabled. On the phone: Settings → Accessibility → Installed apps → Claude Launcher Hands → On (sideloaded apps may first need App info → ⋮ → Allow restricted settings).")

    private fun JsonObject.num(key: String): Float? = (this[key] as? JsonPrimitive)?.content?.toFloatOrNull()
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
    private fun JsonObject.req(key: String): Float = num(key) ?: throw IllegalArgumentException("missing numeric argument '$key'")

    private fun settle(ms: Long) = Thread.sleep(ms)

    val tools: List<ToolSpec> = listOf(
        ToolSpec(
            name = "hands_status",
            description = "Whether the accessibility 'hands' are enabled, which app is in the foreground, and the screen size in pixels.",
            schema = schema { }, readOnly = true,
        ) { _ ->
            val h = HandsService.current
            buildJsonObject {
                put("enabled", h != null)
                if (h != null) {
                    val (w, hgt) = h.screenSize()
                    put("foreground", h.foregroundPackage()); put("width", w); put("height", hgt)
                } else put("how_to_enable", "Settings → Accessibility → Installed apps → Claude Launcher Hands → On")
            }
        },
        ToolSpec(
            name = "hands_screenshot",
            description = "Take a screenshot of the phone (scaled to ~540px wide). Coordinates for hands_tap etc. are in FULL screen pixels: multiply by width/540.",
            schema = schema { prop("width", "integer", "Target width in px (default 540)") }, readOnly = true,
        ) { a ->
            val h = hands(); val (w, hgt) = h.screenSize()
            val target = a.num("width")?.toInt() ?: 540
            buildJsonObject {
                put("screen_width", w); put("screen_height", hgt); put("image_width", target)
                put("scale_to_screen", w.toFloat() / target)
                put("foreground", h.foregroundPackage())
                put(McpServer.IMAGE_KEY, h.screenshotBase64(target)); put(McpServer.IMAGE_MIME_KEY, "image/jpeg")
            }
        },
        ToolSpec(
            name = "hands_dump",
            description = "Dump the visible UI tree: one line per node with class, text, content-desc, view id, screen bounds [l,t,r,b] and flags (C clickable, L long-clickable, K checkable, ✓ checked, S scrollable, E editable, * selected, F focused).",
            schema = schema {
                prop("filter", "string", "Only include lines containing this text (case-insensitive)")
                prop("max_nodes", "integer", "Cap on nodes returned (default 400)")
            }, readOnly = true,
        ) { a ->
            val h = hands()
            buildJsonObject { put("foreground", h.foregroundPackage()); put("nodes", h.dump(a.num("max_nodes")?.toInt() ?: 400, a.str("filter"))) }
        },
        ToolSpec(
            name = "hands_tap",
            description = "Tap at screen coordinates (full-resolution pixels).",
            schema = schema { prop("x", "number", "x", true); prop("y", "number", "y", true) }, destructive = true,
        ) { a -> val ok = hands().tap(a.req("x"), a.req("y")); settle(500); buildJsonObject { put("ok", ok) } },
        ToolSpec(
            name = "hands_long_press",
            description = "Long-press at screen coordinates (default 900 ms) — opens One UI's icon menu (Select / Add to Home / …).",
            schema = schema { prop("x", "number", "x", true); prop("y", "number", "y", true); prop("ms", "integer", "hold duration ms") }, destructive = true,
        ) { a -> val ok = hands().longPress(a.req("x"), a.req("y"), a.num("ms")?.toLong() ?: 900); settle(700); buildJsonObject { put("ok", ok) } },
        ToolSpec(
            name = "hands_swipe",
            description = "Swipe from (x1,y1) to (x2,y2) over `ms` milliseconds (default 300). Use for paging the app drawer.",
            schema = schema {
                prop("x1", "number", "", true); prop("y1", "number", "", true); prop("x2", "number", "", true); prop("y2", "number", "", true)
                prop("ms", "integer", "duration ms")
            }, destructive = true,
        ) { a -> val ok = hands().swipe(a.req("x1"), a.req("y1"), a.req("x2"), a.req("y2"), a.num("ms")?.toLong() ?: 300); settle(600); buildJsonObject { put("ok", ok) } },
        ToolSpec(
            name = "hands_click_text",
            description = "Click (or long-press) the visible element whose text/content-description matches. Substring match unless exact=true; `index` picks among several matches.",
            schema = schema {
                prop("text", "string", "text or content-description to match", true)
                prop("exact", "boolean", "exact match (default false)")
                prop("index", "integer", "which match (default 0)")
                prop("long", "boolean", "long-press instead of click (default false)")
            }, destructive = true,
        ) { a ->
            val r = hands().clickText(a.str("text") ?: throw IllegalArgumentException("missing 'text'"), a.bool("exact") ?: false, a.num("index")?.toInt() ?: 0, a.bool("long") ?: false)
            settle(600); buildJsonObject { put("result", r) }
        },
        ToolSpec(
            name = "hands_find",
            description = "List visible elements matching text/content-description, with their screen bounds.",
            schema = schema { prop("text", "string", "text to match (substring)", true) }, readOnly = true,
        ) { a ->
            val nodes = hands().find(a.str("text") ?: throw IllegalArgumentException("missing 'text'"))
            buildJsonObject {
                put("count", nodes.size)
                put("matches", nodes.take(30).joinToString("\n") { n ->
                    val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                    "${n.className?.toString()?.substringAfterLast('.')} text=\"${n.text}\" desc=\"${n.contentDescription}\" [${r.left},${r.top},${r.right},${r.bottom}] center=(${r.centerX()},${r.centerY()})"
                })
            }
        },
        ToolSpec(
            name = "hands_type",
            description = "Set the text of the focused (or first) editable field on screen, e.g. a folder name.",
            schema = schema { prop("text", "string", "text to enter", true) }, destructive = true,
        ) { a -> buildJsonObject { put("result", hands().setText(a.str("text") ?: "")) } },
        ToolSpec(
            name = "hands_key",
            description = "Press a system key: back, home, or recents.",
            schema = schema { prop("key", "string", "back | home | recents", true) }, destructive = true,
        ) { a -> val ok = hands().key(a.str("key") ?: ""); settle(500); buildJsonObject { put("ok", ok) } },
        ToolSpec(
            name = "hands_open_drawer",
            description = "Go to the system home screen and swipe up to open One UI's app drawer. Returns the foreground package afterwards.",
            schema = schema { }, destructive = true,
        ) { _ ->
            val h = hands(); val (w, hgt) = h.screenSize()
            h.key("home"); settle(800)
            h.swipe(w / 2f, hgt * 0.85f, w / 2f, hgt * 0.25f, 250); settle(900)
            buildJsonObject { put("foreground", h.foregroundPackage()) }
        },
    )
}
