package com.intj.layover.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.LayoverUiState
import com.intj.layover.model.NearbyPeer
import com.intj.layover.navigation.LayoverBackground
import com.intj.layover.ui.theme.CyanAccent
import com.intj.layover.ui.theme.CyanPrimary
import com.intj.layover.ui.theme.DarkNavyBackground
import com.intj.layover.ui.theme.ElevatedCardSurface
import com.intj.layover.ui.theme.IncomingBubbleSurface
import com.intj.layover.ui.theme.LightText
import com.intj.layover.ui.theme.MutedText

@Composable
fun PeopleAroundScreen(
    uiState: LayoverUiState,
    initialSearchActive: Boolean = false,
    onConnectPeer: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onToggleBlockPeer: (String) -> Unit,
    onToggleMutePeer: (String) -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateBack: () -> Unit
) {
    var searchActive by remember { mutableStateOf(initialSearchActive) }
    var searchQuery by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    if (showDiagnosticsDialog) {
        DiagnosticsDialog(uiState = uiState, onDismiss = { showDiagnosticsDialog = false })
    }

    val filteredPeers = remember(uiState.nearbyPeers, searchQuery, uiState.blockedPeerIds) {
        uiState.nearbyPeers.filter { peer ->
            searchQuery.isBlank() ||
                    peer.displayName.contains(searchQuery, ignoreCase = true) ||
                    (peer.handle.isNotEmpty() && peer.handle.contains(searchQuery, ignoreCase = true))
        }
    }

    LayoverBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = LightText
                        )
                    }
                    Text(
                        text = "Layover",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.Serif,
                        color = LightText
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { searchActive = !searchActive }) {
                        Icon(
                            imageVector = if (searchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Toggle Search",
                            tint = if (searchActive) CyanPrimary else LightText
                        )
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menu",
                                tint = LightText
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(ElevatedCardSurface)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Your Profile", color = LightText) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = CyanPrimary
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onNavigateToProfile()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Diagnostics", color = LightText) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.BugReport,
                                        contentDescription = null,
                                        tint = CyanPrimary
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showDiagnosticsDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("About", color = LightText) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = CyanAccent
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showAboutDialog = true
                                }
                            )
                        }
                    }
                }
            }

            // Live Search Bar
            if (searchActive) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter nearby people...", color = MutedText) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = ElevatedCardSurface,
                        unfocusedContainerColor = ElevatedCardSurface,
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = IncomingBubbleSurface,
                        focusedTextColor = LightText,
                        unfocusedTextColor = LightText
                    ),
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = MutedText
                                )
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section Header
            Text(
                text = "People Around (${filteredPeers.size})",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = LightText,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(filteredPeers, key = { it.id }) { peer ->
                    PeerListItemCard(
                        peer = peer,
                        isBlocked = uiState.blockedPeerIds.contains(peer.id),
                        isMuted = uiState.mutedPeerIds.contains(peer.id),
                        onConnect = {
                            uiState.transport.sendConnectionRequest(peer.id)
                            onConnectPeer(peer.id)
                        },
                        onChat = { onOpenChat(peer.id) },
                        onToggleBlock = { onToggleBlockPeer(peer.id) },
                        onToggleMute = { onToggleMutePeer(peer.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun PeerListItemCard(
    peer: NearbyPeer,
    isBlocked: Boolean,
    isMuted: Boolean,
    onConnect: () -> Unit,
    onChat: () -> Unit,
    onToggleBlock: () -> Unit,
    onToggleMute: () -> Unit
) {
    var showCardMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isBlocked) ElevatedCardSurface.copy(alpha = 0.6f) else ElevatedCardSurface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(IncomingBubbleSurface)
                        .border(
                            1.5.dp,
                            if (isBlocked) MutedText else CyanPrimary,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = peer.avatar.initials,
                        fontWeight = FontWeight.Bold,
                        color = if (isBlocked) MutedText else CyanPrimary,
                        fontSize = 17.sp
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = peer.displayName,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isBlocked) MutedText else LightText,
                            fontSize = 16.sp
                        )
                        if (isMuted) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                                contentDescription = "Muted",
                                tint = MutedText,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    if (isBlocked) {
                        Text(
                            text = "[BLOCKED]",
                            color = MutedText,
                            fontSize = 13.sp
                        )
                    } else if (peer.handle.isNotEmpty()) {
                        Text(
                            text = "@${peer.handle}",
                            color = MutedText,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Action Pill & Menu
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isBlocked) {
                    Surface(
                        modifier = Modifier.clickable { onToggleBlock() },
                        color = IncomingBubbleSurface,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = "Unblock",
                            color = LightText,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                } else {
                    when (peer.status) {
                        ConnectionStatus.NONE -> {
                            Surface(
                                modifier = Modifier.clickable { onConnect() },
                                color = CyanPrimary,
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text(
                                    text = "Request",
                                    color = DarkNavyBackground,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }

                        ConnectionStatus.REQUESTED -> {
                            Surface(
                                color = IncomingBubbleSurface,
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MutedText,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Requested",
                                        color = MutedText,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        ConnectionStatus.ACCEPTED -> {
                            Surface(
                                modifier = Modifier.clickable { onChat() },
                                color = Color(0xFF10B981),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Chat,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Accepted",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        ConnectionStatus.DECLINED -> {
                            Surface(
                                color = IncomingBubbleSurface,
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text(
                                    text = "Declined",
                                    color = MutedText,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                Box {
                    IconButton(onClick = { showCardMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = MutedText
                        )
                    }

                    DropdownMenu(
                        expanded = showCardMenu,
                        onDismissRequest = { showCardMenu = false },
                        modifier = Modifier.background(ElevatedCardSurface)
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isMuted) "Unmute Peer" else "Mute Peer", color = LightText) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeOff,
                                    contentDescription = null,
                                    tint = CyanAccent
                                )
                            },
                            onClick = {
                                showCardMenu = false
                                onToggleMute()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isBlocked) "Unblock Peer" else "Block Peer", color = Color(0xFFFF5252)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Block,
                                    contentDescription = null,
                                    tint = Color(0xFFFF5252)
                                )
                            },
                            onClick = {
                                showCardMenu = false
                                onToggleBlock()
                            }
                        )
                    }
                }
            }
        }
    }
}
