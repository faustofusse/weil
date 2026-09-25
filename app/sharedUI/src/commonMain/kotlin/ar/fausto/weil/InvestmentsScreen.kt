package ar.fausto.weil

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.investments_empty_body
import weil.app.sharedui.generated.resources.investments_empty_title
import weil.app.sharedui.generated.resources.investments_soon
import weil.app.sharedui.generated.resources.investments_soon_message
import weil.app.sharedui.generated.resources.investments_source_ibkr
import weil.app.sharedui.generated.resources.investments_source_ibkr_hint
import weil.app.sharedui.generated.resources.investments_source_iol
import weil.app.sharedui.generated.resources.investments_source_iol_hint
import weil.app.sharedui.generated.resources.investments_source_statement
import weil.app.sharedui.generated.resources.investments_source_statement_hint
import weil.app.sharedui.generated.resources.investments_title

/**
 * The investments tab (see `plans/inversiones-brokers.md`, section UI).
 *
 * For now only the empty state: the three ways a broker gets in — IOL's API,
 * an IBKR Flex report, a custody statement PDF — each marked «Pronto» until
 * its phase lands. The rows are already the real entry points in shape, so
 * wiring one up is replacing its `onClick`, not redesigning the screen.
 */
@Composable
fun InvestmentsScreen(
    bottomBar: @Composable () -> Unit = {},
) {
    val soon = stringResource(Res.string.investments_soon_message)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = stringResource(Res.string.investments_title)) },
        bottomBar = bottomBar,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            // Same bottom air as Inicio: the create button straddles the
            // bar's top edge and would otherwise cover the last row.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 56.dp),
        ) {
            item(key = "empty-intro") {
                Text(
                    stringResource(Res.string.investments_empty_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(Res.string.investments_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
            }
            item(key = "source-iol") {
                SourceRow(
                    icon = Icons.Filled.TrendingUp,
                    title = stringResource(Res.string.investments_source_iol),
                    hint = stringResource(Res.string.investments_source_iol_hint),
                    onClick = { Feedback.show(soon) },
                )
            }
            item(key = "source-ibkr") {
                SourceRow(
                    icon = Icons.Filled.TrendingUp,
                    title = stringResource(Res.string.investments_source_ibkr),
                    hint = stringResource(Res.string.investments_source_ibkr_hint),
                    onClick = { Feedback.show(soon) },
                )
            }
            item(key = "source-statement") {
                SourceRow(
                    icon = Icons.Filled.DocumentScanner,
                    title = stringResource(Res.string.investments_source_statement),
                    hint = stringResource(Res.string.investments_source_statement_hint),
                    onClick = { Feedback.show(soon) },
                )
            }
        }
    }
}

/** One way in: the app's standard row, with a dim «Pronto» until it works. */
@Composable
private fun SourceRow(
    icon: ImageVector,
    title: String,
    hint: String,
    onClick: () -> Unit,
) {
    AppListRow(
        icon = icon,
        // Neutral paint: a broker is not a category, and a color here would
        // claim an identity the account it creates doesn't have yet.
        paint = accountPaint(null),
        title = title,
        subtitle = hint,
        onClick = onClick,
    ) {
        Text(
            stringResource(Res.string.investments_soon),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
