package com.intj.layover.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.filled.BluetoothDisabled
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.intj.layover.ble.BlePermissionHelper
import com.intj.layover.ble.PeerAliasRegistry
import com.intj.layover.diagnostics.LayoverFileLogger
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.GenderOption
import com.intj.layover.model.LayoverUiState
import com.intj.layover.model.NearbyPeer
import com.intj.layover.model.formatPeerNameWithBadge
import com.intj.layover.navigation.LayoverBackground
import com.intj.layover.notifications.UnreadMessageTracker
import com.intj.layover.ui.theme.LayoverTheme

internal fun initialsForName(name: String, fallback: String = "?"): String {
    val computed = name.trim()
        .split("\\s+".toRegex())
        .mapNotNull { it.firstOrNull()?.toString() }
        .take(2)
        .joinToString("")
        .uppercase()
    return computed.ifEmpty { fallback }
}

private fun isPlaceholderPeerName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isEmpty() || trimmed.startsWith("Layover Peer (", ignoreCase = true)
}

private fun expandedPeerIdentityAliases(peer: NearbyPeer): Set<String> {
    val result = linkedSetOf<String>()
    fun addId(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        result.add(trimmed)
        result.addAll(PeerAliasRegistry.resolveAliases(trimmed))
    }
    addId(peer.id)
    addId(peer.handle)
    return result
}

private fun peersShareIdentity(
    a: NearbyPeer,
    aAliases: Set<String>,
    b: NearbyPeer,
    bAliases: Set<String>
): Boolean {
    if (aAliases.any { aId -> bAliases.any { bId -> aId.equals(bId, ignoreCase = true) } }) {
        return true
    }
    val aRealName = a.displayName.trim().takeUnless(::isPlaceholderPeerName)
    val bRealName = b.displayName.trim().takeUnless(::isPlaceholderPeerName)
    return aRealName != null && bRealName != null && aRealName.equals(bRealName, ignoreCase = true)
}

private fun mergeCanonicalPeers(existing: NearbyPeer, incoming: NearbyPeer): NearbyPeer {
    val statusPriority = mapOf(
        ConnectionStatus.ACCEPTED to 4,
        ConnectionStatus.REQUESTED to 3,
        ConnectionStatus.DECLINED to 2,
        ConnectionStatus.NONE to 1
    )
    val bestStatus = if ((statusPriority[incoming.status] ?: 0) > (statusPriority[existing.status] ?: 0)) {
        incoming.status
    } else {
        existing.status
    }

    val combinedAliases = expandedPeerIdentityAliases(existing) + expandedPeerIdentityAliases(incoming)
    val preferredDevId = listOf(existing.id, incoming.id, existing.handle, incoming.handle)
        .firstOrNull { it.startsWith("dev_", ignoreCase = true) }
        ?: combinedAliases.firstOrNull { it.startsWith("dev_", ignoreCase = true) }

    val bestId = preferredDevId
        ?: existing.id.ifBlank { incoming.id }
    val bestHandle = preferredDevId
        ?: existing.handle.ifBlank { incoming.handle }.ifBlank { bestId }

    val existingName = existing.displayName.trim()
    val incomingName = incoming.displayName.trim()
    val existingReal = !isPlaceholderPeerName(existingName)
    val incomingReal = !isPlaceholderPeerName(incomingName)
    val bestName = when {
        incomingReal && existingReal -> {
            when {
                existingName.length > incomingName.length &&
                    existingName.startsWith(incomingName, ignoreCase = true) -> existingName
                incomingName.length > existingName.length &&
                    incomingName.startsWith(existingName, ignoreCase = true) -> incomingName
                else -> incomingName
            }
        }
        incomingReal -> incomingName
        existingReal -> existingName
        incomingName.isNotEmpty() -> incomingName
        else -> existingName
    }

    val bestGender = incoming.gender ?: existing.gender
    val bestAge = incoming.age?.takeIf { it > 0 } ?: existing.age?.takeIf { it > 0 }
    val bestAvatarBytes = incoming.avatar.avatarBase64OrBytes?.takeIf { it.isNotEmpty() }
        ?: existing.avatar.avatarBase64OrBytes?.takeIf { it.isNotEmpty() }

    return existing.copy(
        id = bestId,
        handle = bestHandle,
        displayName = bestName,
        gender = bestGender,
        age = bestAge,
        status = bestStatus,
        avatar = existing.avatar.copy(
            peerId = bestId,
            initials = initialsForName(bestName, fallback = existing.avatar.initials.ifEmpty { "?" }),
            avatarBase64OrBytes = bestAvatarBytes
        )
    )
}

