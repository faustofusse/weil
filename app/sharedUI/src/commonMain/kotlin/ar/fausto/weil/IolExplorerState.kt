package ar.fausto.weil

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The IOL explorer's answers, hoisted above the nav host like
 * [SuggestDebugState]: the explorer is a stack of screens over one response
 * (the list, an item, a list inside the item), and every one of them reads
 * the same parsed answer instead of asking IOL again. Keyed by path, never
 * evicted (a session looks at a handful); [run] with `force` asks again.
 */
@Stable
class IolExplorerState(private val explore: suspend (path: String) -> IolRawResponse) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val results = mutableStateMapOf<String, ExplorerResult>()
    private val failures = mutableStateMapOf<String, String>()
    private val running = mutableStateMapOf<String, Boolean>()
    private val jobs = mutableMapOf<String, Job>()

    internal fun result(path: String): ExplorerResult? = results[path]

    fun failure(path: String): String? = failures[path]

    fun isRunning(path: String): Boolean = running[path] == true

    fun run(path: String, force: Boolean = false) {
        if (force) {
            jobs.remove(path)?.cancel()
        } else if (isRunning(path) || results.containsKey(path)) {
            return
        }
        failures.remove(path)
        running[path] = true
        jobs[path] = scope.launch {
            try {
                val response = explore(path)
                results[path] = withContext(Dispatchers.Default) { ExplorerResult.of(response) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures[path] = e.message ?: e.toString()
            } finally {
                running.remove(path)
                jobs.remove(path)
            }
        }
    }
}

/** One answer, parsed once: the JSON, its fields and its pretty-printed lines. */
internal class ExplorerResult(
    val response: IolRawResponse,
    val json: JsonElement?,
    val fields: List<JsonField>,
    val lines: List<String>,
) {
    val ok: Boolean get() = response.status in 200..299

    /** IOL's own words for a refusal, when it sent any. */
    val errorMessage: String? = (json as? JsonObject)?.let { obj ->
        listOf("message", "Message", "mensaje", "error_description", "error", "descripcion")
            .firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }

    private val counts = fields.associate { it.path to it.count }

    /**
     * How many objects [field] could have appeared in: its parent's count.
     * Fewer appearances than that is the news (an optional field); null when
     * the field hangs off the root or is an array's item.
     */
    fun parentCount(field: JsonField): Int? {
        if (field.path.endsWith("[]")) return null
        val cut = field.path.lastIndexOf('.')
        if (cut < 0) return null
        return counts[field.path.substring(0, cut)]
    }

    fun fieldsTable(): String = buildString {
        appendLine("GET ${response.path}")
        appendLine("campo\ttipo\tejemplo")
        fields.forEach { appendLine("${it.path}\t${it.types.joinToString(" | ")}\t${it.example.orEmpty()}") }
    }

    companion object {
        fun of(response: IolRawResponse): ExplorerResult {
            val json = runCatching { Json.parseToJsonElement(response.body) }.getOrNull()
            val text = json?.let { prettyJson(it) } ?: response.body
            return ExplorerResult(response, json, json?.let { jsonFields(it) }.orEmpty(), text.lines())
        }
    }
}

private val pretty = Json { prettyPrint = true }

internal fun prettyJson(element: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), element)

/**
 * The node at [pointer] ("activos/3/titulo": keys and indexes split by '/'),
 * or null when the answer has no such node.
 */
internal fun JsonElement.at(pointer: String): JsonElement? {
    var node: JsonElement? = this
    for (segment in pointer.split('/').filter { it.isNotEmpty() }) {
        node = when (node) {
            is JsonObject -> node[segment]
            is JsonArray -> segment.toIntOrNull()?.let { node.getOrNull(it) }
            else -> null
        }
    }
    return node
}

internal fun childPointer(pointer: String, segment: String): String = if (pointer.isEmpty()) segment else "$pointer/$segment"
