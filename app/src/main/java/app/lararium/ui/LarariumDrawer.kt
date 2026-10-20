package app.lararium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lararium.R

/**
 * Navigation drawer content (~280dp, M3 styled).
 * Header displays paired hearth name with dot or "Not connected" link to Settings.
 * Navigation rows in order: Chats, Approvals, Settings.
 */
@Composable
fun LarariumDrawerContent(
    hearthEndpoint: Triple<String, String, String>?,
    pendingApprovals: Int,
    onNavigateToChats: () -> Unit,
    onNavigateToApprovals: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionState = DrawerLogic.formatConnectionState(hearthEndpoint)

    ModalDrawerSheet(
        modifier = modifier.width(280.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        // Drawer Header Block
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            when (connectionState) {
                is DrawerLogic.ConnectionState.Paired -> {
                    Text(
                        text = connectionState.hearthName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(modifier = Modifier.size(8.dp)) {
                            drawCircle(color = Color(0xFF4CAF50))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.drawer_connection_paired),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                DrawerLogic.ConnectionState.Unpaired -> {
                    val outlineColor = MaterialTheme.colorScheme.outline
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onNavigateToSettings() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Canvas(modifier = Modifier.size(8.dp)) {
                            drawCircle(color = outlineColor)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.drawer_connection_not_connected),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Row 1: Chats
        NavigationDrawerItem(
            icon = {
                Icon(
                    Icons.Default.ChatBubbleOutline,
                    contentDescription = null
                )
            },
            label = {
                Text(
                    stringResource(R.string.drawer_nav_chats),
                    style = MaterialTheme.typography.labelLarge
                )
            },
            selected = false,
            onClick = onNavigateToChats,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )

        // Row 2: Approvals
        NavigationDrawerItem(
            icon = {
                Icon(
                    Icons.Default.VerifiedUser,
                    contentDescription = null
                )
            },
            label = {
                Text(
                    stringResource(R.string.drawer_nav_approvals),
                    style = MaterialTheme.typography.labelLarge
                )
            },
            badge = {
                if (pendingApprovals > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ) {
                        Text(pendingApprovals.toString())
                    }
                }
            },
            selected = false,
            onClick = onNavigateToApprovals,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )

        // Row 3: Settings
        NavigationDrawerItem(
            icon = {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null
                )
            },
            label = {
                Text(
                    stringResource(R.string.drawer_nav_settings),
                    style = MaterialTheme.typography.labelLarge
                )
            },
            selected = false,
            onClick = onNavigateToSettings,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )
    }
}
