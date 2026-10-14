package app.lararium.ui.approvals

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lararium.hearth.Endpoint
import app.lararium.hearth.LarariumClient
import app.lararium.hearth.watch.CardInfo
import app.lararium.hearth.watch.WatchLogic
import app.lararium.security.SecureKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose screen displaying pending custody card approvals.
 * Allows the user to review and resolve cards with "once", "always", or "deny" verdicts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var cards by remember { mutableStateOf<List<CardInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingId by remember { mutableStateOf<String?>(null) }

    val loadCards: suspend () -> Unit = {
        error = null
        val epTriple = SecureKeyManager(context).getHearthEndpoint()
        if (epTriple == null) {
            error = "Not paired with a hearth yet."
        } else {
            val endpoint = Endpoint(epTriple.first, epTriple.second, epTriple.third)
            try {
                val raw = withContext(Dispatchers.IO) {
                    LarariumClient(endpoint).custosCards()
                }
                cards = sortCards(WatchLogic.parseCards(raw))
                error = null
            } catch (e: Exception) {
                error = if (e is java.io.IOException) {
                    "Can't reach your hearth. Check the network and try again."
                } else {
                    "Couldn't load approvals: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        loadCards()
    }

    val resolveCard: (String, String) -> Unit = { cardId, verdict ->
        pendingId = cardId
        scope.launch(Dispatchers.IO) {
            try {
                val epTriple = SecureKeyManager(context).getHearthEndpoint()
                if (epTriple == null) {
                    snackbarHostState.showSnackbar("Not paired with a hearth yet.")
                    return@launch
                }
                val endpoint = Endpoint(epTriple.first, epTriple.second, epTriple.third)
                val client = LarariumClient(endpoint)
                val (status, body) = client.resolveCard(cardId, verdict)
                if (status == 200) {
                    withContext(Dispatchers.Main) {
                        cards = cards?.filter { it.id != cardId }
                    }
                } else {
                    val failureMessage = describeResolveFailure(status, body)
                    snackbarHostState.showSnackbar(failureMessage)
                }
            } catch (e: Exception) {
                val failureMessage = if (e is java.io.IOException) {
                    "Can't reach your hearth."
                } else {
                    describeResolveFailure(0, e.message ?: "")
                }
                snackbarHostState.showSnackbar(failureMessage)
            } finally {
                withContext(Dispatchers.Main) {
                    if (pendingId == cardId) {
                        pendingId = null
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Approvals") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { loadCards() } }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = error ?: "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                cards == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                cards!!.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No approvals waiting.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(cards!!, key = { it.id }) { card ->
                            ApprovalCardItem(
                                card = card,
                                isPending = pendingId == card.id,
                                onResolve = { verdict ->
                                    resolveCard(card.id, verdict)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalCardItem(
    card: CardInfo,
    isPending: Boolean,
    onResolve: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            val title = when {
                card.tool.isNotBlank() -> card.tool
                card.dest.isNotBlank() -> card.dest
                else -> "credential in use"
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            val subtitle = when {
                card.cell.isNotBlank() && card.cred.isNotBlank() -> "${card.cell} · ${card.cred}"
                card.cell.isNotBlank() -> card.cell
                card.cred.isNotBlank() -> card.cred
                else -> ""
            }
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (card.review.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = card.review,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isPending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                TextButton(
                    onClick = { onResolve("once") },
                    enabled = !isPending
                ) {
                    Text("Once")
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(
                    onClick = { onResolve("always") },
                    enabled = !isPending
                ) {
                    Text("Always")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = { onResolve("deny") },
                    enabled = !isPending,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Deny")
                }
            }
        }
    }
}
