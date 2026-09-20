package ar.fausto.weil

import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.ln

/**
 * "Parecidos a este": one vector per transaction, captured notification and
 * email receipt, and an exact cosine scan over them.
 *
 * Two properties shape everything here:
 *
 * 1. **The vector of the row you are looking at is already stored**, so every
 *    similarity search is pure SQL — offline, no network, no model call. The
 *    only thing that needs connectivity is the sweep that fills the vectors,
 *    and that sits behind an explicit button in the profile screen.
 * 2. **One type and one dimensionality across the three tables**
 *    ([EMBEDDING_DIMS] f32), which is what makes the cross-table search
 *    possible: `vector_distance_cos` requires matching type and dims and does
 *    not care which table a vector came from. Quantizing notifications to
 *    save space would have broken exactly that.
 *
 * There is deliberately **no vector index**: the sync engine on the device has
 * `vector32`/`vector_distance_cos`/`vector_extract` but not `libsql_vector_idx`
 * or `vector_top_k`, and the index DDL does not even parse there (the schema
 * replicates to the device). A scan over a few hundred candidate rows is
 * milliseconds.
 */

/** Must match `EMBED_MODEL`/`EMBED_DIMS` in `app/worker/src/embed.ts`. */
const val EMBEDDING_MODEL = "gemini-embedding-001"
const val EMBEDDING_DIMS = 512

/**
 * What lands in `embedding_model`. Rows tagged with a different value are
 * never compared against each other and are what the sweep re-embeds, so
 * changing model is changing this constant plus one "revectorizar" tap.
 */
const val EMBEDDING_TAG = "$EMBEDDING_MODEL/$EMBEDDING_DIMS"

/** The three embedded tables, and the column that identifies them in SQL. */
enum class EmbedKind(val table: String) {
    Transaction("transactions"),
    Notification("notifications"),
    Email("emails"),
}

/**
 * One neighbour of the row being viewed. [amountMinor] and [date] are carried
 * because cosine similarity says "these read alike", never "these are the same
 * event" — two Rappi orders are near-identical vectors and different
 * purchases, so the human confirming a link has to see the money and the day.
 *
 * [amountMinor] is a **magnitude**, not a signed posting: which leg counts as
 * the sign depends on account types this list does not load, and a wrong sign
 * next to a link button is worse than no sign.
 */
data class SimilarItem(
    val kind: EmbedKind,
    val id: String,
    val title: String,
    val subtitle: String,
    val date: Long,
    val amountMinor: Long?,
    val commodity: String?,
    val distance: Double,
)

/** Text → unit vectors. The worker holds the key; the desktop harness fakes it. */
interface TextEmbedder {
    /** Tag stored in `embedding_model` alongside every vector it produces. */
    val tag: String
    suspend fun embed(texts: List<String>): List<List<Double>>
}

@Serializable
private data class EmbedRequest(val texts: List<String>)

@Serializable
private data class EmbedResponse(
    val model: String = EMBEDDING_TAG,
    val dims: Int = EMBEDDING_DIMS,
    val vectors: List<List<Double>> = emptyList(),
)

/** `POST /embed` on the finance worker, same `auth_finance` cookie as import. */
class WorkerEmbedder(
    private val store: SecureStore,
    private val baseUrl: String = AuthConfig.API_BASE_URL,
) : TextEmbedder {
    override val tag: String = EMBEDDING_TAG

    private val json = Json { ignoreUnknownKeys = true }
    private val client = platformHttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = TIMEOUT_MS
            socketTimeoutMillis = TIMEOUT_MS
            connectTimeoutMillis = 30_000
        }
        platformUserAgent()?.let { ua -> install(UserAgent) { agent = ua } }
    }
    private val cookieName = "auth_${AuthConfig.SLUG}"

    override suspend fun embed(texts: List<String>): List<List<Double>> {
        if (texts.isEmpty()) return emptyList()
        val resp = client.post("$baseUrl/embed") {
            contentType(ContentType.Application.Json)
            storedCookieHeader(cookieName, store)?.let { header(HttpHeaders.Cookie, it) }
            setBody(EmbedRequest(texts))
        }
        if (resp.status.value == 401) throw SessionExpired()
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText())
        }
        val body = resp.body<EmbedResponse>()
        require(body.vectors.size == texts.size) {
            "embed: asked ${texts.size} texts, got ${body.vectors.size} vectors"
        }
        return body.vectors
    }

    private companion object {
        const val TIMEOUT_MS = 120_000L
    }
}

