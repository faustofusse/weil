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
import androidx.compose.material3.ExperimentalMaterial3Api
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

/**
 * Searchable account chooser as a bottom sheet, used for posting accounts and
 * for re-parenting (with an [exclude] list for self + descendants). Unfiltered
 * rows keep the tree indentation; filtered rows fall back to the full path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPickerSheet(
    tree: List<AccountNode>,
    title: String,
    exclude: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onPick: (AccountNode) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Search") },
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
                if (filtering) {
                    // Flat path list while searching: depth becomes someone
                    // else's name fragment, not a visual rail.
                    val flat = tree.flatMap { it.selfAndDescendants }
                        .filter { it.account.id !in exclude }
                        .filter { it.path.lowercase().contains(filter.trim().lowercase()) }
                    items(flat, key = { it.account.id }) { node ->
                        Text(
                            node.path,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(node) }
                                .padding(vertical = 12.dp, horizontal = 16.dp),
                        )
                    }
                } else {
                    itemsIndented(tree, exclude, onPick)
                }
                item(key = "pad") {
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** Depth-first flattened rows; children always visible below their parent. */
private fun LazyListScope.itemsIndented(
    tree: List<AccountNode>,
    exclude: Set<String>,
    onPick: (AccountNode) -> Unit,
) {
    val flat = buildList {
        fun visit(node: AccountNode, depth: Int) {
            add(node to depth)
            node.children.forEach { visit(it, depth + 1) }
        }
        tree.forEach { visit(it, 0) }
    }.filter { it.first.account.id !in exclude }
    items(flat, key = { it.first.account.id }) { (node, depth) ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick(node) }
                .padding(vertical = 12.dp),
        ) {
            Spacer(Modifier.width((depth * 16).dp))
            Text(
                node.account.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            )
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
