package ar.fausto.weil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_search
import weil.app.sharedui.generated.resources.picker_empty

/**
 * Searchable account chooser as a bottom sheet, used for posting accounts and
 * for re-parenting (with an [exclude] list for self + descendants). Unfiltered
 * rows keep the tree indentation; filtered rows fall back to the full path.
 * When [onCreate] is set, a leading action row labeled [createLabel] offers
 * inline account creation (e.g. expense categories).
 *
 * With [typeOptions] the caller hands over the *whole* tree and the sheet
 * filters it by the chosen type, showing a chip row to switch: the default
 * ([initialType], first option otherwise) covers the common case, the other
 * chips let the user escape it (categorizing an import row against an asset
 * or a liability instead of an expense, say).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPickerSheet(
    tree: List<AccountNode>,
    title: String,
    subtitle: String? = null,
    exclude: Set<String> = emptySet(),
    createLabel: String? = null,
    onCreate: (() -> Unit)? = null,
    /**
     * Optional "no account" row, used by the move flow to send an account
     * back to the root of its type. Without it a move is one-way: once an
     * account has a parent there is no picker entry that means "none".
     */
    rootLabel: String? = null,
    onPickRoot: (() -> Unit)? = null,
    /**
     * When set, asset/liability accounts declaring a *different* currency are
     * hidden (categories and undeclared accounts always stay). A hidden
     * parent's matching descendants are promoted rather than dropped with it,
     * so declaring a currency on a folder never hides the accounts inside it.
     */
    commodityFilter: String? = null,
    typeOptions: List<AccountType> = emptyList(),
    initialType: AccountType? = null,
    onTypeChange: (AccountType) -> Unit = {},
    onDismiss: () -> Unit,
    onPick: (AccountNode) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(initialType ?: typeOptions.firstOrNull()) }
    val shown = remember(tree, type, typeOptions, commodityFilter) {
        val byType =
            if (typeOptions.isEmpty() || type == null) tree else tree.filter { it.account.type == type }
        if (commodityFilter == null) byType else filterByCommodity(byType, commodityFilter)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // Context line for chained picking ("which row am I on?").
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            if (typeOptions.size > 1) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    typeOptions.forEach { option ->
                        FilterChip(
                            selected = option == type,
                            onClick = {
                                type = option
                                onTypeChange(option)
                            },
                            label = { Text(accountTypeLabel(option)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text(stringResource(Res.string.action_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(4.dp))
            val filtering = filter.isNotBlank()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
            ) {
                if (onPickRoot != null && rootLabel != null) {
                    item(key = "root") {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPickRoot() }
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                            ) {
                                Icon(
                                    Icons.Filled.ArrowBack,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    rootLabel,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
                if (onCreate != null && createLabel != null) {
                    item(key = "create") {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onCreate() }
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    createLabel,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
                if (filtering) {
                    // Flat path list while searching: depth becomes someone
                    // else's name fragment, not a visual rail.
                    val flat = shown.flatMap { it.selfAndDescendants }
                        .filter { it.account.id !in exclude }
                        .filter { it.path.lowercase().contains(filter.trim().lowercase()) }
                    if (flat.isEmpty()) {
                        item(key = "no-results") {
                            Text(
                                stringResource(Res.string.picker_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                            )
                        }
                    }
                    items(flat, key = { it.account.id }) { node ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(node) }
                                .padding(vertical = 12.dp, horizontal = 16.dp),
                        ) {
                            Text(
                                node.path.censored(),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // Two same-named accounts flatten to the same
                            // path; the currency is the only thing that tells
                            // the rows apart.
                            CommodityBadge(node.account.commodity)
                        }
                    }
                } else {
                    itemsIndented(shown, exclude, onPick)
                }
                item(key = "pad") {
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * Drops asset/liability accounts whose declared currency is not [commodity],
 * promoting the survivors under a dropped parent instead of taking the whole
 * branch with it. Accounts that declare nothing are never filtered out: no
 * declaration means no claim, and hiding them would punish the default state.
 */
private fun filterByCommodity(nodes: List<AccountNode>, commodity: String): List<AccountNode> =
    nodes.flatMap { node ->
        val kept = filterByCommodity(node.children, commodity)
        val own = node.account.commodity
        if (own == null || own == commodity) listOf(node.copy(children = kept)) else kept
    }

/** Marker row for a type section in the depth-first list below. */
private data class TypeHeaderRow(val type: AccountType, val divider: Boolean)

/**
 * Depth-first flattened rows; children always visible below their parent.
 * Top-level roots are grouped by [AccountType] with a [SectionHeader] when
 * more than one type is present in [tree] (e.g. the full-tree picker used by
 * [TransactionEditScreen]) — otherwise two same-named roots of different
 * types ("Gastos:Otros" vs "Ingresos:Otros") are indistinguishable once
 * reduced to just their leaf name and indentation.
 */
private fun LazyListScope.itemsIndented(
    tree: List<AccountNode>,
    exclude: Set<String>,
    onPick: (AccountNode) -> Unit,
) {
    val showHeaders = tree.map { it.account.type }.distinct().size > 1
    val rows = buildList {
        var firstHeader = true
        for (type in AccountType.entries) {
            val roots = tree.filter { it.account.type == type }
            if (roots.isEmpty()) continue
            if (showHeaders) {
                add(TypeHeaderRow(type, divider = !firstHeader))
                firstHeader = false
            }
            fun visit(node: AccountNode, depth: Int) {
                if (node.account.id !in exclude) add(node to depth)
                node.children.forEach { visit(it, depth + 1) }
            }
            roots.forEach { visit(it, 0) }
        }
    }
    items(
        rows,
        key = { row ->
            when (row) {
                is TypeHeaderRow -> "header-${row.type}"
                else -> (row as Pair<*, *>).let { (it.first as AccountNode).account.id }
            }
        },
    ) { row ->
        when (row) {
            is TypeHeaderRow -> SectionHeader(
                title = accountTypeLabel(row.type),
                divider = row.divider,
            )
            else -> {
                @Suppress("UNCHECKED_CAST")
                val (node, depth) = row as Pair<AccountNode, Int>
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(node) }
                        .padding(vertical = 12.dp),
                ) {
                    Spacer(Modifier.width((depth * 16).dp))
                    Text(
                        node.account.name.censored(),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(horizontal = 16.dp),
                    )
                    CommodityBadge(node.account.commodity)
                    Spacer(Modifier.weight(1f))
                    if (node.children.isNotEmpty()) {
                        Text(
                            node.children.size.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
