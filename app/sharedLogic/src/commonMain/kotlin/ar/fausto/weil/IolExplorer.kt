package ar.fausto.weil

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/*
 * The IOL API explorer: any read endpoint, answered raw, for someone who
 * wants to see what IOL returns for their own account before deciding what
 * to build on it. Same read-only rule as [IolClient]: GETs under `/api/`
 * and nothing else, so the explorer can show an order but never place one.
 */

/** One GET as IOL answered it, error bodies included: a 404 is an answer too. */
data class IolRawResponse(val path: String, val status: Int, val body: String, val elapsedMs: Long)

/**
 * [input] as a path the explorer may GET, or an [IllegalArgumentException].
 * Accepts the full URL pasted from the swagger as well as a bare path.
 */
fun iolExplorerPath(input: String): String {
    var path = input.trim()
    val base = IolClient.IOL_BASE_URL
    if (path.startsWith(base, ignoreCase = true)) path = path.substring(base.length)
    if (!path.startsWith("/")) path = "/$path"
    require(path.startsWith("/api/", ignoreCase = true)) { "the path must start with /api/" }
    require(path.none { it.isWhitespace() }) { "the path has spaces" }
    require("://" !in path && "@" !in path && ".." !in path) { "not a path on IOL's API" }
    return path
}

/**
 * One field of a response, wherever it appears: `[].titulo.simbolo` for the
 * symbol of every position. [types] gathers every JSON type seen there (a
 * field IOL fills with a number on some rows and null on others shows both),
 * [example] is the first non-null value, [count] how many times it appeared.
 */
data class JsonField(val path: String, val types: Set<String>, val example: String?, val count: Int)

/**
 * Every field of [root], in first-seen order, array items merged under `[]`
 * so a list of a thousand quotes reads as one schema with one example each.
 */
fun jsonFields(root: JsonElement): List<JsonField> {
    val fields = LinkedHashMap<String, JsonField>()
    fun visit(path: String, element: JsonElement) {
        val type = jsonType(element)
        val example = (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val seen = fields[path]
        fields[path] = if (seen == null) {
            JsonField(path, setOf(type), example, 1)
        } else {
            seen.copy(types = seen.types + type, example = seen.example ?: example, count = seen.count + 1)
        }
        when (element) {
            is JsonObject -> element.forEach { (key, value) -> visit(if (path.isEmpty()) key else "$path.$key", value) }
            is JsonArray -> element.forEach { visit("$path[]", it) }
            else -> Unit
        }
    }
    visit("", root)
    // The root itself only says "object"/"array": the fields under it say the rest.
    return fields.values.filter { it.path.isNotEmpty() }
}

/** JSON's type of [element], numbers split into integer and decimal (what a parser would care about). */
fun jsonType(element: JsonElement): String = when (element) {
    is JsonNull -> "null"
    is JsonObject -> "object"
    is JsonArray -> if (element.isEmpty()) "array (vacío)" else "array"
    is JsonPrimitive -> when {
        element.isString -> "string"
        element.booleanOrNull != null -> "boolean"
        element.longOrNull != null -> "integer"
        else -> "decimal"
    }
}

/** yyyy-MM-dd as IOL's paths and filters take it, for the explorer's ready-made requests. */
fun iolExplorerDate(epochMillis: Long): String = iolDate(epochMillis)

/** Something the user holds at IOL, as the explorer's symbol picker offers it. */
data class ExplorerInstrument(
    val symbol: String,
    val name: String?,
    /** IOL's market path segment ("bcba", "nyse"), or "fci" for a fund, which has none. */
    val market: String,
    /** [InstrumentInfo.kind]: "cedear", "bond", "fci", …; "other" when unknown. */
    val kind: String,
)

/**
 * The instruments in [quantities] (one holdings account's balance per
 * commodity) still held, by symbol. Currencies have no market prefix and are
 * skipped; a fund's `FCI:` namespace becomes the "fci" pseudo-market.
 */
fun iolExplorerInstruments(quantities: Map<String, Long>, commodities: Map<String, InstrumentInfo>): List<ExplorerInstrument> =
    quantities.filter { (id, qty) -> qty > 0L && ':' in id }.keys.map { id ->
        val info = commodities[id]
        val market = id.substringBefore(':').lowercase()
        ExplorerInstrument(
            symbol = info?.symbol ?: id.substringAfter(':'),
            name = info?.name,
            market = market,
            kind = if (market == "fci") "fci" else info?.kind ?: "other",
        )
    }.distinctBy { it.market to it.symbol }.sortedBy { it.symbol }

/**
 * A JSON number as a person reads it in Argentina: `2347747.48` →
 * `2.347.747,48`, `15890.0` → `15.890`. Anything that is not a plain decimal
 * (exponents, huge literals) comes back unchanged — the JSON view keeps the
 * literal either way.
 */
fun friendlyNumber(literal: String): String {
    val match = Regex("""^(-?)(\d+)(?:\.(\d+))?$""").matchEntire(literal) ?: return literal
    val (sign, whole, fraction) = match.destructured
    val grouped = whole.reversed().chunked(3).joinToString(".").reversed()
    val decimals = fraction.trimEnd('0')
    return sign + grouped + if (decimals.isEmpty()) "" else ",$decimals"
}

/**
 * IOL's timestamps (`2025-08-14T11:02:33.47`, `2025-08-14T00:00:00`) as
 * `14/08/2025 11:02`, or just the date at midnight; null for anything else.
 */
fun friendlyTimestamp(text: String): String? {
    val match = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?[^ ]*)?$""").matchEntire(text) ?: return null
    val (y, mo, d, h, mi, s) = match.destructured
    val date = "$d/$mo/$y"
    return if (h.isEmpty() || (h == "00" && mi == "00" && (s.isEmpty() || s == "00"))) date else "$date $h:$mi"
}
