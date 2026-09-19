@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.net_worth_excluded_by_parent
import weil.app.sharedui.generated.resources.net_worth_picker_hint
import weil.app.sharedui.generated.resources.net_worth_picker_title

/**
 * Which Asset/Liability accounts count toward Home's net-worth sum, opened
 * from [NetWorthCard]'s trailing button. A checkbox per account, indented by
 * depth; excluding a node disables (and visually dims) its whole subtree —
 * only the excluded ancestor's own checkbox stays interactive, since
 * unchecking a descendant on its own would do nothing (see
 * [LedgerState.excludedFromNetWorth]).
 */
@Composable
internal fun NetWorthPickerSheet(state: LedgerState, onDismiss: () -> Unit) {
    val excluded = LedgerState.excludedFromNetWorth(state.tree)
    val rows = buildList {
        for (type in listOf(AccountType.Asset, AccountType.Liability)) {
            addFlat(state.tree.filter { it.account.type == type }, 0)
        }
    }.filterIsInstance<NodeRow>()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(stringResource(Res.string.net_worth_picker_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(Res.string.net_worth_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    NetWorthPickerRow(
                        row = row,
                        excludedHere = row.node.account.id in excluded,
                        // Own flag off but an ancestor is what actually
                        // excludes it: the checkbox still reflects the
                        // account's stored value, but is locked and captioned.
                        lockedByAncestor = row.node.account.id in excluded && row.node.account.inNetWorth,
                        onToggle = { checked -> state.setInNetWorth(row.node.account.id, checked) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NetWorthPickerRow(
    row: NodeRow,
    excludedHere: Boolean,
    lockedByAncestor: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val indent = (row.depth.coerceAtMost(5) * 16).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !lockedByAncestor) { onToggle(!row.node.account.inNetWorth) }
            .padding(start = 24.dp + indent, end = 24.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = row.node.account.inNetWorth,
                onCheckedChange = if (lockedByAncestor) null else onToggle,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.node.account.name.censored(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (excludedHere) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (lockedByAncestor) {
                    Text(
                        stringResource(Res.string.net_worth_excluded_by_parent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
