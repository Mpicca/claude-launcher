package dev.picca.launcher.core.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Minimal JSON-RPC 2.0 types. Kept dependency-free so the Android module stays lean. */
object JsonRpc {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        explicitNulls = false
    }

    data class Request(val id: JsonElement?, val method: String, val params: JsonObject?) {
        val isNotification: Boolean get() = id == null
    }

    fun parse(raw: String): Request {
        val el = try {
            json.parseToJsonElement(raw)
        } catch (e: Exception) {
            throw RpcError(PARSE_ERROR, "parse error: ${e.message}", id = null)
        }
        val obj = el as? JsonObject ?: throw RpcError(INVALID_REQUEST, "request must be an object", id = null)
        val id = obj["id"]?.takeIf { it !is JsonNull }
        val version = obj["jsonrpc"]?.jsonPrimitive?.content
        if (version != "2.0") throw RpcError(INVALID_REQUEST, "jsonrpc must be \"2.0\"", id)
        val method = (obj["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw RpcError(INVALID_REQUEST, "missing method", id)
        val params = obj["params"]?.let {
            it as? JsonObject ?: throw RpcError(INVALID_PARAMS, "params must be an object", id)
        }
        return Request(id, method, params)
    }

    fun result(id: JsonElement?, result: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", result)
    }

    fun error(id: JsonElement?, code: Int, message: String, data: JsonElement? = null): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
            if (data != null) put("data", data)
        })
    }
}

class RpcError(val code: Int, message: String, val id: JsonElement?, val data: JsonElement? = null) : RuntimeException(message)

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.strList(key: String): List<String>? =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.map { (it as? JsonPrimitive)?.content ?: it.toString() }