fun canonicalDeduplicatePeers(peers: List<NearbyPeer>): List<NearbyPeer> {
    val canonicalList = mutableListOf<NearbyPeer>()
    val aliasCache = mutableListOf<MutableSet<String>>()

    for (p in peers) {
        val pAliases = expandedPeerIdentityAliases(p)
        val matchingIndices = canonicalList.indices.filter { idx ->
            peersShareIdentity(canonicalList[idx], aliasCache[idx], p, pAliases)
        }

        if (matchingIndices.isEmpty()) {
            canonicalList.add(p)
            aliasCache.add(pAliases.toMutableSet())
        } else {
            val primaryIdx = matchingIndices.first()
            var merged = mergeCanonicalPeers(canonicalList[primaryIdx], p)
            val mergedAliases = linkedSetOf<String>().apply {
                addAll(aliasCache[primaryIdx])
                addAll(pAliases)
            }

            // If p bridges multiple previously separate entries, merge them all into primaryIdx
            for (extraIdx in matchingIndices.drop(1).asReversed()) {
                merged = mergeCanonicalPeers(merged, canonicalList[extraIdx])
                mergedAliases.addAll(aliasCache[extraIdx])
                canonicalList.removeAt(extraIdx)
                aliasCache.removeAt(extraIdx)
            }

            mergedAliases.addAll(expandedPeerIdentityAliases(merged))
            canonicalList[primaryIdx] = merged
            aliasCache[primaryIdx] = mergedAliases
        }
    }

    return canonicalList
}

