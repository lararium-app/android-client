package app.lararium.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lararium.R
import app.lararium.hearth.badge.BadgeLogic
import app.lararium.service.AssistantService
import app.lararium.service.AssistantState

/**
 * Full-height chat screen occupying the primary content area under a minimal top bar.
 * Assistant messages are plain text directly on background; user messages in primaryContainer bubbles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHomeScreen(
    assistantService: AssistantService?,
    hearthEndpoint: Triple<String, String, String>?,
    pendingApprovals: Int,
    onOpenDrawer: () -> Unit,
    onOpenApprovals: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel? = null
) {
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val messages = (viewModel?.chatMessages ?: assistantService?.chatMessages)?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val state = (viewModel?.assistantState ?: assistantService?.assistantState)?.collectAsStateWithLifecycle()?.value ?: AssistantState.Idle
    val streamingResponse = (viewModel?.streamingResponse ?: assistantService?.response)?.collectAsStateWithLifecycle()?.value ?: ""

    val isTurnOpen = ChatStateLogic.isInFlight(state, streamingResponse)
    val isGenerating = isTurnOpen || state is AssistantState.Speaking

    // Auto-scroll to latest token / message (also when the keyboard opens, so the
    // newest bubble stays visible above the composer).
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(messages.size, streamingResponse.length, isTurnOpen, imeBottom) {
        val totalItems = messages.size + (if (streamingResponse.isNotEmpty() || isTurnOpen) 1 else 0)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    val topBarTitle = DrawerLogic.formatTopBarTitle(hearthEndpoint, stringResource(R.string.app_name))

    // Inset strategy (edge-to-edge window; adjustResize does NOT resize under enableEdgeToEdge):
    // the bottomBar itself carries the ime∪navigationBars union padding, so the composer rides
    // above the keyboard. Content adds no bottom inset of its own — Scaffold already reserves
    // the bottomBar's (now keyboard-aware) height.
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = topBarTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = stringResource(R.string.open_drawer)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.new_chat)
                        )
                    }
                    IconButton(onClick = onOpenApprovals) {
                        BadgedBox(
                            badge = {
                                BadgeLogic.badgeText(pendingApprovals)?.let { text ->
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    ) {
                                        Text(text)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.VerifiedUser,
                                contentDescription = stringResource(R.string.approvals_badge_description)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            Surface(
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 4,
                        decorationBox = { innerTextField ->
                            if (textInput.isEmpty()) {
                                Text(
                                    text = stringResource(
                                        if (isGenerating) R.string.chat_composer_generating
                                        else R.string.chat_composer_placeholder
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    )

                    if (isGenerating) {
                        FilledIconButton(
                            onClick = { viewModel?.stopResponse() ?: assistantService?.stopResponse() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = stringResource(R.string.chat_composer_stop),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else if (textInput.isNotBlank()) {
                        FilledIconButton(
                            onClick = {
                                val query = textInput.trim()
                                if (query.isNotEmpty()) {
                                    textInput = ""
                                    viewModel?.processTextQuery(query) ?: assistantService?.processTextQuery(query)
                                }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.chat_composer_send),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (state is AssistantState.Listening) {
                                    viewModel?.stopVoiceCapture() ?: assistantService?.stopVoiceCapture()
                                } else {
                                    viewModel?.startVoiceCapture() ?: assistantService?.startVoiceCapture()
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                if (state is AssistantState.Listening) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = stringResource(R.string.chat_composer_voice),
                                tint = if (state is AssistantState.Listening) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (messages.isEmpty() && streamingResponse.isEmpty() && !isTurnOpen) {
                TastefulEmptyChatState(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(
                        items = messages,
                        key = { msg ->
                            if (msg.seq != null) "seq_${msg.seq}"
                            else "${msg.role}_${msg.timestamp}_${msg.content.hashCode()}"
                        }
                    ) { message ->
                        if (message.role == "user") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.widthIn(max = 320.dp)
                                ) {
                                    Text(
                                        text = message.content,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                SelectionContainer {
                                    MarkdownText(
                                        markdown = message.content,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    if (isTurnOpen && streamingResponse.isNotEmpty()) {
                        item(key = "streaming_response") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                SelectionContainer {
                                    MarkdownText(
                                        markdown = streamingResponse,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    } else if (isTurnOpen) {
                        item(key = "typing_indicator") {
                            TypingIndicator()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tasteful empty state for fresh chats: flame glyph with warm hearth prompt.
 */
@Composable
fun TastefulEmptyChatState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.empty_chat_hearth_warm),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Subtle typing indicator with 3 pulsing dots.
 */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                0.3f at 0
                1f at 250
                0.3f at 500
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                0.3f at 200
                1f at 450
                0.3f at 700
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                0.3f at 400
                1f at 650
                0.3f at 900
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot3"
    )

    Row(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .alpha(dot1Alpha)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .alpha(dot2Alpha)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .alpha(dot3Alpha)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
        )
    }
}
