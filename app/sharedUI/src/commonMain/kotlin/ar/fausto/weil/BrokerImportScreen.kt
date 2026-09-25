package ar.fausto.weil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
    apply: suspend (BrokerPlan) -> List<String>,
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val plan = route.plan
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val count = plan.transactions.size
    val doneMessage = stringResource(Res.string.broker_import_done, count)
    val undoLabel = stringResource(Res.string.action_undo)
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
                                        val ids = apply(plan)
                                        onDone()
                                        Feedback.undoable(doneMessage, undoLabel) { ledger.deleteAll(ids) }
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
                    if (count == 0) {
                        stringResource(Res.string.broker_import_nothing)
                    } else {
                        stringResource(Res.string.broker_import_count, count)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            if (plan.differences.isNotEmpty()) {
                item(key = "differences") {
                    Section(stringResource(Res.string.broker_differences_title))
                    Text(
                        stringResource(Res.string.broker_differences_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    plan.differences.forEach { difference ->
                        val cash = difference.accountId != route.accounts.holdings
                        val label = if (cash) currencyName(difference.commodity) else symbols[difference.commodity] ?: difference.commodity
                        fun amount(minor: Long) = if (cash) {
                            formatMoney(minor, difference.commodity, signed = true)
                        } else {
                            formatQuantity(minor, scales[difference.commodity] ?: 2)
                        }
                        Text(
                            stringResource(
                                Res.string.broker_difference_row,
                                label,
                                amount(difference.brokerMinor),
                                amount(difference.ledgerMinor),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
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
            items(plan.transactions.sortedByDescending { it.transaction.date }, key = { it.ref ?: "opening" }) { planned ->
                PlannedRow(planned, route.accounts, scales, symbols)
            }
        }
    }
}

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
        subtitle = listOf(day, holdings).filter { it.isNotBlank() }.joinToString(" · "),
        onClick = {},
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
