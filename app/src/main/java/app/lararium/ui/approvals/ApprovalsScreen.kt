package app.lararium.ui.approvals

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lararium.auth.authGate
import app.lararium.hearth.Endpoint
import app.lararium.R
import app.lararium.hearth.LarariumClient
import app.lararium.hearth.badge.HearthBadgeState
import app.lararium.hearth.live.CustosLive
import app.lararium.hearth.watch.CardInfo
import app.lararium.hearth.watch.HearthNotifier
import app.lararium.hearth.watch.WatchLogic
import app.lararium.receiver.CardActionReceiver
import app.lararium.security.SecureKeyManager
import app.lararium.ui.FirstRunCopy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
    var unpaired by remember { mutableStateOf(false) }

    val loadCards: suspend () -> Unit = {
        error = null
        val epTriple = SecureKeyManager(context).getHearthEndpoint()
        unpaired = epTriple == null
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

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        // Issue #169: re-poll whenever the screen becomes visible again, so cards
        // answered elsewhere (another device, a notification action) drop out of
        // this tray without a manual refresh.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            loadCards()
        }
    }

    LaunchedEffect(lifecycleOwner) {
        // Issue #165: apply live subscription changes as they happen, so an
        // armed card appears the instant it exists and vanishes the instant
        // it settles — no poll wait in between. The poll above remains the
        // backstop whenever the socket is down.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            CustosLive.deltas.collect { delta ->
                when (delta) {
                    is CustosLive.Delta.ReplaceAll -> {
                        cards = sortCards(delta.cards)
                        error = null
                    }
                    is CustosLive.Delta.Upsert -> {
                        val current = cards ?: emptyList()
                        cards = sortCards(current.filterNot { it.id == delta.card.id } + delta.card)
                        error = null
                    }
                    is CustosLive.Delta.Gone -> {
                        cards = cards?.filterNot { it.id == delta.id }
                    }
                }
            }
        }
    }

    val resolveCard: (String, String) -> Unit = { cardId, verdict ->
        scope.launch {
            authGate(
                context = context,
                reasonTitle = "Resolve Custody Card",
                destructive = false
            ) {
                pendingId = cardId
                withContext(Dispatchers.IO) {
                    try {
                        val epTriple = SecureKeyManager(context).getHearthEndpoint()
                        if (epTriple == null) {
                            snackbarHostState.showSnackbar("Not paired with a hearth yet.")
                            return@withContext
                        }
                        val endpoint = Endpoint(epTriple.first, epTriple.second, epTriple.third)
                        val client = LarariumClient(endpoint)
                        val (status, body) = client.resolveCard(cardId, verdict)
                        when (val outcome = classifyResolve(status, body)) {
                            is ResolveOutcome.Retryable -> {
                                snackbarHostState.showSnackbar(outcome.message)
                            }
                            else -> {
                                // Settled or Gone: this card is finished from here on —
                                // drop the row, remember the answer, cancel its pending
                                // notification, and refresh the badge (issue #169).
                                CardActionReceiver.markCardResolved(cardId)
                                withContext(Dispatchers.Main) {
                                    cards = cards?.filterNot { it.id == cardId }
                                    HearthNotifier.cancelCard(context, cardId)
                                    if (outcome is ResolveOutcome.Gone) {
                                        snackbarHostState.showSnackbar(outcome.message)
                                    }
                                }
                                HearthBadgeState.refresh(context)
                            }
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
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = error ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            val explainer = FirstRunCopy.hearthExplainer(unpaired)
                            if (explainer != null) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = explainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = FirstRunCopy.SETUP_LINK_LABEL,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable {
                                            context.startActivity(
                                                Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse(FirstRunCopy.INSTALL_GUIDE_URL)
                                                )
                                            )
                                        }
                                )
                            }
                        }
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
                            text = stringResource(R.string.approvals_all_caught_up),
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
    var expanded by remember { mutableStateOf(false) }

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

            if (card.dest.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "→ ${card.dest}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (WatchLogic.shouldWarnBlind(card)) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Arguments not shown — check your hearth if unsure.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (card.review.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = card.review,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded }
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
