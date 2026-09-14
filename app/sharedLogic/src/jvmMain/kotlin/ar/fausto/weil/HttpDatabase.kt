package ar.fausto.weil

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * JVM/desktop [Database] over the libsql/Turso HTTP "v2/pipeline" API
 * (remote-only — no local replica, no sync engine, one HTTP round-trip per
 * call). Phase 2a of docs/desktop-target-plan.md. Blocking on purpose: the
 * [Database] interface is synchronous by contract and DatabaseProvider
 * always calls it from a dedicated dispatcher thread.
 */
class HttpDatabase(
    url: String,
    private val authToken: String,
) : Database {
    /** libsql:// and turso:// URLs are https:// over the wire (same rule as
     * the Android/Rust driver in Database.android.kt). */
    private val pipelineUrl = URI.create(
        url.replace(Regex("^(libsql|turso)://"), "https://").trimEnd('/') + "/v2/pipeline",
    )

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Guarded by [schemaLock]; see [ensureSchema]. */
    private var schemaReady = false
    private val schemaLock = Any()

    override fun sync() = Unit

    override fun close() = Unit

    override fun execute(sql: String, params: Map<String, Any>?) {
        ensureSchema()
        pipeline(sql, params)
    }

    override fun <T> query(sql: String, params: Map<String, Any>?, block: (Sequence<Row>) -> T): T {
        ensureSchema()
        val result = pipeline(sql, params)
        val rows = result?.get("rows")?.jsonArray.orEmpty()
        return block(rows.asSequence().map { row -> row.jsonArray.map { decodeValue(it.jsonObject) } })
    }

    /**
     * Applies [SCHEMA_SQL] + [migrateSchema] once, exactly like the Android and
     * iOS engines do on open. Deferred to first use rather than done in `init`
     * because DatabaseProvider builds the Database off the db dispatcher and
     * only wraps the *use* of it — doing blocking HTTP in the constructor would
     * run on the caller's thread (often the UI one).
     *
     * The flag is set before the work runs so the nested execute/query calls
     * made by [migrateSchema] don't recurse back into here.
     */
    private fun ensureSchema() {
        synchronized(schemaLock) {
            if (schemaReady) return
            schemaReady = true
            try {
                // SCHEMA_SQL is a ';'-joined script and the pipeline API takes
                // one statement per request, so split it and batch the lot into
                // a single round trip.
                val statements = SCHEMA_SQL.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                pipelineBatch(statements)
                migrateSchema()
            } catch (e: Throwable) {
                schemaReady = false
                throw e
            }
        }
    }

    /** Sends one `execute` + `close` request pair; returns the `execute`
     * step's `result` object, or null for statements with no result set. */
    private fun pipeline(sql: String, params: Map<String, Any>?): JsonObject? =
        send(listOf(executeRequest(sql, params))).firstOrNull()

    /** Runs several statements in a single pipeline request. */
    private fun pipelineBatch(statements: List<String>) {
        if (statements.isEmpty()) return
        send(statements.map { executeRequest(it, null) })
    }

    private fun executeRequest(sql: String, params: Map<String, Any>?): JsonObject = buildJsonObject {
        put("type", "execute")
        put("stmt", buildJsonObject {
            put("sql", sql)
            put("named_args", buildJsonArray {
                params?.forEach { (name, value) ->
                    add(buildJsonObject {
                        // The engine's named params carry a leading ':' in the
                        // map keys; hrana wants the bare name.
                        put("name", name.removePrefix(":"))
                        put("value", encodeValue(value))
                    })
                }
            })
        })
    }

    /** POSTs the requests (plus a trailing `close`) and returns each execute
     * step's `result` object, in order. */
    private fun send(requests: List<JsonObject>): List<JsonObject?> {
        val body = buildJsonObject {
            put("requests", buildJsonArray {
                requests.forEach { add(it) }
                add(buildJsonObject { put("type", "close") })
            })
        }
        val request = HttpRequest.newBuilder(pipelineUrl)
            .header("Authorization", "Bearer $authToken")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 400) {
            throw RuntimeException("turso http ${response.statusCode()}: ${response.body()}")
        }
        val results = json.parseToJsonElement(response.body()).jsonObject["results"]?.jsonArray.orEmpty()
        // Drop the trailing close step; check every execute step for errors.
        return results.take(requests.size).map { step ->
            val obj = step.jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "error") {
                val error = obj["error"]?.jsonObject
                throw RuntimeException(
                    "turso error: ${error?.get("message")?.jsonPrimitive?.content}" +
                        " (${error?.get("code")?.jsonPrimitive?.content})",
                )
            }
            obj["response"]?.jsonObject?.get("result")?.jsonObject
        }
    }

    private fun encodeValue(value: Any): JsonElement = buildJsonObject {
        when (value) {
            is Long -> { put("type", "integer"); put("value", value.toString()) }
            is Int -> { put("type", "integer"); put("value", value.toLong().toString()) }
            is Double -> { put("type", "float"); put("value", value) }
            is Float -> { put("type", "float"); put("value", value.toDouble()) }
            is Boolean -> { put("type", "integer"); put("value", (if (value) 1 else 0).toString()) }
            is ByteArray -> { put("type", "blob"); put("base64", Base64.getEncoder().encodeToString(value)) }
            else -> { put("type", "text"); put("value", value.toString()) }
        }
    }

    private fun decodeValue(cell: JsonObject): Any? = when (cell["type"]?.jsonPrimitive?.content) {
        "null" -> null
        "integer" -> cell["value"]?.jsonPrimitive?.long
        "float" -> cell["value"]?.jsonPrimitive?.content?.toDoubleOrNull()
        "text" -> cell["value"]?.jsonPrimitive?.content
        "blob" -> cell["base64"]?.jsonPrimitive?.content?.let { Base64.getDecoder().decode(it) }
        else -> cell["value"]?.let { if (it is JsonPrimitive) it.content else it.toString() }
    }
}

private fun JsonElement?.orEmpty(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())
