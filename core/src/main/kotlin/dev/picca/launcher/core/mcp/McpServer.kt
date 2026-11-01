package dev.picca.launcher.core.mcp

import dev.picca.launcher.core.engine.LayoutEngine
import dev.picca.launcher.core.engine.LayoutException
import dev.picca.launcher.core.engine.OrganizationPlan
import dev.picca.launcher.core.model.Item
import dev.picca.launcher.core.model.Layout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Transport-agnostic MCP server: takes one JSON-RPC message string, returns the
 * response string (or null for notifications). The Android module wires this to an
 * HTTP endpoint; tests call it directly.
 *
 * Implements the subset of the Model Context Protocol that Claude's clients use:
 * initialize, ping, tools/list, tools/call. Resources/prompts are advertised as absent.
 */
class McpServer(
    private val engine: LayoutEngine,
    private val serverName: String = "claude-launcher",
    private val serverVersion: String = "0.1.0",
) {
    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        val SUPPORTED_PROTOCOL_VERSIONS = setOf("2024-11-05", "2025-03-26", "2025-06-18")
    }

    @Volatile
    var initialized: Boolean = false
        private set

    val tools: List<ToolSpec> = buildTools()

    /** Handle one raw JSON-RPC message. Returns the serialized response, or null for notifications. */
    fun handle(raw: String): String? {
        val req = try {
            JsonRpc.parse(raw)
        } catch (e: RpcError) {
            return JsonRpc.json.encodeToString(JsonObject.serializer(), JsonRpc.error(e.id, e.code, e.message ?: "error", e.data))
        }
        val response: JsonObject? = try {
            dispatch(req)
        } catch (e: RpcError) {
            JsonRpc.error(req.id, e.code, e.message ?: "error", e.data)
        } catch (e: LayoutException) {
            // Domain errors surface as *tool* errors (isError=true) when inside tools/call; here as RPC errors.
            JsonRpc.error(req.id, JsonRpc.INVALID_PARAMS, e.message ?: e.code.name)
        } catch (e: Exception) {
            JsonRpc.error(req.id, JsonRpc.INTERNAL_ERROR, "internal error: ${e::class.simpleName}: ${e.message}")
        }
        if (req.isNotification) return null
        return response?.let { JsonRpc.json.encodeToString(JsonObject.serializer(), it) }
    }

    private fun dispatch(req: JsonRpc.Request): JsonObject? = when (req.method) {
        "initialize" -> JsonRpc.result(req.id, initialize(req.params))
        "notifications/initialized" -> { initialized = true; null }
        "notifications/cancelled", "notifications/roots/list_changed" -> null
        "ping" -> JsonRpc.result(req.id, buildJsonObject { })
        "tools/list" -> JsonRpc.result(req.id, buildJsonObject {
            putJsonArray("tools") { tools.forEach { add(it.toJson()) } }
        })
        "tools/call" -> JsonRpc.result(req.id, callTool(req))
        "resources/list" -> JsonRpc.result(req.id, buildJsonObject { putJsonArray("resources") { } })
        "prompts/list" -> JsonRpc.result(req.id, buildJsonObject { putJsonArray("prompts") { } })
        else -> {
            if (req.isNotification) null
            else throw RpcError(JsonRpc.METHOD_NOT_FOUND, "method not found: ${req.method}", req.id)
        }
    }

    private fun initialize(params: JsonObject?): JsonObject {
        val requested = params?.str("protocolVersion")
        val negotiated = if (requested != null && requested in SUPPORTED_PROTOCOL_VERSIONS) requested else PROTOCOL_VERSION
        return buildJsonObject {
            put("protocolVersion", negotiated)
            putJsonObject("capabilities") {
                putJsonObject("tools") { put("listChanged", false) }
            }
            putJsonObject("serverInfo") {
                put("name", serverName)
                put("version", serverVersion)
            }
            put(
                "instructions",
                "This server controls the home-screen layout of the Claude Launcher app on the user's Android phone. " +
                    "It can only arrange icons and folders inside that launcher; it cannot install, uninstall, disable, " +
                    "or modify any other app, and every change can be reverted with the undo tool. " +
                    "Typical flow: call list_apps, propose an organization, then apply_plan.",
            )
        }
    }

    private fun callTool(req: JsonRpc.Request): JsonObject {
        val params = req.params ?: throw RpcError(JsonRpc.INVALID_PARAMS, "tools/call requires params", req.id)
        val name = params.str("name") ?: throw RpcError(JsonRpc.INVALID_PARAMS, "missing tool name", req.id)
        val args = params.obj("arguments") ?: buildJsonObject { }
        val tool = tools.firstOrNull { it.name == name }
            ?: throw RpcError(JsonRpc.INVALID_PARAMS, "unknown tool: $name", req.id)
        return try {
            val out = tool.handler(args)
            toolResult(out, isError = false)
        } catch (e: LayoutException) {
            toolResult(buildJsonObject { put("error", e.code.name); put("message", e.message ?: "") }, isError = true)
        } catch (e: IllegalArgumentException) {
            toolResult(buildJsonObject { put("error", "INVALID"); put("message", e.message ?: "") }, isError = true)
        }
    }

    private fun toolResult(payload: JsonElement, isError: Boolean): JsonObject = buildJsonObject {
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "text")
                put("text", JsonRpc.json.encodeToString(JsonElement.serializer(), payload))
            })
        }
        put("structuredContent", payload)
        put("isError", isError)
    }

    // ------------------------------------------------------------------ tools

    private fun layoutJson(layout: Layout = engine.current): JsonObject = buildJsonObject {
        put("revision", layout.revision)
        put("gridColumns", layout.gridColumns)
        put("gridRows", layout.gridRows)
        put("undoAvailable", engine.undoAvailable)
        putJsonArray("pages") {
            layout.pages.forEach { page ->
                add(buildJsonObject {
                    put("index", page.index)
                    putJsonArray("items") {
                        page.items.forEach { item ->
                            add(when (item) {
                                is Item.App -> buildJsonObject {
                                    put("type", "app")
                                    put("id", item.id)
                                    put("label", item.app.label)
                                    put("package", item.app.packageName)
                                    put("component", item.app.componentKey)
                                }
                                is Item.Folder -> buildJsonObject {
                                    put("type", "folder")
                                    put("id", item.id)
                                    put("name", item.name)
                                    putJsonArray("apps") {
                                        item.apps.forEach { a ->
                                            add(buildJsonObject {
                                                put("label", a.label); put("package", a.packageName); put("component", a.componentKey)
                                            })
                                        }
                                    }
                                }
                            })
                        }
                    }
                })
            }
        }
    }

    private fun folderJson(f: Item.Folder) = buildJsonObject {
        put("id", f.id); put("name", f.name); put("appCount", f.apps.size)
        putJsonArray("apps") { f.apps.forEach { add(it.label) } }
    }

    private fun ok(extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        extra() // may perform the mutation — so read the revision only afterwards
        put("ok", true)
        put("revision", engine.current.revision)
    }

    private fun required(args: JsonObject, key: String): String =
        args.str(key)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("missing required argument '$key'")

    private fun requiredList(args: JsonObject, key: String): List<String> =
        args.strList(key)?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("missing required array argument '$key'")

    private fun buildTools(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "list_apps",
            description = "List every launchable app installed on the phone, with its label, package name, " +
                "component key, and where it currently sits in the launcher (top-level page, inside a folder, or unplaced/drawer-only). " +
                "Call this first before proposing an organization.",
            schema = schema { },
            readOnly = true,
        ) { _ ->
            val layout = engine.current
            buildJsonObject {
                put("count", engine.installedApps().size)
                putJsonArray("apps") {
                    engine.installedApps().sortedBy { it.label.lowercase() }.forEach { a ->
                        add(buildJsonObject {
                            put("label", a.label)
                            put("package", a.packageName)
                            put("component", a.componentKey)
                            val loc = layout.locate(a.componentKey)
                            put("placement", when (loc) {
                                null -> "drawer_only"
                                is dev.picca.launcher.core.model.Location.TopLevel -> "page_${loc.page}"
                                is dev.picca.launcher.core.model.Location.InFolder -> "folder:${loc.folderName}"
                            })
                        })
                    }
                }
            }
        },
        ToolSpec(
            name = "get_layout",
            description = "Return the full current home-screen layout: pages, loose app icons, and folders with their contents. Includes the revision number and how many undo steps are available.",
            schema = schema { },
            readOnly = true,
        ) { _ -> layoutJson() },
        ToolSpec(
            name = "create_folder",
            description = "Create a new home-screen folder, optionally moving apps into it. Apps may be given by package name, 'package/activity' component, or exact label. Fails if a folder with that name exists.",
            schema = schema {
                prop("name", "string", "Folder name (1-40 chars)", required = true)
                arrayProp("apps", "Apps to put in the folder (package names or component keys)")
                prop("page", "integer", "Page index to place the folder on; omitted = first page with room")
            },
        ) { a ->
            val f = engine.createFolder(required(a, "name"), a.strList("apps") ?: emptyList(), a.int("page"))
            ok { put("folder", folderJson(f)) }
        },
        ToolSpec(
            name = "rename_folder",
            description = "Rename an existing folder (identified by id or current name).",
            schema = schema {
                prop("folder", "string", "Folder id or current name", required = true)
                prop("new_name", "string", "New folder name", required = true)
            },
        ) { a -> ok { put("folder", folderJson(engine.renameFolder(required(a, "folder"), required(a, "new_name")))) } },
        ToolSpec(
            name = "add_to_folder",
            description = "Move apps into a folder. Apps can come from the drawer, the top level, or another folder.",
            schema = schema {
                prop("folder", "string", "Folder id or name", required = true)
                arrayProp("apps", "Apps to move in (package names or component keys)", required = true)
            },
        ) { a -> ok { put("folder", folderJson(engine.addToFolder(required(a, "folder"), requiredList(a, "apps")))) } },
        ToolSpec(
            name = "remove_from_folder",
            description = "Move apps out of a folder back onto the home screen as loose icons.",
            schema = schema {
                prop("folder", "string", "Folder id or name", required = true)
                arrayProp("apps", "Apps to move out", required = true)
            },
        ) { a -> ok { put("folder", folderJson(engine.removeFromFolder(required(a, "folder"), requiredList(a, "apps")))) } },
        ToolSpec(
            name = "dissolve_folder",
            description = "Delete a folder. Its apps return to the home screen as loose icons. Nothing is uninstalled.",
            schema = schema { prop("folder", "string", "Folder id or name", required = true) },
        ) { a -> engine.dissolveFolder(required(a, "folder")); ok() },
        ToolSpec(
            name = "place_app",
            description = "Put an app on the home screen as a loose icon (from the drawer or out of a folder), optionally on a specific page.",
            schema = schema {
                prop("app", "string", "Package name, component key, or label", required = true)
                prop("page", "integer", "Target page index")
            },
        ) { a -> engine.placeApp(required(a, "app"), a.int("page")); ok { put("layout", layoutJson()) } },
        ToolSpec(
            name = "hide_app",
            description = "Remove an app's icon from the home screen. The app stays installed and remains in the app drawer — this is purely cosmetic.",
            schema = schema { prop("app", "string", "Package name, component key, or label", required = true) },
        ) { a -> engine.hideApp(required(a, "app")); ok() },
        ToolSpec(
            name = "apply_plan",
            description = "Apply a whole organization plan in one atomic step: a list of folders, each with the apps that belong in it, " +
                "plus optional apps to keep as loose top-level icons. Existing folders with matching names are merged into. " +
                "If any app or name is invalid the entire plan is rejected and nothing changes. " +
                "Set replace_existing=true to clear the current home screen first so only the plan's apps are placed.",
            schema = schema {
                arrayOfObjects("folders", "Folders to create/merge", required = true) {
                    prop("name", "string", "Folder name", required = true)
                    arrayProp("apps", "Package names or component keys", required = true)
                }
                arrayProp("pin_to_top_level", "Apps to keep as loose icons on the home screen (e.g. Phone, Camera)")
                prop("replace_existing", "boolean", "Clear the current home screen before applying (default false)")
            },
        ) { a ->
            val foldersEl = a["folders"] as? kotlinx.serialization.json.JsonArray
                ?: throw IllegalArgumentException("missing required array argument 'folders'")
            val folders = foldersEl.map { el ->
                val o = el as? JsonObject ?: throw IllegalArgumentException("each folder must be an object")
                OrganizationPlan.FolderSpec(required(o, "name"), requiredList(o, "apps"))
            }
            val plan = OrganizationPlan(folders, a.strList("pin_to_top_level") ?: emptyList())
            val layout = engine.applyPlan(plan, a.bool("replace_existing") ?: false)
            ok { put("foldersApplied", folders.size); put("layout", layoutJson(layout)) }
        },
        ToolSpec(
            name = "add_page",
            description = "Add an empty home-screen page. Returns the new page index.",
            schema = schema { },
        ) { _ -> val idx = engine.addPage(); ok { put("page", idx) } },
        ToolSpec(
            name = "remove_page",
            description = "Remove an empty home-screen page (cannot remove a page with items, or the last page).",
            schema = schema { prop("page", "integer", "Page index", required = true) },
        ) { a -> engine.removePage(a.int("page") ?: throw IllegalArgumentException("missing 'page'")); ok() },
        ToolSpec(
            name = "show_all_apps",
            description = "Place every installed app that isn't already on the home screen as a loose icon (adds pages as needed).",
            schema = schema { },
        ) { _ -> engine.placeAllApps(); ok { put("layout", layoutJson()) } },
        ToolSpec(
            name = "undo",
            description = "Revert the most recent layout change. Up to 50 steps are kept.",
            schema = schema { },
        ) { _ -> ok { put("layout", layoutJson(engine.undo())) } },
        ToolSpec(
            name = "safety_info",
            description = "Describe exactly what this server can and cannot do to the phone.",
            schema = schema { },
            readOnly = true,
        ) { _ ->
            buildJsonObject {
                putJsonArray("can") {
                    add("create, rename, and dissolve folders inside the Claude Launcher home screen")
                    add("move app icons between pages and folders inside the Claude Launcher")
                    add("hide an icon from the Claude Launcher home screen (app stays installed and in the drawer)")
                    add("undo the last 50 layout changes")
                }
                putJsonArray("cannot") {
                    add("install, uninstall, disable, force-stop, or update any app")
                    add("change app permissions, data, settings, or notifications")
                    add("modify the layout of any other launcher (Pixel Launcher, One UI Home, etc.)")
                    add("read app contents, messages, files, or accounts")
                }
                put("note", "Switching your default launcher back to the stock one restores your previous home screen exactly; this app's layout is stored separately.")
            }
        },
    )
}

