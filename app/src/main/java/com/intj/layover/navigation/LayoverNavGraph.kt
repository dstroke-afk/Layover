package com.intj.layover.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import com.intj.layover.ui.screens.PeopleAroundScreen
import com.intj.layover.ui.screens.ProfileScreen
import com.intj.layover.ui.screens.ScanScreen
import com.intj.layover.ui.screens.SplashScreen
import com.intj.layover.ui.theme.DarkNavyBackground

sealed class LayoverRoute(val route: String) {
    data object Splash : LayoverRoute("splash")
    data object Scan : LayoverRoute("scan")
    data object PeopleAround : LayoverRoute("people_around")
    data object Profile : LayoverRoute("profile")
    data class Chat(val peerId: String) : LayoverRoute("chat/$peerId") {
        companion object {
            const val ROUTE_PATTERN = "chat/{peerId}"
            fun createRoute(peerId: String) = "chat/$peerId"
        }
    }
}

@Composable
fun LayoverBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        DarkNavyBackground,
                        Color(0xFF0E1A29),
                        DarkNavyBackground
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
                    .alpha(0.12f)
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
    navController: NavHostController = rememberNavController()
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
                LayoverFileLogger.log("BLOCK ACTION: Unblocked peerId=$resolvedPeerId. Removed aliases=$aliases. Total blocked set size=${updatedBlocked.size}")
            } else {
                updatedBlocked.addAll(aliases)
                LayoverFileLogger.log("BLOCK ACTION: Blocked peerId=$resolvedPeerId. Added aliases=$aliases. Total blocked set size=${updatedBlocked.size}, contents=$updatedBlocked")
            }
            onUpdateUiState(uiState.copy(blockedPeerIds = updatedBlocked))
        }
    }

    val toggleMutePeer = { peerId: String ->
        val updatedMuted = uiState.mutedPeerIds.toMutableSet()
        val peer = uiState.nearbyPeers.find { it.id == peerId || it.handle == peerId }
        val targetId = peer?.id ?: peerId

        if (updatedMuted.contains(targetId)) {
            updatedMuted.remove(targetId)
        } else {
            updatedMuted.add(targetId)
        }
        onUpdateUiState(uiState.copy(mutedPeerIds = updatedMuted))
    }

    NavHost(
        navController = navController,
        startDestination = LayoverRoute.PeopleAround.route,
        modifier = modifier
    ) {
        composable(LayoverRoute.Splash.route) {
            SplashScreen(
                onNavigateToScan = {
                    navController.navigate(LayoverRoute.Scan.route) {
                        popUpTo(LayoverRoute.Splash.route) { inclusive = true }
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
                    uiState.transport.sendConnectionRequest(peerId)
                    val updatedPeers = uiState.nearbyPeers.map { peer ->
                        if (peer.id == peerId || peer.handle == peerId) peer.copy(status = ConnectionStatus.REQUESTED) else peer
                    }
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
            val peer = uiState.nearbyPeers.find { p ->
                queryAliases.any { q ->
                    p.id.equals(q, ignoreCase = true) ||
                            p.handle.equals(q, ignoreCase = true) ||
                            p.displayName.equals(q, ignoreCase = true)
                }
            }

            val messages = uiState.chatMessages[peerId]
                ?: peer?.let { uiState.chatMessages[it.id] ?: uiState.chatMessages[it.handle] }
                ?: emptyList()

            val isBlocked = (uiState.transport as? com.intj.layover.ble.BleProximityTransport)
                ?.isPeerIdentifierBlocked(peerId, peer?.id, peer?.handle, peer?.displayName)
                ?: listOfNotNull(peerId, peer?.id, peer?.handle, peer?.displayName).any { candidate ->
                    val expanded = PeerAliasRegistry.resolveAliases(candidate) + candidate
                    expanded.any { exp -> uiState.blockedPeerIds.any { it.equals(exp, ignoreCase = true) } }
                }
            val isMuted = uiState.mutedPeerIds.contains(peerId) || (peer != null && uiState.mutedPeerIds.contains(peer.id))

            ChatScreen(
                peer = peer,
                messages = messages,
                isBlocked = isBlocked,
                isMuted = isMuted,
                onSendMessage = { text, replyTo ->
                    uiState.transport.sendMessage(peerId, text)
                    val newMsg = ChatMessage(
                        id = "msg_${System.currentTimeMillis()}",
                        senderId = "user",
                        text = text,
                        timestampLabel = "Just now",
                        isOutgoing = true,
                        replyToText = replyTo
                    )
                    val updatedMap = uiState.chatMessages.toMutableMap()
                    val currentList = updatedMap[peerId]?.toMutableList() ?: mutableListOf()
                    currentList.add(newMsg)
                    updatedMap[peerId] = currentList
                    if (peer != null) {
                        updatedMap[peer.id] = currentList
                    }
                    onUpdateUiState(uiState.copy(chatMessages = updatedMap))
                },
                onToggleBlockPeer = toggleBlockPeer,
                onToggleMutePeer = toggleMutePeer,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}