package ar.fausto.weil

import kotlin.js.JsExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JS entry points for the *pure* parts of the app: the ingestion rules
 * ([parseNotification], [parseEmail], [buildInbox]) and the reconciliation
 * matcher ([matchAll]).
 *
 * Used by the dry-run inspector in `app/dryrun`, which replays a statement,
 * a notification or an email exactly as the app would and shows every
 * intermediate value. Running the real Kotlin here — rather than porting the
 * rules to TypeScript — is the whole point: a second implementation would
 * drift, and the drift is precisely what the inspector is supposed to reveal.
 *
 * Everything crosses the boundary as a JSON string. Kotlin/JS cannot export
 * `List`, enums or sealed classes in a usable way, and JSON is also what the
 * inspector wants to display anyway.
 */

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

// ---- inputs ----

@Serializable
private data class NotificationRow(
    val id: String,
    val packageName: String,
    val title: String = "",
    val text: String = "",
    val postTime: Long,
)

@Serializable
private data class EmailRow(
    val id: String,
    val fromEmail: String,
    val subject: String? = null,
    val bodyText: String? = null,
    val bodyHtml: String? = null,
    val receivedAt: Long,
)

// ---- outputs ----

/** One scanned message: what the rules made of it, movement or not. */
@Serializable
private data class ScannedRow(
    val source: EventSource,
    val ref: String,
    val title: String,
    /** The text the rules actually read (decoded, tag-stripped for mail). */
    val body: String,
    val movement: IngestedMovement? = null,
    /** Already linked to a transaction, so the inbox would not offer it. */
    val known: Boolean = false,
)

@Serializable
private data class InboxResult(
    val scanned: List<ScannedRow>,
    val inbox: List<InboxCandidate>,
)

/** Flattened [MatchOutcome]: sealed hierarchies do not survive the boundary. */
@Serializable
private data class MatchDto(
    val transactionId: String,
    val payee: String,
    val date: Long,
    val relation: MatchRelation,
    val score: Int,
    val reasons: List<MatchReason>,
    val retargetPostingId: String? = null,
)

@Serializable
private data class OutcomeDto(
    /** "none" | "confident" | "ambiguous" */
    val kind: String,
    val eventKey: String,
    val matches: List<MatchDto> = emptyList(),
)

private fun ScoredMatch.toDto() = MatchDto(
    transactionId = fact.transactionId,
    payee = fact.payee,
    date = fact.date,
    relation = relation,
    score = score,
    reasons = reasons,
    retargetPostingId = retargetPostingId,
)

@JsExport
object DryRun {

    /** `{id, packageName, title, text, postTime}` → [IngestedMovement] or null. */
    fun parseNotificationJson(rowJson: String): String? {
        val row = json.decodeFromString<NotificationRow>(rowJson)
        val movement = parseNotification(row.id, row.packageName, row.title, row.text, row.postTime)
        return movement?.let { json.encodeToString(IngestedMovement.serializer(), it) }
    }

    /** `{id, fromEmail, subject, bodyText, bodyHtml, receivedAt}` → movement or null. */
    fun parseEmailJson(rowJson: String): String? {
        val row = json.decodeFromString<EmailRow>(rowJson)
        val movement = parseEmail(
            id = row.id,
            fromEmail = row.fromEmail,
            subject = row.subject,
            bodyText = row.bodyText,
            bodyHtml = row.bodyHtml,
            receivedAt = row.receivedAt,
        )
        return movement?.let { json.encodeToString(IngestedMovement.serializer(), it) }
    }

    /** The mail body as the rules see it: quoted-printable decoded, tags gone. */
    fun emailPlainTextJs(raw: String): String = emailPlainText(raw)

    /** RFC 2047 encoded-word subject → readable text. */
    fun decodeMimeHeaderJs(raw: String): String = decodeMimeHeader(raw)

    /** Bank money text ("$ 19.200,50", "U$S100") → `{amountMinor, commodity}`. */
    fun parseMoneyJson(raw: String): String? =
        parseMoney(raw)?.let { json.encodeToString(ParsedMoney.serializer(), it) }

    /** Hints + commodity + the account list → the resolved account id, or null. */
    fun resolveAccountHintJson(hintsJson: String, commodity: String, accountsJson: String): String? =
        resolveAccountHint(
            json.decodeFromString<List<String>>(hintsJson),
            commodity,
            json.decodeFromString<List<Account>>(accountsJson),
        )

