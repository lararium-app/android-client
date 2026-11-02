package app.lararium.hearth.rails.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lararium.hearth.rails.service.DeviceRailService
import app.lararium.hearth.rails.service.DialerToolExecutor
import app.lararium.hearth.rails.service.SmsToolExecutor
import app.lararium.ui.theme.AiintegratedintoandroidTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Transparent/dialog-themed safety-critical confirmation sheet (G3, spec A6).
 * Shows humanized action, 60s countdown, Approve/Deny buttons, and local denial guarantee.
 * Non-cancelable by back press (back = deny).
 */
class ActionConfirmActivity : ComponentActivity() {

    private var presenter: SheetPresenter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val actionId = intent.getStringExtra("action_id")
        val action = InFlightActions.get(actionId)

        if (action == null) {
            // Notification tap when action is no longer in flight -> silently finish
            finish()
            return
        }

        val executors = if (app.lararium.BuildConfig.DIST_CHANNEL == "github") {
            listOf(DialerToolExecutor(this), SmsToolExecutor(this))
        } else {
            listOf(DialerToolExecutor(this))
        }
        val sheetPresenter = SheetPresenter(
            action = action,
            executors = executors,
            receiptQueue = DeviceRailService.receiptQueue,
            inFlightActions = InFlightActions,
            onFinish = { finish() }
        )
        presenter = sheetPresenter

        setContent {
            AiintegratedintoandroidTheme {
                ActionConfirmScreen(
                    presenter = sheetPresenter,
                    onApprove = { sheetPresenter.onApprove() },
                    onDeny = { sheetPresenter.onDeny() },
                    onBack = { sheetPresenter.onBackPressed() }
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        presenter?.onBackPressed() ?: super.onBackPressed()
    }
}

@Composable
fun ActionConfirmScreen(
    presenter: SheetPresenter,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler {
        onBack()
    }

    var countdownText by remember { mutableStateOf(presenter.countdownLabel()) }

    LaunchedEffect(presenter) {
        while (isActive) {
            delay(250)
            val isTerminal = presenter.onTick()
            countdownText = presenter.countdownLabel()
            if (isTerminal) {
                break
            }
        }
    }

    val actionIcon = when {
        presenter.action.tool.contains("dialer") -> Icons.Default.Call
        presenter.action.tool.contains("sms") -> Icons.Default.Sms
        else -> Icons.Default.Security
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                // Non-cancelable by touching outside (touch outside does not dismiss)
            },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = actionIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Approve Action?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = presenter.humanLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.HourglassTop,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = countdownText,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "expires → blocked locally",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDeny,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(
                            text = "Deny",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = onApprove,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Approve",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