@Composable
fun PeopleAroundScreen(
    uiState: LayoverUiState,
    initialSearchActive: Boolean = false,
    blePermissionDeniedOrDismissed: Boolean = false,
    onConnectPeer: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onToggleBlockPeer: (String) -> Unit,
    onToggleMutePeer: (String) -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenAppSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val colors = LayoverTheme.colors
    var searchActive by remember { mutableStateOf(initialSearchActive) }
    var searchQuery by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var hasBlePermissions by remember {
        mutableStateOf(BlePermissionHelper.hasAllRequiredPermissions(context))
    }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, uiState.nearbyPeers.size, uiState.diagnostics.discoveredPeersCount) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME || event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                hasBlePermissions = BlePermissionHelper.hasAllRequiredPermissions(context)
            }
        }
        hasBlePermissions = BlePermissionHelper.hasAllRequiredPermissions(context)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    val openSettingsAction: () -> Unit = onOpenAppSettings ?: {
        val missingPerms = BlePermissionHelper.getRequiredPermissions().joinToString()
        LayoverFileLogger.log("PeopleAroundScreen: opening app permission settings for missing BLE perms: $missingPerms")
        Log.d("LayoverBLE", "PeopleAroundScreen: opening app permission settings for BLE perms: $missingPerms")
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
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
                            tint = colors.lightText
                        )
                    }
                    Text(
                        text = "Layover",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.Serif,
                        color = colors.lightText
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { searchActive = !searchActive }) {
                        Icon(
                            imageVector = if (searchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Toggle Search",
                            tint = if (searchActive) colors.cyanPrimary else colors.lightText
                        )
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menu",
                                tint = colors.lightText
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(colors.cardSurface)
                        ) {
                            DropdownMenuItem(
                                text = { Text("My Profile", color = colors.lightText) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = colors.cyanPrimary
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onNavigateToProfile()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Diagnostics", color = colors.lightText) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.BugReport,
                                        contentDescription = null,
                                        tint = colors.cyanPrimary
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showDiagnosticsDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("About", color = colors.lightText) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = colors.cyanAccent
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
                    placeholder = { Text("Filter nearby people...", color = colors.mutedText) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.cardSurface,
                        unfocusedContainerColor = colors.cardSurface,
                        focusedBorderColor = colors.cyanPrimary,
                        unfocusedBorderColor = colors.incomingBubble,
                        focusedTextColor = colors.lightText,
                        unfocusedTextColor = colors.lightText
                    ),
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = colors.mutedText
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
                color = colors.lightText,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                val currentBleGranted = BlePermissionHelper.hasAllRequiredPermissions(context)
                hasBlePermissions = currentBleGranted
                val prefsHandle = context.getSharedPreferences("layover_user_profile", android.content.Context.MODE_PRIVATE)
                val prefsFlowDone = prefsHandle.getBoolean("initial_permission_flow_done", false)
                val prefsDenied = prefsHandle.getBoolean("ble_permission_denied_or_dismissed", false)
                val promptCurrentlyOpen = prefsHandle.getBoolean("ble_permission_prompt_open", false)
                val shouldShowCard = !currentBleGranted && !promptCurrentlyOpen && (blePermissionDeniedOrDismissed || prefsDenied || prefsFlowDone)
                if (shouldShowCard) {
                    item(key = "ble_permission_recovery_card") {
                        LayoverFileLogger.logThrottled(
                            "people_around_ble_card",
                            "PeopleAroundScreen: rendering pinned BLE permission recovery card in first-peer slot."
                        )
                        BlePermissionRecoveryCard(onClick = openSettingsAction)
                    }
                }

                items(filteredPeers, key = { it.id }) { peer ->
                    val peerCandidates = listOfNotNull(
                        peer.id.takeIf { it.isNotBlank() },
                        peer.handle.takeIf { it.isNotBlank() },
                        peer.displayName.takeIf { it.isNotBlank() }
                    )
                    val expandedPeerIds = UnreadMessageTracker.expandIdentifiers(peerCandidates)
                    val isBlocked = expandedPeerIds.any { candidate ->
                        uiState.blockedPeerIds.any { blocked ->
                            blocked.equals(candidate, ignoreCase = true) ||
                                PeerAliasRegistry.resolveAliases(blocked).any { it.equals(candidate, ignoreCase = true) }
                        }
                    }
                    val isMuted = expandedPeerIds.any { candidate ->
                        uiState.mutedPeerIds.any { muted ->
                            muted.equals(candidate, ignoreCase = true) ||
                                PeerAliasRegistry.resolveAliases(muted).any { it.equals(candidate, ignoreCase = true) }
                        }
                    }
                    val unreadCount = UnreadMessageTracker.resolveUnreadCount(
                        unreadCountsByPeerId = uiState.unreadCountsByPeerId,
                        peerCandidates = peerCandidates
                    )
                    PeerListItemCard(
                        peer = peer,
                        isBlocked = isBlocked,
                        isMuted = isMuted,
                        unreadCount = unreadCount,
                        onConnect = {
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
fun BlePermissionRecoveryCard(
    onClick: () -> Unit
) {
    val colors = LayoverTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                LayoverFileLogger.log("BlePermissionRecoveryCard clicked -> opening App Settings.")
                Log.d("LayoverBLE", "BlePermissionRecoveryCard clicked -> opening App Settings.")
                onClick()
            },
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, colors.cyanPrimary.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.incomingBubble)
                    .border(1.5.dp, colors.cyanPrimary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.BluetoothDisabled,
                    contentDescription = null,
                    tint = colors.cyanPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = "The app needs Nearby Devices permission to discover devices and send and receive media.",
                color = colors.lightText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun PeerListItemCard(
    peer: NearbyPeer,
    isBlocked: Boolean,
    isMuted: Boolean,
    unreadCount: Int = 0,
    onConnect: () -> Unit,
    onChat: () -> Unit,
    onToggleBlock: () -> Unit,
    onToggleMute: () -> Unit
) {
    val colors = LayoverTheme.colors
    val isChatAllowed = peer.status == ConnectionStatus.ACCEPTED || isBlocked
    var showEnlargedAvatar by remember(peer.id) { mutableStateOf(false) }
    val formattedPeerName = remember(peer.displayName, peer.age, peer.gender) {
        formatPeerNameWithBadge(peer.displayName, peer.age, peer.gender)
    }
    val displayInitials = remember(peer.displayName, peer.avatar.initials) {
        if (peer.displayName.isNotBlank()) initialsForName(peer.displayName) else peer.avatar.initials
    }
    val rawBytes = peer.avatar.avatarBase64OrBytes
    val peerAvatarBitmap = remember(rawBytes) {
        if (rawBytes != null && rawBytes.isNotEmpty()) {
            val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
            LayoverFileLogger.logThrottled(
                "avatar_decode_${peer.id}_${rawBytes.size}",
                "PeerListItemCard decode: id=${peer.id}, name=${peer.displayName}, bytes=${rawBytes.size}, success=${decoded != null}, w=${decoded?.width ?: 0}, h=${decoded?.height ?: 0}"
            )
            decoded?.asImageBitmap()
        } else {
            null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isChatAllowed) {
                onChat()
            },
        colors = CardDefaults.cardColors(
            containerColor = if (isBlocked) colors.cardSurface.copy(alpha = 0.6f) else colors.cardSurface
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
                        .background(colors.incomingBubble)
                        .border(
                            1.5.dp,
                            if (isBlocked) colors.mutedText else colors.cyanPrimary,
                            CircleShape
                        )
                        .then(
                            if (!isBlocked && peerAvatarBitmap != null) {
                                Modifier.clickable { showEnlargedAvatar = true }
                            } else {
                                Modifier
                            }
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
                            color = if (isBlocked) colors.mutedText else colors.cyanPrimary,
                            fontSize = 17.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formattedPeerName,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isBlocked) colors.mutedText else colors.lightText,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    when {
                        isBlocked -> {
                            Text(
                                text = "\uD83D\uDEAB Blocked",
                                color = colors.mutedText,
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        isMuted -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (unreadCount > 0) {
                                    val unreadLabel = if (unreadCount == 1) {
                                        "1 Unread Message"
                                    } else {
                                        "$unreadCount Unread Messages"
                                    }
                                    Text(
                                        text = unreadLabel,
                                        color = colors.mutedText,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Text(
                                        text = "\u2022",
                                        color = colors.mutedText,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                                    contentDescription = "Muted",
                                    tint = colors.mutedText,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "Muted",
                                    color = colors.mutedText,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        unreadCount > 0 -> {
                            val unreadLabel = if (unreadCount == 1) {
                                "1 Unread Message"
                            } else {
                                "$unreadCount Unread Messages"
                            }
                            Text(
                                text = unreadLabel,
                                color = colors.cyanAccent,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isBlocked) {
                    Surface(
                        modifier = Modifier.clickable { onToggleBlock() },
                        color = colors.incomingBubble,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = "Unblock",
                            color = colors.lightText,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                } else {
                    when (peer.status) {
                        ConnectionStatus.NONE -> {
                            Surface(
                                modifier = Modifier.clickable { onConnect() },
                                color = colors.cyanPrimary,
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text(
                                    text = "Request",
                                    color = colors.darkBackground,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }

                        ConnectionStatus.REQUESTED -> {
                            Surface(
                                color = colors.incomingBubble,
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = colors.mutedText,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Requested",
                                        color = colors.mutedText,
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
                                color = colors.incomingBubble,
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text(
                                    text = "Declined",
                                    color = colors.mutedText,
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

    if (showEnlargedAvatar && !isBlocked && peerAvatarBitmap != null) {
        Dialog(
            onDismissRequest = { showEnlargedAvatar = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            BackHandler { showEnlargedAvatar = false }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f))
                    .clickable { showEnlargedAvatar = false }
            ) {
                Image(
                    bitmap = peerAvatarBitmap,
                    contentDescription = "$formattedPeerName full-screen photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )

                Text(
                    text = formattedPeerName,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                )

                IconButton(
                    onClick = { showEnlargedAvatar = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close photo",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun OneTimeUsernameDialog(
    onConfirmUsername: (username: String, gender: GenderOption, age: Int) -> Unit
) {
    val colors = LayoverTheme.colors
    var usernameInput by remember { mutableStateOf("") }
    var selectedGender by remember { mutableStateOf<GenderOption?>(null) }
    var ageInput by remember { mutableStateOf("") }

    val hasSpace = usernameInput.any { it.isWhitespace() }
    val tooLong = usernameInput.length > 7
    val isUsernameValid = usernameInput.isNotEmpty() && !hasSpace && !tooLong

    val trimmedAge = ageInput.trim()
    val parsedAge = trimmedAge.toIntOrNull()
    val isAgeValid = parsedAge != null && parsedAge in 13..120

    val isValid = isUsernameValid && selectedGender != null && isAgeValid

    val errorMessage = when {
        hasSpace -> "Spaces are not allowed in your username."
        tooLong -> "Username must be 7 characters or fewer."
        else -> null
    }

    val ageErrorMessage = when {
        trimmedAge.isEmpty() -> null
        parsedAge == null -> "Enter a valid age in years."
        parsedAge !in 13..120 -> "Age must be between 13 and 120."
        else -> null
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        containerColor = colors.cardSurface,
        title = {
            Text(
                text = "Set Your Username",
                color = colors.lightText,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Surface(
                    color = colors.incomingBubble,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Your username can only be set once and cannot be changed later.",
                        color = colors.cyanAccent,
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
                        Text("Up to 7 chars, no spaces", color = colors.mutedText)
                    },
                    supportingText = {
                        Text(
                            text = errorMessage ?: "${usernameInput.length}/7 characters • No spaces",
                            color = if (errorMessage != null) Color(0xFFFF5252) else colors.mutedText,
                            fontSize = 12.sp
                        )
                    },
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Your Age",
                    color = colors.lightText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = ageInput,
                    onValueChange = { ageInput = it },
                    singleLine = true,
                    placeholder = {
                        Text("Enter age (e.g. 25)", color = colors.mutedText)
                    },
                    supportingText = {
                        Text(
                            text = ageErrorMessage ?: "Required (13–120 years)",
                            color = if (ageErrorMessage != null) Color(0xFFFF5252) else colors.mutedText,
                            fontSize = 12.sp
                        )
                    },
                    isError = ageErrorMessage != null,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Select Gender",
                    color = colors.lightText,
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
                            color = if (isSelected) colors.cyanPrimary.copy(alpha = 0.18f) else colors.incomingBubble,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                width = 1.5.dp,
                                color = if (isSelected) colors.cyanPrimary else Color.Transparent
                            )
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = option.label,
                                    color = if (isSelected) colors.cyanPrimary else colors.lightText,
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
                    val chosenAge = parsedAge ?: return@Button
                    onConfirmUsername(usernameInput.trim().take(7), chosenGender, chosenAge)
                }
            ) {
                Text("Save Profile")
            }
        }
    )
}