    /** Colon-joined path per account id, the spelling the prompt uses. */
    fun accountPathsJson(accountsJson: String): String =
        json.encodeToString(json.decodeFromString<List<Account>>(accountsJson).let { accountPaths(it) })

    /**
     * The full inbox pass: runs the rules over captured notifications and
     * emails, then [buildInbox]. Returns both the recognized rows and every
     * scanned row, so the inspector can show *why* a message was ignored.
     *
     * [knownRefsJson] are the refs already in `transaction_sources`.
     */
    fun buildInboxJson(
        notificationsJson: String,
        emailsJson: String,
        accountsJson: String,
        knownRefsJson: String,
    ): String {
        val accounts = json.decodeFromString<List<Account>>(accountsJson)
        val known = json.decodeFromString<List<String>>(knownRefsJson).toSet()
        val scanned = mutableListOf<ScannedRow>()
        val movements = mutableListOf<Pair<IngestedMovement, String>>()

        for (row in json.decodeFromString<List<NotificationRow>>(notificationsJson)) {
            val movement = parseNotification(row.id, row.packageName, row.title, row.text, row.postTime)
            scanned += ScannedRow(
                source = EventSource.Notification,
                ref = row.id,
                title = row.title,
                body = row.text,
                movement = movement,
                known = row.id in known,
            )
            if (movement != null) movements += movement to row.title
        }

        for (row in json.decodeFromString<List<EmailRow>>(emailsJson)) {
            val title = decodeMimeHeader(row.subject.orEmpty())
            val movement = parseEmail(
                id = row.id,
                fromEmail = row.fromEmail,
                subject = row.subject,
                bodyText = row.bodyText,
                bodyHtml = row.bodyHtml,
                receivedAt = row.receivedAt,
            )
            scanned += ScannedRow(
                source = EventSource.Email,
                ref = row.id,
                title = title,
                // HTML first, exactly like parseEmail reads them.
                body = emailPlainText(row.bodyHtml ?: row.bodyText.orEmpty()),
                movement = movement,
                known = row.id in known,
            )
            if (movement != null) movements += movement to title
        }

        return json.encodeToString(
            InboxResult.serializer(),
            InboxResult(scanned = scanned, inbox = buildInbox(movements, accounts, known)),
        )
    }

    /** An import candidate as the matcher's source-agnostic event. */
    fun candidateToEventJson(
        candidateJson: String,
        sourceRef: String?,
        fallbackOwnAccountId: String?,
    ): String {
        val candidate = json.decodeFromString<ImportCandidate>(candidateJson)
        return json.encodeToString(
            CandidateEvent.serializer(),
            candidate.toEvent(sourceRef, fallbackOwnAccountId),
        )
    }

    /** A recognized notification/email movement as an event. */
    fun movementToEventJson(movementJson: String, ownAccountId: String?): String {
        val movement = json.decodeFromString<IngestedMovement>(movementJson)
        return json.encodeToString(CandidateEvent.serializer(), movement.toEvent(ownAccountId))
    }

    /** Day bucket used by the matcher; handy for explaining a date decision. */
    fun dayIndexOf(epochMs: Double): Double = dayIndex(epochMs.toLong()).toDouble()

    /** [MatchPolicy] defaults, so the inspector shows the window the app uses. */
    fun defaultPolicyJson(): String = json.encodeToString(MatchPolicy.serializer(), MatchPolicy())

    /**
     * The batch matcher: events against a window of ledger facts, one outcome
     * per event, in the same order. Identical to what `ImportReviewScreen`
     * runs before offering crear / asociar / omitir.
     */
    fun matchAllJson(eventsJson: String, factsJson: String, policyJson: String?): String {
        val events = json.decodeFromString<List<CandidateEvent>>(eventsJson)
        val facts = json.decodeFromString<List<LedgerFact>>(factsJson)
        val policy = policyJson?.let { json.decodeFromString<MatchPolicy>(it) } ?: MatchPolicy()
        val outcomes = matchAll(events, facts, policy).mapIndexed { index, outcome ->
            val key = events[index].eventKey
            when (outcome) {
                is MatchOutcome.None -> OutcomeDto("none", key)
                is MatchOutcome.Confident -> OutcomeDto("confident", key, listOf(outcome.match.toDto()))
                is MatchOutcome.Ambiguous -> OutcomeDto("ambiguous", key, outcome.matches.map { it.toDto() })
            }
        }
        return json.encodeToString(outcomes)
    }
}
