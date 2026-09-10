package ar.fausto.weil

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Searchable full-path account chooser used for posting accounts and for
 * re-parenting (with an [exclude] list for self + descendants).
 */
@Composable
fun AccountPickerDialog(
    tree: List<AccountNode>,
    title: String,
    exclude: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onPick: (AccountNode) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val candidates = tree.flatMap { it.selfAndDescendants }
        .filter { it.account.id !in exclude }
        .filter { filter.isBlank() || it.path.lowercase().contains(filter.trim().lowercase()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn {
                    items(candidates, key = { it.account.id }) { node ->
                        Text(
                            node.path,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(node) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
