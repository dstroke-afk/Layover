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
    val toggleBlockPeer = { peerId: String ->
        val updatedBlocked = uiState.blockedPeerIds.toMutableSet()
        if (updatedBlocked.contains(peerId)) {
            updatedBlocked.remove(peerId)
        } else {
            updatedBlocked.add(peerId)
        }
        onUpdateUiState(uiState.copy(blockedPeerIds = updatedBlocked))
    }

    val toggleMutePeer = { peerId: String ->
        val updatedMuted = uiState.mutedPeerIds.toMutableSet()
        if (updatedMuted.contains(peerId)) {
            updatedMuted.remove(peerId)
        } else {
            updatedMuted.add(peerId)
        }
        onUpdateUiState(uiState.copy(mutedPeerIds = updatedMuted))
    }

    NavHost(
        navController = navController,
        startDestination = LayoverRoute.Splash.route,
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
                    val updatedPeers = uiState.nearbyPeers.map { peer ->
                        if (peer.id == peerId) peer.copy(status = ConnectionStatus.REQUESTED) else peer
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
            val peer = uiState.nearbyPeers.find { it.id == peerId }
            val messages = uiState.chatMessages[peerId] ?: emptyList()
            val isBlocked = uiState.blockedPeerIds.contains(peerId)
            val isMuted = uiState.mutedPeerIds.contains(peerId)

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
