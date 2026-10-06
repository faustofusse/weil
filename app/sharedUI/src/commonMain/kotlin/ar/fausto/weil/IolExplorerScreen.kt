package ar.fausto.weil

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_cancel
import weil.app.sharedui.generated.resources.iol_d_board
import weil.app.sharedui.generated.resources.iol_d_fund
import weil.app.sharedui.generated.resources.iol_d_fund_managers
import weil.app.sharedui.generated.resources.iol_d_fund_types
import weil.app.sharedui.generated.resources.iol_d_funds
import weil.app.sharedui.generated.resources.iol_d_history_month
import weil.app.sharedui.generated.resources.iol_d_history_year
import weil.app.sharedui.generated.resources.iol_d_instruments
import weil.app.sharedui.generated.resources.iol_d_investor_test
import weil.app.sharedui.generated.resources.iol_d_mep
import weil.app.sharedui.generated.resources.iol_d_mep_simple
import weil.app.sharedui.generated.resources.iol_d_notifications
import weil.app.sharedui.generated.resources.iol_d_operations
import weil.app.sharedui.generated.resources.iol_d_options
import weil.app.sharedui.generated.resources.iol_d_panels
import weil.app.sharedui.generated.resources.iol_d_pending
import weil.app.sharedui.generated.resources.iol_d_portfolio_ar
import weil.app.sharedui.generated.resources.iol_d_portfolio_us
import weil.app.sharedui.generated.resources.iol_d_profile
import weil.app.sharedui.generated.resources.iol_d_quote
import weil.app.sharedui.generated.resources.iol_d_quote_detail
import weil.app.sharedui.generated.resources.iol_d_quote_mobile
import weil.app.sharedui.generated.resources.iol_d_state
import weil.app.sharedui.generated.resources.iol_d_title
import weil.app.sharedui.generated.resources.iol_explorer_advanced
import weil.app.sharedui.generated.resources.iol_explorer_custom
import weil.app.sharedui.generated.resources.iol_explorer_custom_body
import weil.app.sharedui.generated.resources.iol_explorer_intro
import weil.app.sharedui.generated.resources.iol_explorer_no_securities
import weil.app.sharedui.generated.resources.iol_explorer_other_symbol
import weil.app.sharedui.generated.resources.iol_explorer_other_symbol_body
import weil.app.sharedui.generated.resources.iol_explorer_path
import weil.app.sharedui.generated.resources.iol_explorer_run
import weil.app.sharedui.generated.resources.iol_explorer_symbol
import weil.app.sharedui.generated.resources.iol_explorer_title
import weil.app.sharedui.generated.resources.iol_explorer_your_account
import weil.app.sharedui.generated.resources.iol_explorer_your_securities
import weil.app.sharedui.generated.resources.iol_group_funds
import weil.app.sharedui.generated.resources.iol_group_market
import weil.app.sharedui.generated.resources.iol_q_bonds
import weil.app.sharedui.generated.resources.iol_q_cauciones
import weil.app.sharedui.generated.resources.iol_q_cedears
import weil.app.sharedui.generated.resources.iol_q_fund
import weil.app.sharedui.generated.resources.iol_q_fund_managers
import weil.app.sharedui.generated.resources.iol_q_fund_types
import weil.app.sharedui.generated.resources.iol_q_funds
import weil.app.sharedui.generated.resources.iol_q_history_month
import weil.app.sharedui.generated.resources.iol_q_history_year
import weil.app.sharedui.generated.resources.iol_q_instruments
import weil.app.sharedui.generated.resources.iol_q_investor_test
import weil.app.sharedui.generated.resources.iol_q_letters
import weil.app.sharedui.generated.resources.iol_q_mep
import weil.app.sharedui.generated.resources.iol_q_mep_simple
import weil.app.sharedui.generated.resources.iol_q_notifications
import weil.app.sharedui.generated.resources.iol_q_ons
import weil.app.sharedui.generated.resources.iol_q_operations
import weil.app.sharedui.generated.resources.iol_q_options
import weil.app.sharedui.generated.resources.iol_q_panels
import weil.app.sharedui.generated.resources.iol_q_pending
import weil.app.sharedui.generated.resources.iol_q_portfolio_ar
import weil.app.sharedui.generated.resources.iol_q_portfolio_us
import weil.app.sharedui.generated.resources.iol_q_profile
import weil.app.sharedui.generated.resources.iol_q_quote
import weil.app.sharedui.generated.resources.iol_q_quote_detail
import weil.app.sharedui.generated.resources.iol_q_quote_mobile
import weil.app.sharedui.generated.resources.iol_q_state
import weil.app.sharedui.generated.resources.iol_q_stocks
import weil.app.sharedui.generated.resources.iol_q_title
import weil.app.sharedui.generated.resources.iol_q_us_stocks

