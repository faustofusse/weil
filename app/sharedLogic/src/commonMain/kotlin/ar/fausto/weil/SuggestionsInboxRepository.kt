package ar.fausto.weil

import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The quiet half of the notification→transaction path.
 *
 * When a notification arrives, the listener runs the suggest pipeline
 * (read → retrieval → Jev) in the background and hands the finished
 * [SuggestTrace] to [record]. No banner, no sound: the proposed candidate is
 * parked in the `suggestions` table and stays there until the user opens the
 * inbox, where it shows up next to the template-parsed rows. Nothing here
 * ever writes to the ledger — approving still goes through the review screen.
 *
 * What does *not* get recorded:
 *
 * - messages the reader says are not movements (promotions die here);
 * - readings with no usable amount (no candidate to offer);
 * - movements the matcher recognizes as already imported (nothing to ask).
 *
 * Everything else — including confident duplicates, which default to
 * "asociar" on the review screen — is kept. A wrong suggestion costs a row
 * the user skips; a missing one costs a movement nobody sees.
 */
class SuggestionsInboxRepository(
    private val db: DatabaseProvider,
    private val ledger: TransactionsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parks one finished run, if it is worth offering later. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun record(notificationId: String, trace: SuggestTrace) {
        val candidate = trace.candidate()
        val eventKey = trace.event?.eventKey
        if (!worthRecording(trace.read, trace.match, candidate) || eventKey == null) return
        db.use { d ->
            d.execute(
                "insert or ignore into suggestions(id, kind, ref, event_key, candidate, created_at)" +
                    " values(:id, :kind, :ref, :event_key, :candidate, :created_at)",
                mapOf(
                    ":id" to Uuid.random().toString(),
                    ":kind" to EventSource.Notification.db,
                    ":ref" to notificationId,
                    ":event_key" to eventKey,
                    ":candidate" to json.encodeToString(ImportCandidate.serializer(), candidate!!),
                    ":created_at" to epochMillis(),
                ),
            )
        }
    }

    /**
     * Recorded suggestions not yet acted on, freshest first.
     *
     * Reading is also the garbage collection, because two clocks run against
     * these rows: the user approving one (its ref lands in
     * `transaction_sources`) and the same movement arriving through another
     * door (its fingerprint does — the WhatsApp bot writes straight to the
     * ledger). Both are checked against live state here, so a stale row is
     * deleted on the read that would have shown it rather than offered.
     *
     * Two devices that captured the same alert each record their own row;
     * `distinctBy` the fingerprint keeps the freshest.
     */
    suspend fun pending(): List<InboxCandidate> {
        val rows = db.useForRead { d ->
            d.query(
                "select ref, event_key, candidate from suggestions" +
                    " where kind = '${EventSource.Notification.db}' order by created_at desc limit $MAX_ROWS",
                null,
            ) { rs ->
                rs.mapNotNull { r ->
                    val ref = r.getOrNull(0)?.toString() ?: return@mapNotNull null
                    val key = r.getOrNull(1)?.toString() ?: return@mapNotNull null
                    val body = r.getOrNull(2)?.toString() ?: return@mapNotNull null
                    StoredRow(ref, key, body)
                }.toList()
            }
        }
        if (rows.isEmpty()) return emptyList()

        val knownRefs = ledger.knownSourceRefs(rows.map { it.ref })
        val knownKeys = knownEventKeys(rows.map { it.eventKey })
        val fresh = rows.filter { it.ref !in knownRefs && it.eventKey !in knownKeys }
        if (fresh.size != rows.size) {
            val stale = rows.filter { it.ref in knownRefs || it.eventKey in knownKeys }.map { it.ref }
            db.use { d ->
                for (ref in stale) {
                    d.execute(
                        "delete from suggestions where kind = '${EventSource.Notification.db}' and ref = :ref",
                        mapOf(":ref" to ref),
                    )
                }
            }
        }

        val titles = notificationTitles(fresh.map { it.ref })
        return fresh.distinctBy { it.eventKey }.mapNotNull { row ->
            val candidate = runCatching {
                json.decodeFromString(ImportCandidate.serializer(), row.body)
            }.getOrNull() ?: return@mapNotNull null
            InboxCandidate(
                candidate = candidate,
                kind = EventSource.Notification,
                ref = row.ref,
                ruleId = RULE_ID,
                title = titles[row.ref] ?: candidate.payee,
            )
        }
    }

    /** The user said no; the delete syncs, so it stays said on every device. */
    suspend fun dismiss(ref: String) {
        db.use { d ->
            d.execute(
                "delete from suggestions where kind = '${EventSource.Notification.db}' and ref = :ref",
                mapOf(":ref" to ref),
            )
        }
    }

    /** Fingerprints already attached to a ledger row (the bot got there first). */
    private suspend fun knownEventKeys(keys: List<String>): Set<String> {
        if (keys.isEmpty()) return emptySet()
        return db.useForRead { d ->
            d.query(
                "select event_key from transaction_sources where event_key in (${quoteList(keys)})",
                null,
            ) { rs -> rs.mapNotNull { it.firstOrNull()?.toString() }.toSet() }
        }
    }

    private suspend fun notificationTitles(refs: List<String>): Map<String, String> {
        if (refs.isEmpty()) return emptyMap()
        return db.useForRead { d ->
            d.query(
                "select id, title from notifications where id in (${quoteList(refs)})",
                null,
            ) { rs ->
                rs.mapNotNull { r ->
                    val id = r.getOrNull(0)?.toString() ?: return@mapNotNull null
                    id to (r.getOrNull(1)?.toString() ?: "")
                }.toList().toMap()
            }
        }
    }

    private data class StoredRow(val ref: String, val eventKey: String, val body: String)

    /** Single-quoted list literal, the way TransactionsRepository.quoteList builds it. */
    private fun quoteList(values: List<String>): String =
        values.joinToString(", ") { "'${it.replace("'", "''")}'" }

    private companion object {
        const val MAX_ROWS = 100
        const val RULE_ID = "sugerida"
    }
}

/**
 * Whether a finished run is worth parking. Pure, so the rule is testable
 * without a database.
 *
 * [MatchOutcome.Confident] with [MatchRelation.AlreadyImported] is the one
 * match that means "the user already dealt with this exact message" — every
 * other outcome still has something to ask (create, or attach as a source).
 */
internal fun worthRecording(
    read: ReadResponse?,
    match: MatchOutcome?,
    candidate: ImportCandidate?,
): Boolean {
    if (read?.isMovement != true) return false
    if (candidate == null) return false
    if (match is MatchOutcome.Confident && match.match.relation == MatchRelation.AlreadyImported) {
        return false
    }
    return true
}
