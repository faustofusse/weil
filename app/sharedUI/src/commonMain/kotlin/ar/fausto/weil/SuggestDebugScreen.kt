package ar.fausto.weil

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.suggest_debug_copy
import weil.app.sharedui.generated.resources.suggest_debug_create
import weil.app.sharedui.generated.resources.suggest_debug_title

/**
 * What the two models were asked and what they answered, for one captured
 * notification. It writes nothing and goes nowhere: it exists so the pipeline
 * can be *watched* before it is allowed to propose a transaction.
 *
 * Deliberately raw — JSON in monospace, one block per stage, in the order the
 * stages ran. A summarized view would hide exactly the thing being debugged:
 * every wrong answer here has so far been a wrong *question*, or a context
 * that did not contain what the answer needed.
 */
@Composable
fun SuggestDebugScreen(
    suggestions: SuggestTracer,
    id: String,
    onNavigateBack: () -> Unit,
    /** Hands the proposed row to the review screen. Nothing is written here. */
    onReview: (ImportCandidate) -> Unit = {},
) {
    var trace by remember(id) { mutableStateOf<SuggestTrace?>(null) }
    var running by remember(id) { mutableStateOf(true) }
    var failure by remember(id) { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(id) {
        running = true
        failure = null
        try {
            trace = suggestions.traceNotification(id)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            failure = e.message ?: e.toString()
        }
        running = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(
            title = stringResource(Res.string.suggest_debug_title),
            onNavigateBack = onNavigateBack,
            actions = {
                val current = trace
                if (current != null) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(plainText(current))) }) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(Res.string.suggest_debug_copy),
                        )
                    }
                }
            },
        )

        if (running) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Leyendo el mensaje…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        val current = trace
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            failure?.let { message ->
                item { Block("error", message, open = true) }
            }
            if (current == null) return@LazyColumn

            current.error?.let { message ->
                item { Block("error", message, open = true) }
            }

            item {
                Text(
                    "gemini ${current.readMs} ms · device ${current.retrievalMs} ms · " +
                        "jev ${current.decisionMs} ms · total ${current.totalMs} ms",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            item { Block("1 · aviso", notificationText(current), open = true) }
            item { Block("2 · gemini: pedido y respuesta", current.readDebug ?: "—") }
            item { Block("3 · gemini: lectura", readText(current.read), open = true) }
            current.alt?.let { alt ->
                item {
                    Block(
                        "3b · ${alt.model.substringAfterLast('/')}: la misma lectura",
                        altText(alt, current),
                        open = true,
                    )
                }
            }
            item { Block("4 · recuperación (device)", retrievalText(current), open = true) }
            item { Block("5 · jev: state y questions", current.decisionDebug ?: "—") }
            item { Block("6 · jev: decisión", decisionText(current.decision), open = true) }

            // The end of the path, as it will be: the proposal goes to the
            // same review screen the statement import uses, which is the one
            // place in the app allowed to write a transaction.
            val candidate = current.candidate()
            item { Block("7 · candidato", candidateText(current, candidate), open = true) }
            item {
                if (candidate == null) {
                    Text(
                        "Sin importe: no hay nada que proponer.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Button(
                        onClick = { onReview(candidate) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(Res.string.suggest_debug_create))
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/** One collapsible chunk of the trace. Closed by default except the readings. */
@Composable
private fun Block(title: String, body: String, open: Boolean = false) {
    var expanded by remember(title) { mutableStateOf(open) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GroupRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { expanded = !expanded }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "−" else "+",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            // Horizontal scroll rather than wrapping: JSON that wraps at 40
            // columns is unreadable, and every block here is JSON.
            Text(
                body,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

private fun notificationText(trace: SuggestTrace): String {
    val n = trace.notification ?: return "—"
    return buildString {
        appendLine("app:   ${n.appName} (${n.packageName})")
        appendLine("fecha: ${formatTimestamp(n.postTime)}")
        appendLine("cat:   ${n.category ?: "—"}")
        appendLine()
        appendLine(n.title)
        append(n.text)
    }
}

private fun readText(read: ReadResponse?): String {
    if (read == null) return "—"
    return buildString {
        appendLine("isMovement:  ${read.isMovement}")
        appendLine("direction:   ${read.direction}")
        appendLine("amount:      ${read.amount} ${read.commodity}")
        appendLine("payee:       ${read.payee}")
        appendLine("account:     ${read.account ?: "—"}")
        appendLine("note:        ${read.note ?: "—"}")
        append("normalized:  ${read.normalized}")
    }
}

/**
 * The second reader's answer next to the first one's, field by field, with a
 * mark on every field where they differ. Comparing two models on a task is
 * comparing their *disagreements*: the fields both get right say nothing.
 */
private fun altText(alt: AltReading, trace: SuggestTrace): String = buildString {
    appendLine(
        "${alt.latencyMs} ms   contra ${trace.read?.readerMs ?: 0} ms de gemini " +
            "(los dos medidos dentro del worker; ${trace.readMs} ms es la etapa entera, " +
            "y con compare=1 es la del más lento de los dos)",
    )
    alt.error?.let {
        append("error: $it")
        return@buildString
    }
    val read = alt.reading
    if (read == null) {
        append("sin JSON parseable:\n${alt.raw?.take(600) ?: "—"}")
        return@buildString
    }
    val mine = trace.read
    fun line(label: String, a: String?, b: String?) {
        val differs = (a ?: "") != (b ?: "")
        appendLine("$label ${a ?: "—"}${if (differs) "   ≠ ${b ?: "—"}" else ""}")
    }
    appendLine("campo        este modelo   ≠ gemini")
    line("isMovement: ", read.isMovement.toString(), mine?.isMovement?.toString())
    line("direction:  ", read.direction, mine?.direction)
    line("amount:     ", "${read.amount} ${read.commodity}", mine?.let { "${it.amount} ${it.commodity}" })
    line("payee:      ", read.payee, mine?.payee)
    line("account:    ", read.account, mine?.account)
    append("normalized:  ${read.normalized}")
}

private fun retrievalText(trace: SuggestTrace): String = buildString {
    appendLine("evento para el matcher:")
    trace.event?.let {
        appendLine("  ${it.amountMinor} ${it.commodity} · ${it.rawPayee} · cuenta ${it.ownAccountId ?: "—"}")
        appendLine("  eventKey ${it.eventKey}")
    }
    appendLine()
    appendLine("matcher: ${matchText(trace.match)}")
    appendLine()
    appendLine("precedentes (notificaciones parecidas, por la frase normalizada):")
    if (trace.precedents.isEmpty()) appendLine("  ninguno")
    for (p in trace.precedents) {
        val distance = ((p.distance()) * 1000).toInt() / 1000.0
        appendLine("  $distance  ${p.item.title.take(60)}")
        appendLine("           vinculada a: ${p.recordedIn.ifEmpty { listOf("—") }.joinToString()}")
    }
    appendLine()
    appendLine("transacciones parecidas:")
    if (trace.similarTransactions.isEmpty()) appendLine("  ninguna")
    for (s in trace.similarTransactions) {
        val distance = ((s.distance) * 1000).toInt() / 1000.0
        appendLine("  $distance  ${s.title.take(60)} · ${s.amountMinor?.let { formatMinorUnits(it) } ?: "—"}")
    }
}

private fun Precedent.distance(): Double = item.distance

private fun matchText(match: MatchOutcome?): String = when (match) {
    null -> "—"
    MatchOutcome.None -> "None (nada comparable en la ventana)"
    is MatchOutcome.Confident ->
        "Confident ${match.match.relation} score ${match.match.score} " +
            "(${match.match.reasons.joinToString()}) → ${match.match.fact.payee}"
    is MatchOutcome.Ambiguous ->
        "Ambiguous:\n" + match.matches.joinToString("\n") {
            "    ${it.score} ${it.relation} ${it.fact.payee} (${it.reasons.joinToString()})"
        }
}

private fun decisionText(decision: AccountsResponse?): String {
    if (decision == null) return "—"
    fun line(label: String, picked: PickedAccount?): String {
        if (picked == null) return "$label: —"
        val head = "$label: ${picked.path ?: "ninguna"} (conf ${round2(picked.confidence)})"
        val ranked = picked.ranked.joinToString(", ") { "${it.path} ${round2(it.probability)}" }
        return if (ranked.isBlank()) head else "$head\n    $ranked"
    }
    return buildString {
        appendLine("is_movement:      ${decision.isMovement?.let { round2(it) } ?: "—"}")
        appendLine("already_recorded: ${decision.alreadyRecorded?.let { round2(it) } ?: "—"}")
        appendLine(line("direction", decision.direction))
        appendLine(line("my_account", decision.myAccount))
        appendLine(line("transfer_to", decision.transferDestination))
        appendLine(line("expense_cat", decision.expenseCategory))
        appendLine(line("income_cat", decision.incomeCategory))
        append(line("duplicate_of", decision.duplicateOf))
    }
}

/**
 * The row that would be written, and where each field came from. Without it
 * the trace stops one step before the only thing that reaches the ledger, and
 * a field that flips between the answer and the row has nowhere to show up.
 */
private fun candidateText(trace: SuggestTrace, candidate: ImportCandidate?): String {
    if (candidate == null) return "sin importe — no hay candidato"
    val split = candidate.splits.firstOrNull()
    return buildString {
        val jev = trace.decision?.direction?.path
        val gemini = trace.read?.direction
        appendLine("direction:  ${candidate.direction}  (manda gemini: lo dice la frase)")
        appendLine("  gemini:   ${gemini ?: "—"}")
        appendLine(
            "  jev:      ${jev ?: "—"}" +
                if (jev != null && gemini != null && jev != gemini) "   ⚠ no coinciden" else "",
        )
        appendLine("payee:      ${candidate.payee.ifBlank { "(vacío)" }}")
        appendLine("monto:      ${split?.amountMinor} ${candidate.commodity}")
        appendLine("cuenta:     ${candidate.accountPath ?: "(vacía → la por defecto)"}")
        append("categoría:  ${split?.categoryPath ?: "(vacía → la por defecto)"}")
    }
}

private fun round2(value: Double): String = ((value * 100).toInt() / 100.0).toString()

/** The whole trace as one pasteable blob. */
private fun plainText(trace: SuggestTrace): String = buildString {
    appendLine("== aviso =="); appendLine(notificationText(trace)); appendLine()
    appendLine("== gemini (debug) =="); appendLine(trace.readDebug ?: "—"); appendLine()
    appendLine("== lectura =="); appendLine(readText(trace.read)); appendLine()
    trace.alt?.let { appendLine("== lectura (${it.model}) =="); appendLine(altText(it, trace)); appendLine() }
    appendLine("== recuperación =="); appendLine(retrievalText(trace)); appendLine()
    appendLine("== jev (debug) =="); appendLine(trace.decisionDebug ?: "—"); appendLine()
    appendLine("== decisión =="); appendLine(decisionText(trace.decision)); appendLine()
    appendLine("== candidato =="); appendLine(candidateText(trace, trace.candidate())); appendLine()
    appendLine("tiempos: gemini ${trace.readMs} ms, device ${trace.retrievalMs} ms, jev ${trace.decisionMs} ms")
}