/** Progress of a sweep, for the profile screen's indicator. */
data class EmbedProgress(val done: Int, val total: Int)

class EmbeddingsRepository(
    private val db: DatabaseProvider,
    private val embedder: TextEmbedder,
) {

    /** Rows that would be embedded by a full [embedPending] run. */
    suspend fun pendingCount(): Int = db.useForRead { d ->
        EmbedKind.entries.sumOf { kind -> pending(d, kind, MAX_SCAN).size }
    }

    /**
     * Embeds every candidate row that has no vector, or one produced by
     * another model. Writes after each batch, so it can be cut off (the user
     * leaving the screen cancels the coroutine) and resumed later without
     * redoing work.
     */
    suspend fun embedPending(
        limit: Int = MAX_SCAN,
        onProgress: (EmbedProgress) -> Unit = {},
    ): Int {
        val work = db.useForRead { d ->
            EmbedKind.entries.flatMap { kind -> pending(d, kind, limit) }
        }
        if (work.isEmpty()) {
            onProgress(EmbedProgress(0, 0))
            return 0
        }
        var done = 0
        onProgress(EmbedProgress(0, work.size))
        for (batch in work.chunked(BATCH)) {
            val vectors = embedder.embed(batch.map { it.text })
            db.use { d ->
                batch.forEachIndexed { i, row -> store(d, row, vectors[i]) }
            }
            done += batch.size
            onProgress(EmbedProgress(done, work.size))
        }
        return done
    }

    /**
     * The escape hatch behind the "buscar parecidas" button: embeds one row
     * on demand, even one the candidate prefilter rejected. Cheap (a single
     * call, and the vector is kept), and it exposes exactly where the
     * prefilter is wrong.
     */
    suspend fun embedOne(kind: EmbedKind, id: String): Boolean {
        val row = db.useForRead { d -> textOf(d, kind, id) } ?: return false
        val vector = embedder.embed(listOf(row.text)).firstOrNull() ?: return false
        db.use { d -> store(d, row, vector) }
        return true
    }

    /** True when this row already has a vector under the current model. */
    suspend fun isEmbedded(kind: EmbedKind, id: String): Boolean = db.useForRead { d ->
        d.query(
            "select 1 from ${kind.table} where id = :id and embedding is not null" +
                " and embedding_model = :model",
            mapOf(":id" to id, ":model" to embedder.tag),
        ) { rows -> rows.any() }
    }

    /**
     * Nearest rows of [into] to the row ([kind], [id]), by cosine distance.
     *
     * No network: the query vector is read back out of the stored blob with
     * `vector_extract`. Returns empty when the source row has no vector yet —
     * the caller offers [embedOne] instead of hiding the section.
     */
    suspend fun similar(
        kind: EmbedKind,
        id: String,
        into: EmbedKind = kind,
        k: Int = 10,
        samePackageOnly: Boolean = false,
        amountWeight: Double = AMOUNT_WEIGHT,
    ): List<SimilarItem> = db.useForRead { d ->
        val query = d.query(
            "select vector_extract(embedding) from ${kind.table}" +
                " where id = :id and embedding is not null and embedding_model = :model",
            mapOf(":id" to id, ":model" to embedder.tag),
        ) { rows -> rows.firstOrNull()?.firstOrNull()?.toString() } ?: return@useForRead emptyList()

        // Restricting notification neighbours to the same app is both more
        // relevant and much cheaper, but it is off by default: searching
        // everything is what surfaces the Santander push next to the
        // Santander mail.
        val pkg = if (samePackageOnly && into == EmbedKind.Notification) {
            d.query(
                "select package_name from notifications where id = :id",
                mapOf(":id" to id),
            ) { rows -> rows.firstOrNull()?.firstOrNull()?.toString() }
        } else {
            null
        }

        // Over-fetch when the amount gets a vote: the row whose money matches
        // can sit just outside the top k by cosine alone.
        val want = k.coerceIn(1, 50)
        val scan = if (amountWeight > 0.0) (want * 4).coerceAtMost(50) else want
        val distances = d.query(
            "select id, vector_distance_cos(embedding, vector32(:q)) as d from ${into.table}" +
                " where embedding is not null and embedding_model = :model and id <> :self" +
                (if (pkg != null) " and package_name = :pkg" else "") +
                " order by d limit $scan",
            buildMap {
                put(":q", query)
                put(":model", embedder.tag)
                put(":self", if (into == kind) id else "")
                if (pkg != null) put(":pkg", pkg)
            },
        ) { rows ->
            rows.filter { it.size >= 2 }.mapNotNull { row ->
                val rowId = row[0]?.toString() ?: return@mapNotNull null
                val distance = (row[1] as? Number)?.toDouble() ?: return@mapNotNull null
                rowId to distance
            }.toList()
        }
        val items = hydrate(d, into, distances)
        if (amountWeight <= 0.0) return@useForRead items.take(want)
        val self = rowAmount(d, kind, id)
        items
            .sortedBy { it.distance + amountWeight * amountPenalty(self, it) }
            .take(want)
    }

    /**
     * Nearest rows of [into] to an arbitrary [text].
     *
     * Unlike [similar] this one costs a call (the query vector has to be
     * computed), and it is what makes "parecidos a esto *dicho de otro modo*"
     * possible: the notification→transaction path searches with the plain
     * sentence another model wrote ("compra en Coto con Mercado Pago") instead
     * of the bank's template, so the neighbours are purchases and not rows
     * that share boilerplate.
     */
    suspend fun similarToText(
        text: String,
        into: EmbedKind,
        k: Int = 5,
        exclude: String? = null,
    ): List<SimilarItem> {
        if (text.isBlank()) return emptyList()
        val vector = embedder.embed(listOf(text)).firstOrNull() ?: return emptyList()
        return db.useForRead { d ->
            val want = k.coerceIn(1, 50)
            val distances = d.query(
                "select id, vector_distance_cos(embedding, vector32(:q)) as d from ${into.table}" +
                    " where embedding is not null and embedding_model = :model and id <> :self" +
                    " order by d limit $want",
                mapOf(
                    ":q" to vector.toVectorLiteral(),
                    ":model" to embedder.tag,
                    ":self" to (exclude ?: ""),
                ),
            ) { rows ->
                rows.filter { it.size >= 2 }.mapNotNull { row ->
                    val rowId = row[0]?.toString() ?: return@mapNotNull null
                    val distance = (row[1] as? Number)?.toDouble() ?: return@mapNotNull null
                    rowId to distance
                }.toList()
            }
            hydrate(d, into, distances)
        }
    }

    /**
     * How much the money argues *against* two rows being the same event, as a
     * number on the same scale as a cosine distance (0 = identical, 1 = as bad
     * as it gets).
     *
     * The amount is deliberately **not** in the embedded text: the model has no
     * notion of magnitude, so `$46.210` and `$46.500` tokenize into unrelated
     * pieces while `$46.210` and `$462.100` can land next to each other. It is
     * a re-rank instead, on the log of the ratio so it is scale-free — 300 vs
     * 330 is as close as 30.000 vs 33.000, which is what "parecido" means for
     * money.
     *
     * Unknown on either side, or two different commodities, votes zero rather
     * than guessing: cosine keeps the ordering it had.
     */
    private fun amountPenalty(self: ParsedMoney?, other: SimilarItem): Double {
        val a = self?.amountMinor?.takeIf { it > 0L } ?: return 0.0
        val b = other.amountMinor?.takeIf { it > 0L } ?: return 0.0
        if (other.commodity != null && other.commodity != self.commodity) return 0.0
        val ratio = a.toDouble() / b.toDouble()
        val penalty = abs(ln(ratio))
        return if (penalty > 1.0) 1.0 else penalty
    }

    /** The money of one row, whatever table it lives in. Null when unreadable. */
    private fun rowAmount(d: Database, kind: EmbedKind, id: String): ParsedMoney? = when (kind) {
        EmbedKind.Transaction -> d.query(
            "select amount_minor, commodity from postings where transaction_id = :id",
            mapOf(":id" to id),
        ) { rows ->
            rows.filter { it.size >= 2 }.mapNotNull { row ->
                val minor = (row[0] as? Number)?.toLong() ?: return@mapNotNull null
                ParsedMoney(if (minor < 0) -minor else minor, row[1]?.toString() ?: Money.DEFAULT_COMMODITY)
            }.maxByOrNull { it.amountMinor }
        }

        EmbedKind.Notification -> d.query(
            "select title, text from notifications where id = :id",
            mapOf(":id" to id),
        ) { rows -> rows.firstOrNull()?.joinToString(" ") { it?.toString().orEmpty() } }
            ?.let { biggestMoney(it) }

        EmbedKind.Email -> d.query(
            "select subject, body_text, body_html from emails where id = :id",
            mapOf(":id" to id),
        ) { rows -> rows.firstOrNull() }
            ?.let { row ->
                val subject = decodeMimeHeader(row.getOrNull(0)?.toString().orEmpty())
                val body = emailPlainText(
                    row.getOrNull(2)?.toString()?.takeIf { it.isNotBlank() }
                        ?: row.getOrNull(1)?.toString().orEmpty(),
                )
                biggestMoney("$subject $body")
            }
    }

    /** Drops every vector, so the next sweep recomputes all of them. */
    suspend fun invalidateAll() = db.use { d ->
        for (kind in EmbedKind.entries) {
            d.execute("update ${kind.table} set embedding = null, embedding_model = null")
        }
    }

    // ---- canonical text ----------------------------------------------------

    private class Pending(val kind: EmbedKind, val id: String, val text: String)

    /**
     * The string that gets embedded.
     *
     * A notification is a sentence ("Pagaste $46.210 a Rappi") and a
     * transaction is structure (payee, two accounts): different registers, and
     * cosine between registers is weaker than within one. So the transaction
     * text is written to read like the sentence, with the **payee first** —
     * that is the signal the two genuinely share. Getting this wrong makes the
     * cross-table search look like proof that vectors do not work.
     *
     * No amount and no date on purpose: that is [matchAll]'s job, and in a
     * vector they are noise (two Rappi orders of different sizes should look
     * alike, not different).
     */
    private fun transactionText(payee: String, from: String?, to: String?, note: String?): String =
        listOfNotNull(
            payee.trim().takeIf { it.isNotEmpty() },
            if (from != null || to != null) "${from ?: "?"} → ${to ?: "?"}" else null,
            note?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")

    private fun messageText(origin: String, title: String, body: String): String =
        listOf(origin, title, body.take(MAX_BODY))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

    /** Candidate rows of [kind] without a current vector, with their text. */
    private fun pending(d: Database, kind: EmbedKind, limit: Int): List<Pending> {
        val stale = "(embedding is null or embedding_model is null or embedding_model <> :model)"
        val params = mapOf<String, Any>(":model" to embedder.tag)
        return when (kind) {
            // Every transaction is a candidate: the user wrote it, so it is a
            // movement by construction.
            EmbedKind.Transaction -> d.query(
                "select t.id, t.payee, t.note, p.amount_minor, a.name" +
                    " from transactions t" +
                    " left join postings p on p.transaction_id = t.id" +
                    " left join accounts a on a.id = p.account_id" +
                    " where t.id in (select id from transactions where $stale" +
                    " order by date desc limit $limit)",
                params,
            ) { rows -> transactionsFrom(rows) }

            EmbedKind.Notification -> d.query(
                "select n.id, n.title, n.text, coalesce(app.name, n.package_name)" +
                    " from notifications n" +
                    " left join applications app on app.id = n.package_name" +
                    " where $stale and $MONEY_LIKE" +
                    " order by n.post_time desc limit $limit",
                params,
            ) { rows ->
                rows.filter { it.size >= 4 }.mapNotNull { row ->
                    val id = row[0]?.toString() ?: return@mapNotNull null
                    val title = row[1]?.toString().orEmpty()
                    val text = row[2]?.toString().orEmpty()
                    // The SQL prefilter is a cheap net (a "$" anywhere); this
                    // is the real currency-anchored test. Rows that fail it
                    // stay pending forever and are re-scanned every sweep,
                    // which is the price of "only candidates".
                    if (!hasAmount("$title $text")) return@mapNotNull null
                    Pending(kind, id, messageText(row[3]?.toString().orEmpty(), title, text))
                }.toList()
            }

            EmbedKind.Email -> d.query(
                "select id, from_email, subject, body_html, body_text from emails" +
                    " where $stale and $MONEY_LIKE_EMAIL" +
                    " order by received_at desc limit $limit",
                params,
            ) { rows ->
                rows.filter { it.size >= 5 }.mapNotNull { row ->
                    val id = row[0]?.toString() ?: return@mapNotNull null
                    val subject = decodeMimeHeader(row[2]?.toString().orEmpty())
                    // body_html first: the stored text part is frequently raw
                    // MIME truncated at 10 kB with the receipt past the cut.
                    val raw = row[3]?.toString() ?: row[4]?.toString().orEmpty()
                    val body = emailPlainText(raw)
                    if (!hasAmount("$subject $body")) return@mapNotNull null
                    Pending(kind, id, messageText(row[1]?.toString().orEmpty(), subject, body))
                }.toList()
            }
        }
    }

    /** Same text, for one known row, candidate or not (see [embedOne]). */
    private fun textOf(d: Database, kind: EmbedKind, id: String): Pending? = when (kind) {
        EmbedKind.Transaction -> d.query(
            "select t.id, t.payee, t.note, p.amount_minor, a.name" +
                " from transactions t" +
                " left join postings p on p.transaction_id = t.id" +
                " left join accounts a on a.id = p.account_id" +
                " where t.id = :id",
            mapOf(":id" to id),
        ) { rows -> transactionsFrom(rows).firstOrNull() }

        EmbedKind.Notification -> d.query(
            "select n.id, n.title, n.text, coalesce(app.name, n.package_name)" +
                " from notifications n" +
                " left join applications app on app.id = n.package_name where n.id = :id",
            mapOf(":id" to id),
        ) { rows ->
            rows.firstOrNull()?.takeIf { it.size >= 4 }?.let { row ->
                Pending(
                    kind,
                    id,
                    messageText(
                        row[3]?.toString().orEmpty(),
                        row[1]?.toString().orEmpty(),
                        row[2]?.toString().orEmpty(),
                    ),
                )
            }
        }

        EmbedKind.Email -> d.query(
            "select id, from_email, subject, body_html, body_text from emails where id = :id",
            mapOf(":id" to id),
        ) { rows ->
            rows.firstOrNull()?.takeIf { it.size >= 5 }?.let { row ->
                val raw = row[3]?.toString() ?: row[4]?.toString().orEmpty()
                Pending(
                    kind,
                    id,
                    messageText(
                        row[1]?.toString().orEmpty(),
                        decodeMimeHeader(row[2]?.toString().orEmpty()),
                        emailPlainText(raw),
                    ),
                )
            }
        }
    }

    /** Leg rows → one [Pending] per transaction (money out → money in). */
    private fun transactionsFrom(rows: Sequence<Row>): List<Pending> {
        class Acc(val payee: String, val note: String?) {
            var from: String? = null
            var to: String? = null
        }

        val byId = LinkedHashMap<String, Acc>()
        for (row in rows) {
            if (row.size < 5) continue
            val id = row[0]?.toString() ?: continue
            val acc = byId.getOrPut(id) { Acc(row[1]?.toString().orEmpty(), row[2]?.toString()) }
            val amount = (row[3] as? Number)?.toLong() ?: continue
            val name = row[4]?.toString() ?: continue
            if (amount < 0) {
                if (acc.from == null) acc.from = name
            } else if (acc.to == null) {
                acc.to = name
            }
        }
        return byId.map { (id, acc) ->
            Pending(EmbedKind.Transaction, id, transactionText(acc.payee, acc.from, acc.to, acc.note))
        }
    }

    private fun store(d: Database, row: Pending, vector: List<Double>) {
        d.execute(
            "update ${row.kind.table} set embedding = vector32(:v), embedding_model = :model" +
                " where id = :id",
            mapOf(":v" to vector.toVectorLiteral(), ":model" to embedder.tag, ":id" to row.id),
        )
    }

    // ---- display -----------------------------------------------------------

    private fun hydrate(
        d: Database,
        kind: EmbedKind,
        distances: List<Pair<String, Double>>,
    ): List<SimilarItem> {
        if (distances.isEmpty()) return emptyList()
        val ids = distances.map { it.first }
        val inList = ids.joinToString(",") { "'" + it.replace("'", "''") + "'" }
        val items: Map<String, SimilarItem> = when (kind) {
            EmbedKind.Transaction -> {
                val legs = d.query(
                    "select t.id, t.date, t.payee, t.note, p.amount_minor, p.commodity" +
                        " from transactions t left join postings p on p.transaction_id = t.id" +
                        " where t.id in ($inList)",
                    null,
                ) { rows -> rows.filter { it.size >= 6 }.map { it.toList() }.toList() }
                legs.groupBy { it[0]?.toString().orEmpty() }.mapValues { (id, rows) ->
                    val head = rows.first()
                    // The transaction's "size" is its largest leg in absolute
                    // value: on a two-legged row both are the same number.
                    val biggest = rows.maxByOrNull {
                        ((it[4] as? Number)?.toLong() ?: 0L).let { a -> if (a < 0) -a else a }
                    }
                    val magnitude = ((biggest?.get(4) as? Number)?.toLong())
                        ?.let { if (it < 0) -it else it }
                    SimilarItem(
                        kind = kind,
                        id = id,
                        title = head[2]?.toString().orEmpty(),
                        subtitle = head[3]?.toString().orEmpty(),
                        date = (head[1] as? Number)?.toLong() ?: 0L,
                        amountMinor = magnitude,
                        commodity = biggest?.get(5)?.toString(),
                        distance = 0.0,
                    )
                }
            }

            EmbedKind.Notification -> d.query(
                "select n.id, n.title, n.text, n.post_time, coalesce(app.name, n.package_name)" +
                    " from notifications n left join applications app on app.id = n.package_name" +
                    " where n.id in ($inList)",
                null,
            ) { rows ->
                rows.filter { it.size >= 5 }.mapNotNull { row ->
                    val id = row[0]?.toString() ?: return@mapNotNull null
                    val title = row[1]?.toString().orEmpty()
                    val text = row[2]?.toString().orEmpty()
                    val money = biggestMoney("$title $text")
                    id to SimilarItem(
                        kind = kind,
                        id = id,
                        title = title,
                        subtitle = text,
                        date = (row[3] as? Number)?.toLong() ?: 0L,
                        amountMinor = money?.amountMinor,
                        commodity = money?.commodity,
                        distance = 0.0,
                    )
                }.toMap()
            }

            EmbedKind.Email -> d.query(
                "select id, from_email, subject, received_at, body_text, body_html" +
                    " from emails where id in ($inList)",
                null,
            ) { rows ->
                rows.filter { it.size >= 6 }.mapNotNull { row ->
                    val id = row[0]?.toString() ?: return@mapNotNull null
                    val subject = decodeMimeHeader(row[2]?.toString().orEmpty())
                    val body = emailPlainText(
                        row[5]?.toString()?.takeIf { it.isNotBlank() } ?: row[4]?.toString().orEmpty(),
                    )
                    val money = biggestMoney("$subject $body")
                    id to SimilarItem(
                        kind = kind,
                        id = id,
                        title = subject,
                        subtitle = row[1]?.toString().orEmpty(),
                        date = (row[3] as? Number)?.toLong() ?: 0L,
                        amountMinor = money?.amountMinor,
                        commodity = money?.commodity,
                        distance = 0.0,
                    )
                }.toMap()
            }
        }
        // The SQL already ordered by distance; the hydration lookup must not
        // reorder it.
        return distances.mapNotNull { (id, d0) -> items[id]?.copy(distance = d0) }
    }

    private companion object {
        /**
         * How loud the money is allowed to be next to the text. At 0.35 an
         * amount twice as big costs ~0.24 — enough to drop a neighbour a few
         * places, never enough to promote a row that reads like nothing.
         */
        const val AMOUNT_WEIGHT = 0.35

        /** Texts per worker call; the endpoint caps at 128. */
        const val BATCH = 100

        /** Rows scanned per sweep and per table. */
        const val MAX_SCAN = 2_000

        /** Past this an email is boilerplate. */
        const val MAX_BODY = 1_500

        /** Cheap SQL net; [hasAmount] is the real test (see [pending]). */
        const val MONEY_LIKE =
            "(n.title like '%\$%' or n.text like '%\$%'" +
                " or n.title like '%ARS%' or n.text like '%ARS%'" +
                " or n.title like '%USD%' or n.text like '%USD%')"
        const val MONEY_LIKE_EMAIL =
            "(subject like '%\$%' or body_text like '%\$%' or body_html like '%\$%'" +
                " or subject like '%ARS%' or body_text like '%ARS%' or body_html like '%ARS%')"
    }
}

/**
 * `[0.1,0.2,…]`, which is what `vector32()` parses. Formatted by hand because
 * the sync engine takes the literal as text and Kotlin's default Double
 * rendering ("1.0E-4") is not something SQLite's parser accepts.
 */
/**
 * The largest amount mentioned in [text], which on a bank message is the
 * movement itself — the smaller numbers around it are balances, instalments or
 * card digits. Used to put money next to a notification or a mail, for the
 * reader and for the re-rank.
 */
private fun biggestMoney(text: String): ParsedMoney? =
    MONEY_TEXT.findAll(text)
        .mapNotNull { parseMoney(it.value) }
        .maxByOrNull { it.amountMinor }
        ?.takeIf { it.amountMinor > 0L }

internal fun List<Double>.toVectorLiteral(): String =
    joinToString(",", prefix = "[", postfix = "]") { it.toFixedLiteral() }

private fun Double.toFixedLiteral(): String {
    if (this.isNaN() || this.isInfinite()) return "0"
    val scaled = (this * 1_000_000.0).toLong()
    val sign = if (scaled < 0) "-" else ""
    val abs = if (scaled < 0) -scaled else scaled
    val whole = abs / 1_000_000
    val frac = (abs % 1_000_000).toString().padStart(6, '0')
    return "$sign$whole.$frac"
}
