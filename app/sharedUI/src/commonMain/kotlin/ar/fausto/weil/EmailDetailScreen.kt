@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_back
import weil.app.sharedui.generated.resources.email_detail_from
import weil.app.sharedui.generated.resources.email_detail_title
import weil.app.sharedui.generated.resources.email_detail_to
import weil.app.sharedui.generated.resources.emails_no_subject
import weil.app.sharedui.generated.resources.similar_emails
import weil.app.sharedui.generated.resources.similar_link_done
import weil.app.sharedui.generated.resources.similar_transactions

/** Vista de solo lectura de un correo: remitente, destinatario y cuerpo completo. */
@Composable
fun EmailDetailScreen(
    emails: EmailsRepository,
    ledger: TransactionsRepository,
    embeddings: EmbeddingsRepository,
    id: String,
    onNavigateBack: () -> Unit,
    onOpenEmail: (String) -> Unit = {},
    onOpenTransaction: (String) -> Unit = {},
) {
    var email by remember(id) { mutableStateOf<EmailDetail?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var linkedTo by remember(id) { mutableStateOf<Set<String>>(emptySet()) }
    var showSimilar by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val linkedMessage = stringResource(Res.string.similar_link_done)

    LaunchedEffect(id) {
        email = emails.get(id)
        linkedTo = ledger.transactionsForSource(EventSource.Email, id)
        loaded = true
    }

    val noSubject = stringResource(Res.string.emails_no_subject)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.email_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    // The similar lists and the HTML body cannot share a
                    // column: HtmlView brings its own scrolling and must not
                    // sit under an unbounded height, so the two swap instead.
                    TextButton(onClick = { showSimilar = !showSimilar }) {
                        Text(stringResource(Res.string.similar_emails))
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            !loaded -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(32.dp))
                    CircularProgressIndicator()
                }
            }
            email == null -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
                ) {
                    Text(
                        stringResource(Res.string.emails_no_subject),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            else -> {
                val current = email!!
                // The header stays put and the body scrolls on its own: the
                // HTML view brings its own scrolling and must never sit inside
                // a verticalScroll parent (unbounded height breaks measuring).
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(GroupRadius),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                current.subject ?: noSubject,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(Res.string.email_detail_from, current.fromEmail),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(Res.string.email_detail_to, current.toEmail),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                formatTimestamp(current.receivedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (showSimilar) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            SimilarSection(
                                embeddings = embeddings,
                                title = stringResource(Res.string.similar_emails),
                                kind = EmbedKind.Email,
                                id = id,
                                onOpen = { onOpenEmail(it.id) },
                            )
                            Spacer(Modifier.height(24.dp))
                            SimilarSection(
                                embeddings = embeddings,
                                title = stringResource(Res.string.similar_transactions),
                                kind = EmbedKind.Email,
                                id = id,
                                into = EmbedKind.Transaction,
                                onOpen = { onOpenTransaction(it.id) },
                                linkedRefs = linkedTo,
                                reloadKey = linkedTo,
                                onLink = { match ->
                                    scope.launch {
                                        try {
                                            ledger.associate(
                                                listOf(
                                                    AssociateOp(
                                                        transactionId = match.id,
                                                        sources = listOf(
                                                            TransactionSource(EventSource.Email, id),
                                                        ),
                                                    ),
                                                ),
                                            )
                                            linkedTo =
                                                ledger.transactionsForSource(EventSource.Email, id)
                                            Feedback.show(linkedMessage)
                                        } catch (e: Throwable) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            Feedback.show(e.message ?: e.toString())
                                        }
                                    }
                                },
                            )
                            Spacer(Modifier.height(32.dp))
                        }
                    } else {
                    Surface(
                        shape = RoundedCornerShape(GroupRadius),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        val bodyHtml = current.bodyHtml
                        if (bodyHtml.isNullOrBlank()) {
                            // Pre-HTML rows, and mails that only carried text.
                            Text(
                                current.bodyText.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        } else {
                            HtmlView(
                                html = rememberEmailDocument(bodyHtml),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}
