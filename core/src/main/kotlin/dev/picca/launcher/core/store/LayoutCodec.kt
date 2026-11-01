package dev.picca.launcher.core.store

import dev.picca.launcher.core.mcp.JsonRpc
import dev.picca.launcher.core.model.AppRef
import dev.picca.launcher.core.model.Item
import dev.picca.launcher.core.model.Layout
import dev.picca.launcher.core.model.Page
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Explicit JSON codec for the layout (format version 1). Hand-written rather than
 * annotation-generated so the on-disk format is stable, readable, and independent of
 * library versions — the launcher must never fail to boot because of a schema drift.
 */
object LayoutCodec {
    const val FORMAT_VERSION = 1

    fun encode(layout: Layout): String = JsonRpc.json.encodeToString(JsonObject.serializer(), toJson(layout))

    fun toJson(layout: Layout): JsonObject = buildJsonObject {
        put("format", FORMAT_VERSION)
        put("revision", layout.revision)
        put("gridColumns", layout.gridColumns)
        put("gridRows", layout.gridRows)
        putJsonArray("pages") {
            layout.pages.forEach { page ->
                add(buildJsonObject {
                    put("index", page.index)
                    putJsonArray("items") { page.items.forEach { add(itemJson(it)) } }
                })
            }
        }
    }

    private fun appJson(a: AppRef) = buildJsonObject {
        put("package", a.packageName); put("activity", a.activityName); put("label", a.label)
    }

    private fun itemJson(item: Item): JsonObject = when (item) {
        is Item.App -> buildJsonObject { put("type", "app"); put("id", item.id); put("app", appJson(item.app)) }
        is Item.Folder -> buildJsonObject {
            put("type", "folder"); put("id", item.id); put("name", item.name)
            putJsonArray("apps") { item.apps.forEach { add(appJson(it)) } }
        }
    }

    /** Strict decode: throws on malformed input. */
    fun decode(raw: String): Layout {
        val root = JsonRpc.json.parseToJsonElement(raw) as? JsonObject ?: error("layout root must be an object")
        val format = root.num("format") ?: 1
        require(format == FORMAT_VERSION) { "unsupported layout format $format" }
        val pages = (root["pages"] as? JsonArray ?: error("missing pages")).map { pe ->
            val po = pe as? JsonObject ?: error("page must be an object")
            Page(
                index = po.num("index") ?: error("page missing index"),
                items = (po["items"] as? JsonArray ?: JsonArray(emptyList())).map { decodeItem(it as JsonObject) },
            )
        }
        return Layout(
            pages = pages,
            gridColumns = root.num("gridColumns") ?: 4,
            gridRows = root.num("gridRows") ?: 6,
            revision = root.long("revision") ?: 0,
        )
    }

    private fun decodeApp(o: JsonObject) = AppRef(
        packageName = o.text("package") ?: error("app missing package"),
        activityName = o.text("activity") ?: error("app missing activity"),
        label = o.text("label") ?: "",
    )

    private fun decodeItem(o: JsonObject): Item = when (val t = o.text("type")) {
        "app" -> Item.App(o.text("id") ?: error("item missing id"), decodeApp(o["app"] as JsonObject))
        "folder" -> Item.Folder(
            id = o.text("id") ?: error("item missing id"),
            name = o.text("name") ?: error("folder missing name"),
            apps = (o["apps"] as? JsonArray ?: JsonArray(emptyList())).map { decodeApp(it as JsonObject) },
        )
        else -> error("unknown item type $t")
    }

    /** Lenient decode: a corrupt or invalid file yields a fresh layout rather than a crash on boot. */
    fun decodeOrDefault(raw: String?): Layout {
        if (raw.isNullOrBlank()) return Layout()
        return try {
            val l = decode(raw)
            if (l.validate().isEmpty()) l else Layout()
        } catch (e: Exception) {
            Layout()
        }
    }

    private fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.content?.toIntOrNull()
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.content?.toLongOrNull()
}

/** Storage abstraction; in-memory for tests, file-backed on Android. */
interface LayoutStore {
    fun load(): Layout
    fun save(layout: Layout)
}

class InMemoryLayoutStore(initial: Layout = Layout()) : LayoutStore {
    @Volatile
    private var raw: String = LayoutCodec.encode(initial)
    var saveCount: Int = 0
        private set

    override fun load(): Layout = LayoutCodec.decodeOrDefault(raw)
    override fun save(layout: Layout) { raw = LayoutCodec.encode(layout); saveCount++ }
}
