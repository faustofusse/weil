package ar.fausto.weil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_undo
import weil.app.sharedui.generated.resources.broker_positions_hint
import weil.app.sharedui.generated.resources.broker_rebuild_done
import weil.app.sharedui.generated.resources.broker_rebuild_replaces
import weil.app.sharedui.generated.resources.broker_adjust
import weil.app.sharedui.generated.resources.broker_adjusted
import weil.app.sharedui.generated.resources.broker_all_match
import weil.app.sharedui.generated.resources.broker_counterpart_from
import weil.app.sharedui.generated.resources.broker_counterpart_from_title
import weil.app.sharedui.generated.resources.broker_counterpart_to
import weil.app.sharedui.generated.resources.broker_counterpart_hint
import weil.app.sharedui.generated.resources.broker_counterpart_to_title
import weil.app.sharedui.generated.resources.broker_difference_row
import weil.app.sharedui.generated.resources.broker_differences_body
import weil.app.sharedui.generated.resources.broker_differences_title
import weil.app.sharedui.generated.resources.broker_import_action
import weil.app.sharedui.generated.resources.broker_import_count
import weil.app.sharedui.generated.resources.broker_import_done
import weil.app.sharedui.generated.resources.broker_import_nothing
import weil.app.sharedui.generated.resources.broker_import_title
import weil.app.sharedui.generated.resources.broker_issues_title

/**
 * Review of a [BrokerPlan] before anything is written (plans/inversiones-brokers.md,
 * phase 4): the movements it would book, what the broker says that the
 * ledger would still disagree with, and what couldn't be translated. One
 * button writes the movements; the Snackbar undoes them.
 *
 * Broker-agnostic on purpose: IOL's sync and IBKR's report file (phase 3)
 * both end here.
 */
