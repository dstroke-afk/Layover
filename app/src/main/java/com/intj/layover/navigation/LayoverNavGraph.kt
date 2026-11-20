package com.intj.layover.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.intj.layover.model.ChatMessage
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.LayoverUiState
import com.intj.layover.model.NearbyPeer
import com.intj.layover.ui.theme.CyanAccent
import com.intj.layover.ui.theme.CyanPrimary
import com.intj.layover.ui.theme.DarkNavyBackground
import com.intj.layover.ui.theme.ElevatedCardSurface
import com.intj.layover.ui.theme.IncomingBubbleSurface
import com.intj.layover.ui.theme.LightText
import com.intj.layover.ui.theme.MutedText
import com.intj.layover.ui.theme.OutgoingCyanBubble

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
    content: @Composable () -> Unit,
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
    navController: NavHostController = rememberNavController(),
) {
    LayoverBackground(modifier = modifier) {
        NavHost(
            navController = navController,
            startDestination = LayoverRoute.Splash.route
        ) {
            composable(LayoverRoute.Splash.route) {
                SplashScreen(
                    onNavigateToScan = { navController.navigate(LayoverRoute.Scan.route) },
                    onNavigateToPeople = { navController.navigate(LayoverRoute.PeopleAround.route) },
                    onNavigateToProfile = { navController.navigate(LayoverRoute.Profile.route) }
                )
            }
            composable(LayoverRoute.Scan.route) {
                ScanScreen(
                    uiState = uiState,
                    onToggleScan = {
                        onUpdateUiState(uiState.copy(isScanning = !uiState.isScanning))
                    },
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToPeople = { navController.navigate(LayoverRoute.PeopleAround.route) }
                )
            }
            composable(LayoverRoute.PeopleAround.route) {
                PeopleAroundScreen(
                    uiState = uiState,
                    onConnectPeer = { peerId ->
                        val updatedPeers = uiState.nearbyPeers.map { peer ->
                            if (peer.id == peerId) peer.copy(status = ConnectionStatus.REQUESTED) else peer
                        }
                        onUpdateUiState(uiState.copy(nearbyPeers = updatedPeers))
                    },
                    onOpenChat = { peerId ->
                        navController.navigate(LayoverRoute.Chat.createRoute(peerId))
                    },
                    onNavigateToScan = { navController.navigate(LayoverRoute.Scan.route) },
                    onNavigateToProfile = { navController.navigate(LayoverRoute.Profile.route) },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(LayoverRoute.Profile.route) {
                ProfileScreen(
                    uiState = uiState,
                    onToggleVisibility = { isVisible ->
                        onUpdateUiState(
                            uiState.copy(
                                userProfile = uiState.userProfile.copy(isVisibleToNearby = isVisible)
                            )
                        )
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                route = LayoverRoute.Chat.ROUTE_PATTERN,
                arguments = listOf(navArgument("peerId") { type = NavType.StringType })
            ) { backStackEntry ->
                val peerId = backStackEntry.arguments?.getString("peerId") ?: ""
                val peer = uiState.nearbyPeers.find { it.id == peerId }
                val messages = uiState.chatMessages[peerId] ?: emptyList()

                ChatScreen(
                    peer = peer,
                    messages = messages,
                    onSendMessage = { text ->
                        val newMsg = ChatMessage(
                            id = "msg_${System.currentTimeMillis()}",
                            senderId = "user",
                            text = text,
                            timestampLabel = "Just now",
                            isOutgoing = true
                        )
                        val updatedMap = uiState.chatMessages.toMutableMap()
                        val currentList = updatedMap[peerId]?.toMutableList() ?: mutableListOf()
                        currentList.add(newMsg)
                        updatedMap[peerId] = currentList
                        onUpdateUiState(uiState.copy(chatMessages = updatedMap))
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
fun SplashScreen(
    onNavigateToScan: () -> Unit,
    onNavigateToPeople: () -> Unit,
    onNavigateToProfile: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(ElevatedCardSurface)
                    .border(2.dp, CyanPrimary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Radar,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(54.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "LAYOVER",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = LightText,
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Offline Nearby Social Mesh",
                fontSize = 14.sp,
                color = MutedText,
                textAlign = TextAlign.Center
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onNavigateToScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = DarkNavyBackground
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.Radar, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Scan Nearby", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onNavigateToPeople,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = LightText
                ),
                border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(CyanPrimary, CyanAccent))),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.Person, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "People Around")
            }

            OutlinedButton(
                onClick = onNavigateToProfile,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MutedText
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.AccountCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "My Profile")
            }
        }
    }
}

@Composable
fun ScanScreen(
    uiState: LayoverUiState,
    onToggleScan: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToPeople: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "RadarScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = LightText
                )
            }
            Text(
                text = "Radar Scan",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = LightText
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier.size(240.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .scale(if (uiState.isScanning) scale else 1.0f)
                    .clip(CircleShape)
                    .background(CyanPrimary.copy(alpha = 0.1f))
                    .border(1.dp, CyanPrimary.copy(alpha = 0.4f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .clip(CircleShape)
                    .background(CyanPrimary.copy(alpha = 0.15f))
                    .border(1.dp, CyanPrimary.copy(alpha = 0.6f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(ElevatedCardSurface)
                    .border(2.dp, CyanPrimary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Radar,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (uiState.isScanning) "Scanning for nearby peers..." else "Scan paused",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = LightText
        )
        Text(
            text = "Using Bluetooth Low Energy & Wi-Fi Direct Mesh",
            fontSize = 12.sp,
            color = MutedText,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = onToggleScan,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isScanning) IncomingBubbleSurface else CyanPrimary,
                    contentColor = if (uiState.isScanning) LightText else DarkNavyBackground
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = if (uiState.isScanning) "Stop Scanning" else "Start Scan")
            }

            Button(
                onClick = onNavigateToPeople,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElevatedCardSurface,
                    contentColor = LightText
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.Person, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "View People")
            }
        }
    }
}

@Composable
fun PeopleAroundScreen(
    uiState: LayoverUiState,
    onConnectPeer: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onNavigateToScan: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    text = "People Around",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = LightText
                )
            }

            Row {
                IconButton(onClick = onNavigateToScan) {
                    Icon(
                        imageVector = Icons.Default.Radar,
                        contentDescription = "Scan",
                        tint = CyanPrimary
                    )
                }
                IconButton(onClick = onNavigateToProfile) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = "Profile",
                        tint = LightText
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(uiState.nearbyPeers) { peer ->
                PeerCard(
                    peer = peer,
                    onConnect = { onConnectPeer(peer.id) },
                    onChat = { onOpenChat(peer.id) }
                )
            }
        }
    }
}

