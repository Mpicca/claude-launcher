package dev.picca.launcher.core

import dev.picca.launcher.core.mcp.JsonRpc
import dev.picca.launcher.core.mcp.McpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerTest {
    private val engine = engine()
    private val server = McpServer(engine)

    private fun rpc(method: String, params: String? = null, id: String = "1"): JsonObject {
        val raw = buildString {
            append("""{"jsonrpc":"2.0","id":$id,"method":"$method"""")
            if (params != null) append(""","params":$params""")
            append("}")
        }
        val resp = server.handle(raw) ?: throw AssertionError("expected a response for $method")
        return JsonRpc.json.parseToJsonElement(resp).jsonObject
    }

    private fun call(tool: String, args: String = "{}"): JsonObject {
        val r = rpc("tools/call", """{"name":"$tool","arguments":$args}""")
        assertNull("rpc error: ${r["error"]}", r["error"])
        return r["result"]!!.jsonObject
    }

    private fun structured(result: JsonObject) = result["structuredContent"]!!.jsonObject
    private fun isError(result: JsonObject) = result["isError"]!!.jsonPrimitive.content.toBoolean()

    // ----------------------------------------------------------- handshake

    @Test fun `initialize negotiates protocol and advertises tools capability`() {
        val r = rpc("initialize", """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"claude","version":"1"}}""")
        val res = r["result"]!!.jsonObject
        assertEquals("2025-03-26", res["protocolVersion"]!!.jsonPrimitive.content)
        assertNotNull(res["capabilities"]!!.jsonObject["tools"])
        assertEquals("claude-launcher", res["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(res["instructions"]!!.jsonPrimitive.content.contains("cannot install"))
    }

    @Test fun `unknown protocol version falls back to latest supported`() {
        val r = rpc("initialize", """{"protocolVersion":"1999-01-01"}""")
        assertEquals(McpServer.PROTOCOL_VERSION, r["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test fun `initialized notification has no response and flips state`() {
        assertFalse(server.initialized)
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertTrue(server.initialized)
    }

    @Test fun `ping returns empty result`() {
        assertEquals(JsonObject(emptyMap()), rpc("ping")["result"])
    }

    @Test fun `id types are echoed - numeric, string, null`() {
        assertEquals("42", rpc("ping", id = "42")["id"]!!.jsonPrimitive.content)
        assertEquals("abc", rpc("ping", id = "\"abc\"")["id"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------- error handling

    @Test fun `malformed json yields parse error with null id`() {
        val r = JsonRpc.json.parseToJsonElement(server.handle("{not json")!!).jsonObject
        assertEquals(JsonRpc.PARSE_ERROR, r["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `wrong jsonrpc version and missing method are invalid requests`() {
        var r = JsonRpc.json.parseToJsonElement(server.handle("""{"jsonrpc":"1.0","id":1,"method":"ping"}""")!!).jsonObject
        assertEquals(JsonRpc.INVALID_REQUEST, r["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        r = JsonRpc.json.parseToJsonElement(server.handle("""{"jsonrpc":"2.0","id":1}""")!!).jsonObject
        assertEquals(JsonRpc.INVALID_REQUEST, r["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `unknown method is method-not-found and unknown notification is silently dropped`() {
        val r = rpc("nope/nothing")
        assertEquals(JsonRpc.METHOD_NOT_FOUND, r["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/whatever"}"""))
    }

    @Test fun `unknown tool is an rpc error, bad arguments are a tool error`() {
        val r = rpc("tools/call", """{"name":"launch_nukes","arguments":{}}""")
        assertEquals(JsonRpc.INVALID_PARAMS, r["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val res = call("create_folder", """{"apps":["com.slack"]}""") // missing name
        assertTrue(isError(res))
        assertTrue(structured(res)["message"]!!.jsonPrimitive.content.contains("name"))
    }

    @Test fun `domain errors are reported as tool errors not transport errors`() {
        val res = call("create_folder", """{"name":"X","apps":["com.not.real"]}""")
        assertTrue(isError(res))
        assertEquals("NOT_INSTALLED", structured(res)["error"]!!.jsonPrimitive.content)
        assertEquals(0L, engine.current.revision)
    }

    // --------------------------------------------------------------- tools

    @Test fun `tools list is complete and every tool has a valid schema and annotations`() {
        val tools = rpc("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(
            listOf("list_apps", "get_layout", "create_folder", "rename_folder", "add_to_folder", "remove_from_folder",
                "dissolve_folder", "place_app", "hide_app", "apply_plan", "add_page", "remove_page", "show_all_apps", "undo", "safety_info"),
            names,
        )
        for (t in tools) {
            val o = t.jsonObject
            assertTrue(o["description"]!!.jsonPrimitive.content.length > 20)
            val schema = o["inputSchema"]!!.jsonObject
            assertEquals("object", schema["type"]!!.jsonPrimitive.content)
            assertNotNull(schema["properties"])
            val ann = o["annotations"]!!.jsonObject
            assertEquals("false", ann["destructiveHint"]!!.jsonPrimitive.content)
            assertEquals("false", ann["openWorldHint"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `no tool is annotated destructive - the safety contract in metadata`() {
        assertTrue(server.tools.none { it.toJson()["annotations"]!!.jsonObject["destructiveHint"]!!.jsonPrimitive.content == "true" })
    }

    @Test fun `list_apps reports placement for every installed app`() {
        engine.createFolder("Social", listOf("com.instagram.android"))
        engine.placeApp("com.android.dialer", 1)
        val res = structured(call("list_apps"))
        assertEquals(FakePhone.apps.size, res["count"]!!.jsonPrimitive.content.toInt())
        val byLabel = res["apps"]!!.jsonArray.associate { it.jsonObject["label"]!!.jsonPrimitive.content to it.jsonObject["placement"]!!.jsonPrimitive.content }
        assertEquals("folder:Social", byLabel["Instagram"])
        assertEquals("page_1", byLabel["Phone"])
        assertEquals("drawer_only", byLabel["Slack"])
    }

    @Test fun `end to end - the flow Claude would run`() {
        rpc("initialize", """{"protocolVersion":"2025-06-18"}""")
        server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        call("list_apps")
        val res = call(
            "apply_plan",
            """{
              "folders": [
                {"name":"Social","apps":["com.instagram.android","com.twitter.android","com.reddit.frontpage","com.whatsapp"]},
                {"name":"Work","apps":["com.slack","com.microsoft.teams","com.microsoft.office.outlook","com.databricks.mobile"]},
                {"name":"Finance","apps":["com.chase.sig.android","com.venmo","com.robinhood.android"]},
                {"name":"Media","apps":["YouTube","Spotify","Netflix"]}
              ],
              "pin_to_top_level": ["Phone","Messages","Camera","Chrome"]
            }""",
        )
        assertFalse(isError(res))
        assertEquals(4, structured(res)["foldersApplied"]!!.jsonPrimitive.content.toInt())
        val layout = structured(call("get_layout"))
        val items = layout["pages"]!!.jsonArray[0].jsonObject["items"]!!.jsonArray
        assertEquals(8, items.size) // 4 folders + 4 pinned
        assertEquals(1, layout["undoAvailable"]!!.jsonPrimitive.content.toInt())

        // rename, move, undo, verify
        assertFalse(isError(call("rename_folder", """{"folder":"Media","new_name":"Entertainment"}""")))
        assertFalse(isError(call("add_to_folder", """{"folder":"Entertainment","apps":["com.nianticlabs.pokemongo"]}""")))
        assertEquals(4, structured(call("get_layout"))["pages"]!!.jsonArray[0].jsonObject["items"]!!.jsonArray
            .first { it.jsonObject["name"]?.jsonPrimitive?.content == "Entertainment" }.jsonObject["apps"]!!.jsonArray.size)
        assertFalse(isError(call("undo")))
        assertFalse(isError(call("undo")))
        assertNotNull(engine.current.findFolderByName("Media"))
        assertFalse(isError(call("hide_app", """{"app":"Chrome"}""")))
        assertEquals(7, structured(call("get_layout"))["pages"]!!.jsonArray[0].jsonObject["items"]!!.jsonArray.size)
    }

    @Test fun `every mutating tool reports the post-mutation revision`() {
        call("create_folder", """{"name":"A","apps":["com.slack"]}""")
        for ((tool, args) in listOf(
            "rename_folder" to """{"folder":"A","new_name":"B"}""",
            "add_to_folder" to """{"folder":"B","apps":["com.venmo"]}""",
            "remove_from_folder" to """{"folder":"B","apps":["com.venmo"]}""",
            "place_app" to """{"app":"Strava"}""",
            "hide_app" to """{"app":"Strava"}""",
            "add_page" to "{}",
            "remove_page" to """{"page":1}""",
            "show_all_apps" to "{}",
            "undo" to "{}",
            "dissolve_folder" to """{"folder":"B"}""",
        )) {
            val res = call(tool, args)
            assertFalse("$tool errored: ${structured(res)}", isError(res))
            assertEquals(tool, engine.current.revision, structured(res)["revision"]!!.jsonPrimitive.content.toLong())
        }
    }

    @Test fun `tool result carries both text and structuredContent`() {
        val res = call("safety_info")
        val content = res["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        val text = content[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("uninstall"))
        assertEquals(JsonRpc.json.parseToJsonElement(text), res["structuredContent"])
    }

    @Test fun `apply_plan with malformed folders array is a tool error with nothing applied`() {
        val res = call("apply_plan", """{"folders":"nope"}""")
        assertTrue(isError(res))
        val res2 = call("apply_plan", """{"folders":[{"name":"A"}]}""")
        assertTrue(isError(res2))
        assertEquals(0L, engine.current.revision)
    }

    @Test fun `remove_page and add_page round trip`() {
        val idx = structured(call("add_page"))["page"]!!.jsonPrimitive.content.toInt()
        assertEquals(1, idx)
        assertFalse(isError(call("remove_page", """{"page":1}""")))
        assertTrue(isError(call("remove_page", """{"page":0}""")))
    }

    @Test fun `batch of 200 sequential calls keeps the layout valid and revision exact`() {
        for (i in 1..100) {
            assertFalse(isError(call("create_folder", """{"name":"F$i"}""")))
            assertFalse(isError(call("rename_folder", """{"folder":"F$i","new_name":"G$i"}""")))
        }
        assertEquals(200L, engine.current.revision)
        assertTrue(engine.current.validate().isEmpty())
        assertEquals(100, engine.current.folders().size)
    }
}