@Composable
fun BrokerImportScreen(
    route: BrokerImportRoute,
    ledger: TransactionsRepository,
    /** The account tree, for picking where a deposit came from. */
    tree: List<AccountNode>,
    apply: suspend (BrokerPlan) -> List<String>,
    /** Settles a cash difference against the opening balance; returns the new transaction's id. */
    adjust: suspend (BalanceDifference) -> String,
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val plan = route.plan
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val count = plan.transactions.size
    val doneMessage = stringResource(Res.string.broker_import_done, count)
    val rebuiltMessage = stringResource(Res.string.broker_rebuild_done, route.brokerName)
    val undoLabel = stringResource(Res.string.action_undo)
    val adjustedMessage = stringResource(Res.string.broker_adjusted)
    // Differences settled on this screen: they leave the list, and come back
    // if the adjustment is undone.
    var adjusted by remember { mutableStateOf<Set<BalanceDifference>>(emptySet()) }
    var adjusting by remember { mutableStateOf<BalanceDifference?>(null) }
    val differences = plan.differences.filter { it !in adjusted }
    // Where each deposit/withdrawal really came from or went to, by ref.
    // Unpicked ones stay on the opening balance (the planner's fallback).
    var counterparts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var picking by remember { mutableStateOf<PlannedTransaction?>(null) }
    // Shortest unique names, like the journal's rows: "Saldo inicial", or
    // "Banco › Dólares" only when another "Dólares" exists.
    val paths = remember(tree) {
        val nodes = tree.flatMap { it.selfAndDescendants }
        val repeated = nodes.groupingBy { it.account.name.lowercase() }.eachCount().filterValues { it > 1 }.keys
        nodes.associate { n ->
            n.account.id to (if (n.account.name.lowercase() in repeated) n.path.displayPath() else n.account.name).censored()
        }
    }
    val needCounterpart = plan.transactions.count { it.needsCounterpart }
    val scales = route.scales + plan.newCommodities.associate { it.id to it.scale }
    val symbols = plan.newCommodities.associate { it.id to it.symbol }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.broker_import_title, route.brokerName),
                onNavigateBack = onNavigateBack,
            )
        },
        bottomBar = {
            if (count > 0) {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(16.dp),
                    ) {
                        error?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        Button(
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        val chosen = plan.copy(
                                            transactions = plan.transactions.map { p ->
                                                p.ref?.let { counterparts[it] }
                                                    ?.let { p.withCounterpart(route.accounts.opening, it) } ?: p
                                            },
                                        )
                                        val ids = apply(chosen)
                                        onDone()
                                        // A rebuild deleted what it replaced: undoing
                                        // it would leave neither, so it has no undo.
                                        if (plan.replaces.isEmpty()) {
                                            Feedback.undoable(doneMessage, undoLabel) { ledger.deleteAll(ids) }
                                        } else {
                                            Feedback.show(rebuiltMessage)
                                        }
                                    } catch (e: Throwable) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        error = e.message ?: e.toString()
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text(stringResource(Res.string.broker_import_action, count))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "count") {
                Text(
                    when {
                        count > 0 -> stringResource(Res.string.broker_import_count, count)
                        differences.isEmpty() && plan.issues.isEmpty() -> stringResource(Res.string.broker_all_match)
                        else -> stringResource(Res.string.broker_import_nothing)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = if (plan.replaces.isEmpty()) 12.dp else 4.dp),
                )
                if (plan.replaces.isNotEmpty()) {
                    Text(
                        stringResource(Res.string.broker_rebuild_replaces, plan.replaces.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            if (differences.isNotEmpty()) {
                item(key = "differences") {
                    Section(stringResource(Res.string.broker_differences_title))
                    Text(
                        stringResource(Res.string.broker_differences_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    // A position that disagrees has no «Ajustar»; on IOL the
                    // usual cause is an opening computed wrong, which a
                    // rebuild recomputes.
                    if (route.provider == IOL_PROVIDER && plan.replaces.isEmpty() &&
                        differences.any { it.accountId !in route.accounts.cash.values }
                    ) {
                        Text(
                            stringResource(Res.string.broker_positions_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    differences.forEach { difference ->
                        val cash = difference.accountId in route.accounts.cash.values
                        val label = if (cash) currencyName(difference.commodity) else symbols[difference.commodity] ?: difference.commodity
                        fun amount(minor: Long) = if (cash) {
                            formatMoney(minor, difference.commodity, signed = true)
                        } else {
                            formatQuantity(minor, scales[difference.commodity] ?: 2)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        ) {
                            Text(
                                stringResource(
                                    Res.string.broker_difference_row,
                                    label,
                                    amount(difference.brokerMinor),
                                    amount(difference.ledgerMinor),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            // Cash only (see BrokersRepository.adjustOpening):
                            // a position that disagrees isn't fixed with money.
                            if (cash) {
                                if (adjusting == difference) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.padding(start = 12.dp).size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    TextButton(
                                        onClick = {
                                            adjusting = difference
                                            scope.launch {
                                                try {
                                                    val id = adjust(difference)
                                                    adjusted = adjusted + difference
                                                    Feedback.undoable(adjustedMessage, undoLabel) {
                                                        ledger.delete(id)
                                                        adjusted = adjusted - difference
                                                    }
                                                } catch (e: Throwable) {
                                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                                    Feedback.show(e.message ?: e.toString())
                                                } finally {
                                                    adjusting = null
                                                }
                                            }
                                        },
                                        enabled = adjusting == null,
                                        modifier = Modifier.padding(start = 8.dp),
                                    ) { Text(stringResource(Res.string.broker_adjust)) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            if (plan.issues.isNotEmpty()) {
                item(key = "issues") {
                    Section(stringResource(Res.string.broker_issues_title))
                    // Developer text (sharedLogic stays untranslated): enough
                    // for a user to see *what* was left out and report it.
                    plan.issues.forEach { issue ->
                        Text(
                            listOfNotNull(issue.ref, issue.message).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            if (needCounterpart > 0) {
                item(key = "counterpart-hint") {
                    Text(
                        stringResource(Res.string.broker_counterpart_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            items(plan.transactions.sortedByDescending { it.transaction.date }, key = { it.ref ?: "opening" }) { planned ->
                val other = if (planned.needsCounterpart) {
                    planned.ref?.let { counterparts[it] } ?: route.accounts.opening
                } else {
                    null
                }
                PlannedRow(
                    planned, route.accounts, scales, symbols,
                    counterpart = other?.let { id -> paths[id]?.let { stringResource(if (planned.isDeposit(route.accounts)) Res.string.broker_counterpart_from else Res.string.broker_counterpart_to, it) } },
                    onClick = if (planned.needsCounterpart) ({ picking = planned }) else null,
                )
            }
        }
    }

    picking?.let { planned ->
        val deposit = planned.isDeposit(route.accounts)
        // The broker's own accounts would make the movement a no-op.
        val own = route.accounts.cash.values.toSet() + route.accounts.holdings
        AccountPickerSheet(
            tree = tree.filter { it.account.type in COUNTERPART_TYPES },
            title = stringResource(if (deposit) Res.string.broker_counterpart_from_title else Res.string.broker_counterpart_to_title),
            subtitle = planned.transaction.payee,
            exclude = own,
            typeOptions = COUNTERPART_TYPES,
            initialType = AccountType.Asset,
            onDismiss = { picking = null },
            onPick = { node ->
                val ref = planned.ref
                if (ref != null) {
                    counterparts = if (node.account.id == route.accounts.opening) counterparts - ref
                    else counterparts + (ref to node.account.id)
                }
                picking = null
            },
        )
    }
}

/** Money into the broker (its cash leg is positive), as opposed to a withdrawal. */
private fun PlannedTransaction.isDeposit(accounts: BrokerAccounts): Boolean =
    transaction.drafts.any { it.accountId in accounts.cash.values && !it.amountText.trim().startsWith("-") }

/** Where a broker's deposit can come from: an own account, or the opening balance. */
private val COUNTERPART_TYPES = listOf(AccountType.Asset, AccountType.Liability, AccountType.Equity)

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/**
 * One movement: payee, day and what moved in the holdings ("+13 MELI"),
 * with the cash it cost or brought on the right.
 */
@Composable
private fun PlannedRow(
    planned: PlannedTransaction,
    accounts: BrokerAccounts,
    scales: Map<String, Int>,
    symbols: Map<String, String>,
    /** Display path of a transfer's other account ("Patrimonio › Saldo inicial"), null for the rest. */
    counterpart: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val postings = remember(planned) { runCatching { resolvePostings(planned.transaction.drafts) }.getOrDefault(emptyList()) }
    val cashAccounts = accounts.cash.values.toSet()
    val holdings = postings.filter { it.accountId == accounts.holdings }
        .joinToString(" · ") { p ->
            (if (p.amountMinor > 0) "+" else "") + formatQuantity(p.amountMinor, scales[p.commodity] ?: 2) + " " +
                (symbols[p.commodity] ?: p.commodity.substringAfter(':'))
        }
    val cash = postings.filter { it.accountId in cashAccounts }
        .groupBy { it.commodity }
        .map { (commodity, legs) -> formatMoney(legs.sumOf { it.amountMinor }, commodity, signed = true) }
    val day = dateInputOf(planned.transaction.date).split('-').reversed().joinToString("/")
    AppListRow(
        icon = if (planned.kind == PlannedKind.Opening) Icons.Filled.AccountTree else Icons.Filled.TrendingUp,
        paint = accountPaint(null),
        title = planned.transaction.payee,
        subtitle = listOf(day, holdings, counterpart.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
        onClick = onClick ?: {},
    ) {
        // One line per currency: a MEP or an opening moves two, and side by
        // side they squeezed the payee down to a few letters.
        Column(horizontalAlignment = Alignment.End) {
            cash.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun currencyName(currency: String): String = when (currency) {
    "ARS" -> "Pesos"
    "USD" -> "Dólares"
    else -> currency
}

/**
 * A quantity at its commodity's scale, es-AR style: "1.923.076", "0,4939".
 * Trailing zeros dropped — a quantity is not money and "13,0000" of a
 * CEDEAR reads as noise.
 */
fun formatQuantity(minor: Long, scale: Int): String {
    val plain = Decimal.ofMinorUnits(minor, scale).stripTrailingZeros().toPlainString()
    val negative = plain.startsWith("-")
    val body = plain.removePrefix("-")
    val whole = body.substringBefore('.')
    val frac = body.substringAfter('.', "")
    val grouped = whole.reversed().chunked(3).joinToString(".").reversed()
    return (if (negative) "-" else "") + grouped + (if (frac.isEmpty()) "" else ",$frac")
}

/**
 * An amount in whatever [commodity] it is: money for a currency, a quantity
 * with its symbol for an instrument ("13 MELI", "0,4939 TTWO") — never an
 * instrument's minor units dressed up as money.
 */
fun formatAmount(minor: Long, commodity: String, valuation: Valuation, signed: Boolean = false): String {
    val info = valuation.commodities[commodity] ?: return formatMoney(minor, commodity, signed)
    val magnitude = if (minor < 0 && !signed) -minor else minor
    return formatQuantity(magnitude, info.scale) + " " + info.symbol
}