/*
 * The IOL explorer is three kinds of screen, each doing one thing:
 *
 *  - this catalog: what can be asked, in words, grouped the way an investor
 *    thinks (my account, my securities, the market, funds);
 *  - [IolSymbolScreen]: what can be asked about one security;
 *  - [IolResponseScreen]: one answer, and any list or item inside it, each
 *    a screen of its own.
 *
 * Read-only throughout: every request goes through [IolRepository.explore],
 * which GETs under `/api/` and nothing else.
 */

/** One thing the explorer can ask IOL, described for someone who is not reading the swagger. */
private class ExplorerQuery(val title: StringResource, val description: StringResource, val path: String, val icon: ImageVector)

@Composable
fun IolExplorerScreen(
    /** What the user holds at IOL, each a door to [IolSymbolScreen]. */
    held: List<ExplorerInstrument>,
    onNavigateBack: () -> Unit,
    onOpen: (IolResponseRoute) -> Unit,
    onSymbol: (IolSymbolRoute) -> Unit,
) {
    val dates = remember { ExplorerDates.now() }
    var askingSymbol by rememberSaveable { mutableStateOf(false) }
    var askingPath by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(Res.string.iol_explorer_title), onNavigateBack = onNavigateBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item(key = "intro") {
                Text(
                    stringResource(Res.string.iol_explorer_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            querySection("account", Res.string.iol_explorer_your_account, accountQueries(dates), onOpen)

            item(key = "securities") { SectionHeader(title = stringResource(Res.string.iol_explorer_your_securities)) }
            if (held.isEmpty()) {
                item(key = "no-securities") {
                    Text(
                        stringResource(Res.string.iol_explorer_no_securities),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            items(held, key = { "held-${it.market}-${it.symbol}" }) { instrument ->
                ExplorerRow(
                    icon = if (instrument.kind == "fci") Icons.Filled.Category else Icons.Filled.TrendingUp,
                    title = instrument.symbol,
                    subtitle = instrument.name,
                    onClick = { onSymbol(IolSymbolRoute(instrument.symbol, instrument.market, instrument.kind, instrument.name)) },
                )
            }
            item(key = "other-symbol") {
                ExplorerRow(
                    icon = Icons.Filled.Search,
                    title = stringResource(Res.string.iol_explorer_other_symbol),
                    subtitle = stringResource(Res.string.iol_explorer_other_symbol_body),
                    onClick = { askingSymbol = true },
                )
            }

            querySection("market", Res.string.iol_group_market, marketQueries(), onOpen)
            querySection("funds", Res.string.iol_group_funds, fundQueries(), onOpen)

            item(key = "advanced") { SectionHeader(title = stringResource(Res.string.iol_explorer_advanced)) }
            item(key = "custom") {
                ExplorerRow(
                    icon = Icons.Filled.Tune,
                    title = stringResource(Res.string.iol_explorer_custom),
                    subtitle = stringResource(Res.string.iol_explorer_custom_body),
                    onClick = { askingPath = true },
                )
            }
        }
    }

    if (askingSymbol) {
        AskDialog(
            title = stringResource(Res.string.iol_explorer_other_symbol),
            label = stringResource(Res.string.iol_explorer_symbol),
            initial = "",
            monospace = false,
            validate = { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() } },
            transform = { it.filter { c -> c.isLetterOrDigit() }.uppercase() },
            onDone = { askingSymbol = false; onSymbol(IolSymbolRoute(it)) },
            onDismiss = { askingSymbol = false },
        )
    }
    if (askingPath) {
        AskDialog(
            title = stringResource(Res.string.iol_explorer_custom),
            label = stringResource(Res.string.iol_explorer_path),
            initial = "/api/v2/",
            monospace = true,
            validate = { runCatching { iolExplorerPath(it) }.isSuccess },
            transform = { it },
            onDone = { path ->
                askingPath = false
                val checked = iolExplorerPath(path)
                onOpen(IolResponseRoute(checked, checked.substringBefore('?').removePrefix("/api/v2/")))
            },
            onDismiss = { askingPath = false },
        )
    }
}

/** What can be asked about one security: its quote, its card, its prices, its options. */
@Composable
fun IolSymbolScreen(
    route: IolSymbolRoute,
    onNavigateBack: () -> Unit,
    onOpen: (IolResponseRoute) -> Unit,
) {
    val dates = remember { ExplorerDates.now() }
    val queries = symbolQueries(route, dates)
    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(title = route.symbol, onNavigateBack = onNavigateBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            route.name?.let { name ->
                item(key = "name") {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
            items(queries, key = { it.path }) { query ->
                val title = stringResource(query.title)
                ExplorerRow(
                    icon = query.icon,
                    title = title,
                    subtitle = stringResource(query.description),
                    onClick = { onOpen(IolResponseRoute(query.path, "${route.symbol} · $title")) },
                )
            }
        }
    }
}

private fun LazyListScope.querySection(
    key: String,
    title: StringResource,
    queries: List<ExplorerQuery>,
    onOpen: (IolResponseRoute) -> Unit,
) {
    item(key = "header-$key") { SectionHeader(title = stringResource(title)) }
    items(queries, key = { "$key-${it.path}" }) { query ->
        val label = stringResource(query.title)
        ExplorerRow(
            icon = query.icon,
            title = label,
            subtitle = stringResource(query.description),
            onClick = { onOpen(IolResponseRoute(query.path, label)) },
        )
    }
}

/** The app's list row with a neutral disc and a chevron: every row here opens something. */
@Composable
internal fun ExplorerRow(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    AppListRow(
        icon = icon,
        paint = AccountPaint(
            tint = MaterialTheme.colorScheme.background,
            ink = MaterialTheme.colorScheme.inverseSurface,
        ),
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            trailing?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Spacer(Modifier.size(6.dp))
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

@Composable
private fun AskDialog(
    title: String,
    label: String,
    initial: String,
    monospace: Boolean,
    validate: (String) -> Boolean,
    transform: (String) -> String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val valid = validate(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = transform(it) },
                label = { Text(label) },
                singleLine = true,
                textStyle = if (monospace) {
                    MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = if (monospace) KeyboardCapitalization.None else KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { if (valid) onDone(value) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(value) }, enabled = valid) { Text(stringResource(Res.string.iol_explorer_run)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/** The dates the ready-made requests are filled with, yyyy-MM-dd. */
private class ExplorerDates(val today: String, val tomorrow: String, val monthAgo: String, val yearAgo: String) {
    companion object {
        private const val DAY = 24L * 60 * 60 * 1000

        fun now(): ExplorerDates {
            val now = epochMillis()
            return ExplorerDates(
                today = iolExplorerDate(now),
                tomorrow = iolExplorerDate(now + DAY),
                monthAgo = iolExplorerDate(now - 31 * DAY),
                yearAgo = iolExplorerDate(now - 365 * DAY),
            )
        }
    }
}

/*
 * The swagger's read endpoints that need no body
 * (api.invertironline.com/v2/swagger), filled in.
 */

private fun accountQueries(d: ExplorerDates) = listOf(
    ExplorerQuery(Res.string.iol_q_state, Res.string.iol_d_state, "/api/v2/estadocuenta", Icons.Filled.Home),
    ExplorerQuery(Res.string.iol_q_portfolio_ar, Res.string.iol_d_portfolio_ar, "/api/v2/portafolio/argentina", Icons.Filled.TrendingUp),
    ExplorerQuery(Res.string.iol_q_portfolio_us, Res.string.iol_d_portfolio_us, "/api/v2/portafolio/estados_Unidos", Icons.Filled.TrendingUp),
    ExplorerQuery(
        Res.string.iol_q_operations,
        Res.string.iol_d_operations,
        "/api/v2/operaciones?filtro.estado=todas&filtro.fechaDesde=${d.yearAgo}&filtro.fechaHasta=${d.tomorrow}",
        Icons.Filled.ListAlt,
    ),
    ExplorerQuery(Res.string.iol_q_pending, Res.string.iol_d_pending, "/api/v2/operaciones?filtro.estado=pendientes", Icons.Filled.DateRange),
    ExplorerQuery(Res.string.iol_q_profile, Res.string.iol_d_profile, "/api/v2/datos-perfil", Icons.Filled.Person),
    ExplorerQuery(Res.string.iol_q_investor_test, Res.string.iol_d_investor_test, "/api/v2/asesores/test-inversor", Icons.Filled.Check),
    ExplorerQuery(Res.string.iol_q_notifications, Res.string.iol_d_notifications, "/api/v2/Notificacion", Icons.Filled.Notifications),
)

private fun marketQueries() = listOf(
    ExplorerQuery(Res.string.iol_q_stocks, Res.string.iol_d_board, "/api/v2/Cotizaciones/acciones/argentina/Todos", Icons.Filled.TrendingUp),
    ExplorerQuery(Res.string.iol_q_cedears, Res.string.iol_d_board, "/api/v2/Cotizaciones/cedears/argentina/Todos", Icons.Filled.TrendingUp),
    ExplorerQuery(Res.string.iol_q_bonds, Res.string.iol_d_board, "/api/v2/Cotizaciones/titulosPublicos/argentina/Todos", Icons.Filled.TrendingUp),
    ExplorerQuery(Res.string.iol_q_ons, Res.string.iol_d_board, "/api/v2/Cotizaciones/obligacionesNegociables/argentina/Todos", Icons.Filled.TrendingUp),
    ExplorerQuery(Res.string.iol_q_letters, Res.string.iol_d_board, "/api/v2/Cotizaciones/letras/argentina/Todos", Icons.Filled.TrendingUp),
    ExplorerQuery(Res.string.iol_q_cauciones, Res.string.iol_d_board, "/api/v2/Cotizaciones/cauciones/argentina/Todos", Icons.Filled.TrendingUp),
    ExplorerQuery(Res.string.iol_q_us_stocks, Res.string.iol_d_board, "/api/v2/Cotizaciones/acciones/estados_Unidos/Todos", Icons.Filled.TrendingUp),
    ExplorerQuery(Res.string.iol_q_panels, Res.string.iol_d_panels, "/api/v2/argentina/Titulos/Cotizacion/Paneles/acciones", Icons.Filled.AccountTree),
    ExplorerQuery(Res.string.iol_q_instruments, Res.string.iol_d_instruments, "/api/v2/argentina/Titulos/Cotizacion/Instrumentos", Icons.Filled.AccountTree),
    ExplorerQuery(
        Res.string.iol_q_mep_simple,
        Res.string.iol_d_mep_simple,
        "/api/v2/OperatoriaSimplificada/VentaMepSimple/MontosEstimados/100000",
        Icons.Filled.Bolt,
    ),
)

private fun fundQueries() = listOf(
    ExplorerQuery(Res.string.iol_q_funds, Res.string.iol_d_funds, "/api/v2/Titulos/FCI", Icons.Filled.Category),
    ExplorerQuery(Res.string.iol_q_fund_types, Res.string.iol_d_fund_types, "/api/v2/Titulos/FCI/TipoFondos", Icons.Filled.AccountTree),
    ExplorerQuery(Res.string.iol_q_fund_managers, Res.string.iol_d_fund_managers, "/api/v2/Titulos/FCI/Administradoras", Icons.Filled.Person),
)

/** A fund gets its fund page; anything listed gets its market's quote endpoints. */
private fun symbolQueries(route: IolSymbolRoute, d: ExplorerDates): List<ExplorerQuery> {
    val s = route.symbol
    val m = route.market
    if (route.kind == "fci") {
        return listOf(ExplorerQuery(Res.string.iol_q_fund, Res.string.iol_d_fund, "/api/v2/Titulos/FCI/$s", Icons.Filled.Category))
    }
    return listOfNotNull(
        ExplorerQuery(Res.string.iol_q_quote, Res.string.iol_d_quote, "/api/v2/$m/Titulos/$s/Cotizacion", Icons.Filled.TrendingUp),
        ExplorerQuery(Res.string.iol_q_quote_detail, Res.string.iol_d_quote_detail, "/api/v2/$m/Titulos/$s/CotizacionDetalle", Icons.Filled.Tune),
        ExplorerQuery(
            Res.string.iol_q_quote_mobile,
            Res.string.iol_d_quote_mobile,
            "/api/v2/$m/Titulos/$s/CotizacionDetalleMobile/t1",
            Icons.Filled.Smartphone,
        ),
        ExplorerQuery(Res.string.iol_q_title, Res.string.iol_d_title, "/api/v2/$m/Titulos/$s", Icons.Filled.MenuBook),
        ExplorerQuery(
            Res.string.iol_q_history_month,
            Res.string.iol_d_history_month,
            "/api/v2/$m/Titulos/$s/Cotizacion/seriehistorica/${d.monthAgo}/${d.today}/sinAjustar",
            Icons.Filled.DateRange,
        ),
        ExplorerQuery(
            Res.string.iol_q_history_year,
            Res.string.iol_d_history_year,
            "/api/v2/$m/Titulos/$s/Cotizacion/seriehistorica/${d.yearAgo}/${d.today}/ajustada",
            Icons.Filled.DateRange,
        ),
        ExplorerQuery(Res.string.iol_q_options, Res.string.iol_d_options, "/api/v2/$m/Titulos/$s/Opciones", Icons.Filled.AccountTree)
            .takeIf { route.kind in setOf("stock", "cedear", "other") },
        // The MEP rate is quoted through a bond with a dollar line.
        ExplorerQuery(Res.string.iol_q_mep, Res.string.iol_d_mep, "/api/v2/Cotizaciones/MEP/$s", Icons.Filled.Bolt)
            .takeIf { route.kind in setOf("bond", "on", "other") },
    )
}