@Composable
fun PeerCard(
    peer: NearbyPeer,
    onConnect: () -> Unit,
    onChat: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ElevatedCardSurface),
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
                        .border(1.dp, CyanAccent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = peer.displayName.take(1).uppercase(),
                        fontWeight = FontWeight.Bold,
                        color = CyanPrimary,
                        fontSize = 18.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = peer.displayName,
                        fontWeight = FontWeight.SemiBold,
                        color = LightText,
                        fontSize = 16.sp
                    )
                    if (peer.handle.isNotEmpty()) {
                        Text(
                            text = "@${peer.handle}",
                            color = MutedText,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            when (peer.status) {
                ConnectionStatus.ACCEPTED -> {
                    Button(
                        onClick = onChat,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OutgoingCyanBubble,
                            contentColor = LightText
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Chat", fontSize = 13.sp)
                    }
                }

                ConnectionStatus.REQUESTED -> {
                    Surface(
                        color = IncomingBubbleSurface,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                                text = "Pending",
                                color = MutedText,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                ConnectionStatus.NONE -> {
                    OutlinedButton(
                        onClick = onConnect,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanPrimary),
                        border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(CyanPrimary, CyanAccent))),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(text = "Connect", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(
    uiState: LayoverUiState,
    onToggleVisibility: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = LightText
                )
            }
            Text(
                text = "My Profile",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = LightText
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ElevatedCardSurface),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(IncomingBubbleSurface)
                        .border(2.dp, CyanPrimary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.userProfile.displayName.split(" ")
                            .mapNotNull { it.firstOrNull()?.toString() }
                            .take(2)
                            .joinToString(""),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanPrimary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = uiState.userProfile.displayName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = LightText
                )

                Text(
                    text = "@${uiState.userProfile.handle}",
                    fontSize = 14.sp,
                    color = MutedText
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(IncomingBubbleSurface)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Visible to Nearby Travelers",
                            fontWeight = FontWeight.SemiBold,
                            color = LightText,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Allows nearby devices to discover your Layover node",
                            color = MutedText,
                            fontSize = 12.sp
                        )
                    }

                    Switch(
                        checked = uiState.userProfile.isVisibleToNearby,
                        onCheckedChange = onToggleVisibility,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DarkNavyBackground,
                            checkedTrackColor = CyanPrimary
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun ChatScreen(
    peer: NearbyPeer?,
    messages: List<ChatMessage>,
    onSendMessage: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = LightText
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(IncomingBubbleSurface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peer?.displayName?.take(1) ?: "?",
                    fontWeight = FontWeight.Bold,
                    color = CyanPrimary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = peer?.displayName ?: "Unknown Peer",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = LightText
                )
                if (peer?.handle?.isNotEmpty() == true) {
                    Text(
                        text = "@${peer.handle}",
                        fontSize = 12.sp,
                        color = MutedText
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages) { message ->
                ChatMessageBubble(message = message)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text(text = "Type message...", color = MutedText) },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ElevatedCardSurface,
                    unfocusedContainerColor = ElevatedCardSurface,
                    focusedBorderColor = CyanPrimary,
                    unfocusedBorderColor = IncomingBubbleSurface,
                    focusedTextColor = LightText,
                    unfocusedTextColor = LightText
                ),
                shape = RoundedCornerShape(24.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSendMessage(inputText.trim())
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CyanPrimary)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = DarkNavyBackground
                )
            }
        }
    }
}

@Composable
fun ChatMessageBubble(message: ChatMessage) {
    val alignment = if (message.isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (message.isOutgoing) OutgoingCyanBubble else IncomingBubbleSurface
    val shape = if (message.isOutgoing) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = bubbleColor,
            shape = shape,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (message.replyToText != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(DarkNavyBackground.copy(alpha = 0.3f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = message.replyToText,
                            fontSize = 12.sp,
                            color = LightText.copy(alpha = 0.8f)
                        )
                    }
                }

                Text(
                    text = message.text,
                    color = LightText,
                    fontSize = 15.sp
                )

                Text(
                    text = message.timestampLabel,
                    color = MutedText,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp)
                )
            }
        }
    }
}
