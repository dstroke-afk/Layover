package com.intj.layover.navigation

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.intj.layover.ble.PeerAliasRegistry
import com.intj.layover.diagnostics.LayoverFileLogger
import com.intj.layover.model.ChatMessage
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.LayoverUiState
import com.intj.layover.ui.screens.ChatScreen
import com.intj.layover.ui.screens.DEFAULT_LOADING_DISPLAY_MS
import com.intj.layover.ui.screens.LayoverLoadingScreen
import com.intj.layover.ui.screens.PeopleAroundScreen
import com.intj.layover.ui.screens.ProfileScreen
import com.intj.layover.ui.screens.ScanScreen
import com.intj.layover.ui.screens.canonicalDeduplicatePeers
import com.intj.layover.ui.screens.formatMessageBubbleTimestamp
import com.intj.layover.ui.theme.LayoverTheme

sealed class LayoverRoute(val route: String) {
    data object Splash : LayoverRoute("splash")
    data object Scan : LayoverRoute("scan")
    data object PeopleAround : LayoverRoute("people_around")
    data object Profile : LayoverRoute("profile")
    data class PeerProfile(val peerId: String) : LayoverRoute("peer_profile/$peerId") {
        companion object {
            const val ROUTE_PATTERN = "peer_profile/{peerId}"
            fun createRoute(peerId: String) = "peer_profile/$peerId"
        }
    }
    data class Chat(val peerId: String) : LayoverRoute("chat/$peerId") {
        companion object {
            const val ROUTE_PATTERN = "chat/{peerId}"
            fun createRoute(peerId: String) = "chat/$peerId"
        }
    }
}

internal fun resolveChatMessagesForPeer(
    chatMessages: Map<String, List<ChatMessage>>,
    candidateAliases: Collection<String?>
): List<ChatMessage> {
    val cleanAliases = candidateAliases
        .mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
        .toSet()
    if (cleanAliases.isEmpty() || chatMessages.isEmpty()) return emptyList()

    val mergedById = LinkedHashMap<String, ChatMessage>()
    for ((key, list) in chatMessages) {
        val matches = cleanAliases.any { alias -> key.equals(alias, ignoreCase = true) }
        if (matches) {
            for (msg in list) {
                val existing = mergedById[msg.id]
                if (existing == null) {
                    mergedById[msg.id] = msg
                } else {
                    val existingAtt = existing.photoAttachment
                    val newAtt = msg.photoAttachment
                    val newWins = newAtt != null && (
                        existingAtt == null ||
                            (newAtt.state == com.intj.layover.model.PhotoTransferState.COMPLETED &&
                                existingAtt.state != com.intj.layover.model.PhotoTransferState.COMPLETED) ||
                            (newAtt.localFilePath != null && existingAtt.localFilePath == null) ||
                            (newAtt.progress > existingAtt.progress &&
                                existingAtt.state != com.intj.layover.model.PhotoTransferState.COMPLETED)
                        )
                    if (newWins) {
                        mergedById[msg.id] = msg
                    }
                }
            }
        }
    }
    return mergedById.values.toList()
}

@Composable
fun LayoverBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = LayoverTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        colors.gradientStart,
                        colors.gradientMid,
                        colors.gradientEnd
                    )
                )
            )
    ) {
        val context = LocalContext.current
        val drawableId = remember(context) {
            context.resources.getIdentifier("bg_layover", "drawable", context.packageName)
        }
        if (drawableId != 0) {
            Image(
                painter = painterResource(id = drawableId),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(colors.overlayAlpha)
            )
        }
        content()
    }
}

