package com.intj.layover.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.GenderOption
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

internal fun initialsForName(name: String, fallback: String = "?"): String {
    val computed = name.trim()
        .split("\\s+".toRegex())
        .mapNotNull { it.firstOrNull()?.toString() }
        .take(2)
        .joinToString("")
        .uppercase()
    return computed.ifEmpty { fallback }
}

fun canonicalDeduplicatePeers(peers: List<NearbyPeer>): List<NearbyPeer> {
    val canonicalList = mutableListOf<NearbyPeer>()

    for (p in peers) {
        val existingIndex = canonicalList.indexOfFirst { existing ->
            existing.id == p.id ||
                    (existing.handle.isNotEmpty() && p.handle.isNotEmpty() && existing.handle == p.handle) ||
                    (existing.displayName.isNotEmpty() && p.displayName.isNotEmpty() && (
                            existing.displayName.equals(p.displayName, ignoreCase = true) ||
                                    existing.displayName.take(3).equals(p.displayName.take(3), ignoreCase = true)
                            ))
        }

        if (existingIndex == -1) {
            canonicalList.add(p)
        } else {
            val existing = canonicalList[existingIndex]
            val statusPriority = mapOf(
                ConnectionStatus.ACCEPTED to 4,
                ConnectionStatus.REQUESTED to 3,
                ConnectionStatus.DECLINED to 2,
                ConnectionStatus.NONE to 1
            )
            val bestStatus = if ((statusPriority[p.status] ?: 0) > (statusPriority[existing.status] ?: 0)) {
                p.status
            } else {
                existing.status
            }

            val bestId = if (p.id.startsWith("dev_")) p.id else existing.id
            val bestHandle = if (p.handle.startsWith("dev_")) p.handle else existing.handle.ifEmpty { p.handle }
            val bestName = if (p.displayName.isNotBlank()) p.displayName else existing.displayName

            canonicalList[existingIndex] = existing.copy(
                id = bestId,
                handle = bestHandle,
                displayName = bestName,
                status = bestStatus,
                avatar = if (p.avatar.avatarBase64OrBytes != null) p.avatar else existing.avatar
            )
        }
    }

    return canonicalList
}

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
        canonicalDeduplicatePeers(uiState.nearbyPeers).filter { peer ->
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
                                text = { Text("My Profile", color = LightText) },
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
                        isBlocked = uiState.blockedPeerIds.contains(peer.id) ||
                                (peer.handle.isNotEmpty() && uiState.blockedPeerIds.contains(peer.handle)) ||
                                (peer.displayName.isNotEmpty() && uiState.blockedPeerIds.contains(peer.displayName)),
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
    val isChatAllowed = peer.status == ConnectionStatus.ACCEPTED || isBlocked
    val displayInitials = remember(peer.displayName, peer.avatar.initials) {
        if (peer.displayName.isNotBlank()) initialsForName(peer.displayName) else peer.avatar.initials
    }
    val peerAvatarBitmap = remember(peer.avatar.avatarBase64OrBytes) {
        peer.avatar.avatarBase64OrBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isChatAllowed) {
                onChat()
            },
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
                    if (!isBlocked && peerAvatarBitmap != null) {
                        Image(
                            bitmap = peerAvatarBitmap,
                            contentDescription = "${peer.displayName} avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                        )
                    } else {
                        Text(
                            text = displayInitials,
                            fontWeight = FontWeight.Bold,
                            color = if (isBlocked) MutedText else CyanPrimary,
                            fontSize = 17.sp
                        )
                    }
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
                                        text = "Connected",
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
            }
        }
    }
}

@Composable
fun OneTimeUsernameDialog(
    onConfirmUsername: (username: String, gender: GenderOption) -> Unit
) {
    var usernameInput by remember { mutableStateOf("") }
    var selectedGender by remember { mutableStateOf<GenderOption?>(null) }

    val hasSpace = usernameInput.any { it.isWhitespace() }
    val tooLong = usernameInput.length > 8
    val isUsernameValid = usernameInput.isNotEmpty() && !hasSpace && !tooLong
    val isValid = isUsernameValid && selectedGender != null

    val errorMessage = when {
        hasSpace -> "Spaces are not allowed in your username."
        tooLong -> "Username must be 8 characters or fewer."
        else -> null
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        containerColor = ElevatedCardSurface,
        title = {
            Text(
                text = "Set Your Username",
                color = LightText,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Surface(
                    color = IncomingBubbleSurface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Your username can only be set once and cannot be changed later.",
                        color = CyanAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = usernameInput,
                    onValueChange = { usernameInput = it },
                    singleLine = true,
                    placeholder = {
                        Text("Up to 8 chars, no spaces", color = MutedText)
                    },
                    supportingText = {
                        Text(
                            text = errorMessage ?: "${usernameInput.length}/8 characters • No spaces",
                            color = if (errorMessage != null) Color(0xFFFF5252) else MutedText,
                            fontSize = 12.sp
                        )
                    },
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Select Gender",
                    color = LightText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GenderOption.entries.forEach { option ->
                        val isSelected = selectedGender == option
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectedGender = option },
                            color = if (isSelected) CyanPrimary.copy(alpha = 0.18f) else IncomingBubbleSurface,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                width = 1.5.dp,
                                color = if (isSelected) CyanPrimary else Color.Transparent
                            )
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = option.label,
                                    color = if (isSelected) CyanPrimary else LightText,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                enabled = isValid,
                onClick = {
                    val chosenGender = selectedGender ?: GenderOption.MALE
                    onConfirmUsername(usernameInput, chosenGender)
                }
            ) {
                Text("Save Profile")
            }
        }
    )
}