class ToolSpec(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val readOnly: Boolean = false,
    val handler: (JsonObject) -> JsonElement,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", schema)
        putJsonObject("annotations") {
            put("readOnlyHint", readOnly)
            put("destructiveHint", false) // every mutation is undoable and cosmetic
            put("idempotentHint", readOnly)
            put("openWorldHint", false)
        }
    }
}

// --------------------------------------------------- tiny JSON-schema builder

internal class SchemaBuilder {
    private val props = mutableMapOf<String, JsonElement>()
    private val req = mutableListOf<String>()

    fun prop(name: String, type: String, description: String, required: Boolean = false) {
        props[name] = buildJsonObject { put("type", type); put("description", description) }
        if (required) req += name
    }

    fun arrayProp(name: String, description: String, required: Boolean = false) {
        props[name] = buildJsonObject {
            put("type", "array"); put("description", description)
            putJsonObject("items") { put("type", "string") }
        }
        if (required) req += name
    }

    fun arrayOfObjects(name: String, description: String, required: Boolean = false, items: SchemaBuilder.() -> Unit) {
        props[name] = buildJsonObject {
            put("type", "array"); put("description", description)
            put("items", SchemaBuilder().apply(items).build())
        }
        if (required) req += name
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        if (req.isNotEmpty()) putJsonArray("required") { req.forEach { add(it) } }
        put("additionalProperties", false)
    }
}

internal fun schema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()