@Composable
fun LayoverNavGraph(
    uiState: LayoverUiState,
    onUpdateUiState: (LayoverUiState) -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    skipSplash: Boolean = false,
    isRuntimeReady: Boolean = true
) {
    val toggleBlockPeer = { rawPeerId: String ->
        val queryAliases = PeerAliasRegistry.resolveAliases(rawPeerId) + rawPeerId
        val targetPeer = uiState.nearbyPeers.find { peer ->
            queryAliases.any { q ->
                peer.id.equals(q, ignoreCase = true) ||
                        peer.handle.equals(q, ignoreCase = true) ||
                        peer.displayName.equals(q, ignoreCase = true)
            }
        }

        val resolvedPeerId = targetPeer?.id?.takeIf { it.isNotBlank() }
            ?: targetPeer?.handle?.takeIf { it.isNotBlank() }
            ?: rawPeerId.trim()

        if (resolvedPeerId.isNotBlank() && resolvedPeerId != "00000000" && resolvedPeerId != "user") {
            PeerAliasRegistry.linkAliases(
                listOfNotNull(
                    rawPeerId,
                    resolvedPeerId,
                    targetPeer?.id,
                    targetPeer?.handle?.ifEmpty { null },
                    targetPeer?.displayName?.ifEmpty { null }
                )
            )

            val aliases = (
                    PeerAliasRegistry.resolveAliases(resolvedPeerId) +
                            PeerAliasRegistry.resolveAliases(rawPeerId) +
                            listOfNotNull(
                                resolvedPeerId,
                                targetPeer?.id,
                                targetPeer?.handle?.ifEmpty { null },
                                targetPeer?.displayName?.ifEmpty { null }
                            )
                    ).filter { it.isNotBlank() && it != "00000000" && it != "user" }.toSet()

            val updatedBlocked = uiState.blockedPeerIds.toMutableSet()
            val isCurrentlyBlocked = aliases.any { candidate ->
                updatedBlocked.any { existing -> existing.equals(candidate, ignoreCase = true) }
            }

            if (isCurrentlyBlocked) {
                updatedBlocked.removeAll { existing ->
                    aliases.any { candidate -> existing.equals(candidate, ignoreCase = true) }
                }
                uiState.transport.sendBlockState(resolvedPeerId, false)
                LayoverFileLogger.log("BLOCK ACTION: Unblocked peerId=$resolvedPeerId. Removed aliases=$aliases. Total blocked set size=${updatedBlocked.size}")
            } else {
                updatedBlocked.addAll(aliases)
                uiState.transport.sendBlockState(resolvedPeerId, true)
                LayoverFileLogger.log("BLOCK ACTION: Blocked peerId=$resolvedPeerId. Added aliases=$aliases. Total blocked set size=${updatedBlocked.size}, contents=$updatedBlocked")
            }
            onUpdateUiState(uiState.copy(blockedPeerIds = updatedBlocked))
        }
    }

    val toggleMutePeer = { rawPeerId: String ->
        val queryAliases = PeerAliasRegistry.resolveAliases(rawPeerId) + rawPeerId
        val targetPeer = uiState.nearbyPeers.find { peer ->
            queryAliases.any { q ->
                peer.id.equals(q, ignoreCase = true) ||
                        peer.handle.equals(q, ignoreCase = true) ||
                        peer.displayName.equals(q, ignoreCase = true)
            }
        }

        val resolvedPeerId = targetPeer?.id?.takeIf { it.isNotBlank() }
            ?: targetPeer?.handle?.takeIf { it.isNotBlank() }
            ?: rawPeerId.trim()

        if (resolvedPeerId.isNotBlank() && resolvedPeerId != "00000000" && resolvedPeerId != "user") {
            PeerAliasRegistry.linkAliases(
                listOfNotNull(
                    rawPeerId,
                    resolvedPeerId,
                    targetPeer?.id,
                    targetPeer?.handle?.ifEmpty { null },
                    targetPeer?.displayName?.ifEmpty { null }
                )
            )

            val aliases = (
                    PeerAliasRegistry.resolveAliases(resolvedPeerId) +
                            PeerAliasRegistry.resolveAliases(rawPeerId) +
                            listOfNotNull(
                                resolvedPeerId,
                                targetPeer?.id,
                                targetPeer?.handle?.ifEmpty { null },
                                targetPeer?.displayName?.ifEmpty { null }
                            )
                    ).filter { it.isNotBlank() && it != "00000000" && it != "user" }.toSet()

            val updatedMuted = uiState.mutedPeerIds.toMutableSet()
            val isCurrentlyMuted = aliases.any { candidate ->
                updatedMuted.any { existing -> existing.equals(candidate, ignoreCase = true) }
            }

            if (isCurrentlyMuted) {
                updatedMuted.removeAll { existing ->
                    aliases.any { candidate -> existing.equals(candidate, ignoreCase = true) }
                }
                LayoverFileLogger.log("MUTE ACTION: Unmuted peerId=$resolvedPeerId. Removed aliases=$aliases.")
            } else {
                updatedMuted.addAll(aliases)
                LayoverFileLogger.log("MUTE ACTION: Muted peerId=$resolvedPeerId. Added aliases=$aliases.")
            }
            onUpdateUiState(uiState.copy(mutedPeerIds = updatedMuted))
        }
    }

    val startDestination = remember(skipSplash) {
        if (skipSplash) LayoverRoute.PeopleAround.route else LayoverRoute.Splash.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier.fillMaxSize()
    ) {
        composable(LayoverRoute.Splash.route) {
            LayoverLoadingScreen(
                autoFinishDelayMs = DEFAULT_LOADING_DISPLAY_MS,
                isReady = isRuntimeReady,
                onFinished = {
                    if (navController.currentDestination?.route == LayoverRoute.Splash.route) {
                        navController.navigate(LayoverRoute.PeopleAround.route) {
                            popUpTo(LayoverRoute.Splash.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            )
        }

        composable(LayoverRoute.Scan.route) {
            ScanScreen(
                uiState = uiState,
                onToggleScan = {
                    onUpdateUiState(uiState.copy(isScanning = !uiState.isScanning))
                },
                onNavigateToPeople = { openWithSearch ->
                    onUpdateUiState(uiState.copy(isSearchActive = openWithSearch))
                    navController.navigate(LayoverRoute.PeopleAround.route)
                },
                onNavigateToProfile = {
                    navController.navigate(LayoverRoute.Profile.route)
                }
            )
        }

        composable(LayoverRoute.PeopleAround.route) {
            PeopleAroundScreen(
                uiState = uiState,
                initialSearchActive = uiState.isSearchActive,
                onConnectPeer = { peerId ->
                    val queryAliases = PeerAliasRegistry.resolveAliases(peerId) + peerId
                    val matchingPeers = uiState.nearbyPeers.filter { peer ->
                        queryAliases.any { q ->
                            peer.id.equals(q, ignoreCase = true) ||
                                peer.handle.equals(q, ignoreCase = true)
                        }
                    }
                    val targetPeer = matchingPeers.firstOrNull()
                    if (targetPeer?.status == ConnectionStatus.REQUESTED ||
                        targetPeer?.status == ConnectionStatus.ACCEPTED
                    ) {
                        return@PeopleAroundScreen
                    }
                    PeerAliasRegistry.linkAliases(
                        listOfNotNull(
                            peerId,
                            targetPeer?.id,
                            targetPeer?.handle?.ifEmpty { null },
                            targetPeer?.displayName?.ifEmpty { null }
                        )
                    )
                    val expandedAliases = PeerAliasRegistry.resolveAliases(peerId) + peerId
                    val targetId = targetPeer?.id?.takeIf { it.isNotBlank() } ?: peerId
                    uiState.transport.sendConnectionRequest(targetId)
                    val updatedPeers = canonicalDeduplicatePeers(
                        uiState.nearbyPeers.map { peer ->
                            val matches = expandedAliases.any { alias ->
                                peer.id.equals(alias, ignoreCase = true) ||
                                    peer.handle.equals(alias, ignoreCase = true)
                            }
                            if (matches) peer.copy(status = ConnectionStatus.REQUESTED) else peer
                        }
                    )
                    onUpdateUiState(uiState.copy(nearbyPeers = updatedPeers))
                },
                onOpenChat = { peerId ->
                    navController.navigate(LayoverRoute.Chat.createRoute(peerId))
                },
                onToggleBlockPeer = toggleBlockPeer,
                onToggleMutePeer = toggleMutePeer,
                onNavigateToProfile = {
                    navController.navigate(LayoverRoute.Profile.route)
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(LayoverRoute.Profile.route) {
            ProfileScreen(
                uiState = uiState,
                onSaveProfile = { updatedProfile ->
                    onUpdateUiState(uiState.copy(userProfile = updatedProfile))
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = LayoverRoute.Chat.ROUTE_PATTERN,
            arguments = listOf(navArgument("peerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString("peerId") ?: ""
            val queryAliases = PeerAliasRegistry.resolveAliases(peerId) + peerId
            val matchingPeers = uiState.nearbyPeers.filter { p ->
                queryAliases.any { q ->
                    p.id.equals(q, ignoreCase = true) ||
                            p.handle.equals(q, ignoreCase = true) ||
                            p.displayName.equals(q, ignoreCase = true)
                }
            }
            val peer = matchingPeers.firstOrNull { it.avatar.avatarBase64OrBytes?.isNotEmpty() == true }
                ?: matchingPeers.firstOrNull()
            val resolvedAvatarBytes = matchingPeers
                .firstNotNullOfOrNull { it.avatar.avatarBase64OrBytes?.takeIf { b -> b.isNotEmpty() } }

            val chatAliases = LinkedHashSet<String>().apply {
                addAll(queryAliases)
                peer?.id?.takeIf { it.isNotBlank() }?.let {
                    add(it)
                    addAll(PeerAliasRegistry.resolveAliases(it))
                }
                peer?.handle?.takeIf { it.isNotBlank() }?.let {
                    add(it)
                    addAll(PeerAliasRegistry.resolveAliases(it))
                }
                peer?.displayName?.takeIf { it.isNotBlank() }?.let { add(it) }
            }

            val messages = resolveChatMessagesForPeer(
                chatMessages = uiState.chatMessages,
                candidateAliases = chatAliases
            )

            val isBlocked = (uiState.transport as? com.intj.layover.ble.BleProximityTransport)
                ?.isPeerIdentifierBlocked(peerId, peer?.id, peer?.handle, peer?.displayName)
                ?: listOfNotNull(peerId, peer?.id, peer?.handle, peer?.displayName).any { candidate ->
                    val expanded = PeerAliasRegistry.resolveAliases(candidate) + candidate
                    expanded.any { exp -> uiState.blockedPeerIds.any { it.equals(exp, ignoreCase = true) } }
                }

            val isBlockedByPeer = listOfNotNull(peerId, peer?.id, peer?.handle, peer?.displayName).any { candidate ->
                val expanded = PeerAliasRegistry.resolveAliases(candidate) + candidate
                expanded.any { exp -> uiState.blockedByPeerIds.any { it.equals(exp, ignoreCase = true) } }
            }

            val isMuted = listOfNotNull(peerId, peer?.id, peer?.handle, peer?.displayName).any { candidate ->
                val expanded = PeerAliasRegistry.resolveAliases(candidate) + candidate
                expanded.any { exp -> uiState.mutedPeerIds.any { it.equals(exp, ignoreCase = true) } }
            }

            val photoErrorMessage = uiState.photoErrorsByPeerId.entries
                .firstOrNull { (key, _) ->
                    chatAliases.any { alias -> key.equals(alias, ignoreCase = true) }
                }?.value

            ChatScreen(
                peer = peer,
                messages = messages,
                isBlocked = isBlocked,
                isBlockedByPeer = isBlockedByPeer,
                isMuted = isMuted,
                photoErrorMessage = photoErrorMessage,
                onDismissPhotoError = {
                    val updatedErrors = uiState.photoErrorsByPeerId.filterKeys { key ->
                        chatAliases.none { alias -> key.equals(alias, ignoreCase = true) }
                    }
                    onUpdateUiState(uiState.copy(photoErrorsByPeerId = updatedErrors))
                },
                onSendMessage = { text, replyTo ->
                    uiState.transport.sendMessage(peerId, text)
                    val sentAtMillis = System.currentTimeMillis()
                    val newMsg = ChatMessage(
                        id = "msg_$sentAtMillis",
                        senderId = "user",
                        text = text,
                        timestampLabel = formatMessageBubbleTimestamp(sentAtMillis),
                        isOutgoing = true,
                        replyToText = replyTo,
                        timestampMillis = sentAtMillis
                    )
                    val updatedMap = uiState.chatMessages.toMutableMap()
                    val baseMessages = resolveChatMessagesForPeer(updatedMap, chatAliases).toMutableList()
                    if (!baseMessages.any { it.id == newMsg.id }) {
                        baseMessages.add(newMsg)
                    }
                    val writeKeys = LinkedHashSet<String>().apply {
                        add(peerId)
                        peer?.id?.takeIf { it.isNotBlank() }?.let { add(it) }
                        peer?.handle?.takeIf { it.isNotBlank() }?.let { add(it) }
                        addAll(chatAliases)
                        updatedMap.keys.filter { existingKey ->
                            chatAliases.any { alias -> existingKey.equals(alias, ignoreCase = true) }
                        }.forEach { add(it) }
                    }
                    writeKeys.forEach { key ->
                        if (key.isNotBlank()) {
                            updatedMap[key] = baseMessages
                        }
                    }
                    onUpdateUiState(uiState.copy(chatMessages = updatedMap))
                },
                fallbackAvatarBytes = resolvedAvatarBytes,
                onToggleBlockPeer = toggleBlockPeer,
                onToggleMutePeer = toggleMutePeer,
                onOpenPeerProfile = { targetPeerId ->
                    val routeTarget = peer?.id?.takeIf { it.isNotBlank() } ?: targetPeerId
                    if (routeTarget.isNotBlank()) {
                        navController.navigate(LayoverRoute.PeerProfile.createRoute(routeTarget))
                    }
                },
                onSendPhotoUri = { uri ->
                    val targetPeerId = peer?.id?.takeIf { it.isNotBlank() } ?: peerId
                    uiState.transport.sendPhoto(targetPeerId, uri.toString())
                },
                onDownloadPhoto = { transferId ->
                    val targetPeerId = peer?.id?.takeIf { it.isNotBlank() } ?: peerId
                    uiState.transport.downloadPhoto(targetPeerId, transferId)
                },
                onCancelPhotoTransfer = { transferId ->
                    uiState.transport.cancelPhotoTransfer(transferId)
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = LayoverRoute.PeerProfile.ROUTE_PATTERN,
            arguments = listOf(navArgument("peerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val targetPeerId = backStackEntry.arguments?.getString("peerId") ?: ""
            val queryAliases = PeerAliasRegistry.resolveAliases(targetPeerId) + targetPeerId
            val matchingTargetPeers = uiState.nearbyPeers.filter { p ->
                queryAliases.any { q ->
                    p.id.equals(q, ignoreCase = true) ||
                            p.handle.equals(q, ignoreCase = true) ||
                            p.displayName.equals(q, ignoreCase = true)
                }
            }
            val targetPeer = matchingTargetPeers.firstOrNull { it.avatar.avatarBase64OrBytes?.isNotEmpty() == true }
                ?: matchingTargetPeers.firstOrNull()
            val targetAvatarBytes = matchingTargetPeers
                .firstNotNullOfOrNull { it.avatar.avatarBase64OrBytes?.takeIf { b -> b.isNotEmpty() } }
            val resolvedPeerAge: Int? = matchingTargetPeers
                .firstNotNullOfOrNull { it.age?.takeIf { ageVal -> ageVal > 0 } }
            val resolvedPeerGender = matchingTargetPeers
                .firstNotNullOfOrNull { it.gender }
            val enrichedTargetPeer = targetPeer?.copy(
                age = targetPeer.age ?: resolvedPeerAge,
                gender = targetPeer.gender ?: resolvedPeerGender
            )

            val peerBlocked = (uiState.transport as? com.intj.layover.ble.BleProximityTransport)
                ?.isPeerIdentifierBlocked(targetPeerId, targetPeer?.id, targetPeer?.handle, targetPeer?.displayName)
                ?: listOfNotNull(targetPeerId, targetPeer?.id, targetPeer?.handle, targetPeer?.displayName).any { candidate ->
                    val expanded = PeerAliasRegistry.resolveAliases(candidate) + candidate
                    expanded.any { exp -> uiState.blockedPeerIds.any { it.equals(exp, ignoreCase = true) } }
                }

            val peerBlockedByPeer = listOfNotNull(targetPeerId, targetPeer?.id, targetPeer?.handle, targetPeer?.displayName).any { candidate ->
                val expanded = PeerAliasRegistry.resolveAliases(candidate) + candidate
                expanded.any { exp -> uiState.blockedByPeerIds.any { it.equals(exp, ignoreCase = true) } }
            }

            com.intj.layover.ui.screens.PeerProfileScreen(
                peer = enrichedTargetPeer,
                peerIdFallback = targetPeerId,
                age = resolvedPeerAge,
                fallbackAvatarBytes = targetAvatarBytes,
                isBlocked = peerBlocked,
                isBlockedByPeer = peerBlockedByPeer,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}