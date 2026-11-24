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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intj.layover.model.ChatMessage
import com.intj.layover.model.NearbyPeer
import com.intj.layover.navigation.LayoverBackground
import com.intj.layover.ui.theme.CyanAccent
import com.intj.layover.ui.theme.CyanPrimary
import com.intj.layover.ui.theme.DarkNavyBackground
import com.intj.layover.ui.theme.ElevatedCardSurface
import com.intj.layover.ui.theme.IncomingBubbleSurface
import com.intj.layover.ui.theme.LightText
import com.intj.layover.ui.theme.MutedText
import com.intj.layover.ui.theme.OutgoingCyanBubble
import kotlinx.coroutines.delay

@Composable
fun ChatScreen(
    peer: NearbyPeer?,
    messages: List<ChatMessage>,
    isBlocked: Boolean = false,
    isBlockedByPeer: Boolean = false,
    isMuted: Boolean = false,
    onSendMessage: (text: String, replyToText: String?) -> Unit,
    onToggleBlockPeer: (String) -> Unit,
    onToggleMutePeer: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var replyingToMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var showChatMenu by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val isChatLocked = isBlocked || isBlockedByPeer

    val peerId = peer?.id ?: ""
    val resolvedName = peer?.displayName?.takeIf { it.isNotBlank() }
        ?: if (peerId.isNotEmpty()) "Peer (${peerId.takeLast(4)})" else "Nearby Peer"
    val resolvedInitials = remember(peer?.displayName, peer?.avatar?.initials, resolvedName) {
        if (peer?.displayName?.isNotBlank() == true) {
            initialsForName(peer.displayName)
        } else {
            peer?.avatar?.initials?.takeIf { it.isNotBlank() }
                ?: initialsForName(resolvedName)
        }
    }

    LaunchedEffect(peerId, isChatLocked) {
        if (!isChatLocked) {
            delay(100)
            runCatching {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
        } else {
            keyboardController?.hide()
        }
    }

    val latestMessageId = messages.lastOrNull()?.id
    LaunchedEffect(messages.size, latestMessageId) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(index = 0)
        }
    }

    val greyDisabledFill = Color(0xFF232933)
    val greyDisabledBorder = Color(0xFF3A4250)
    val greyDisabledText = Color(0xFF8A94A6)

    val statusText = when {
        isBlockedByPeer -> "You have been blocked"
        isBlocked -> "Blocked"
        else -> "End-to-End Encrypted"
    }

    val inputPlaceholderText = when {
        isBlockedByPeer -> "You have been blocked"
        isBlocked -> "Peer is blocked"
        else -> "Type encrypted message..."
    }

    LayoverBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
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
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isChatLocked) greyDisabledFill else IncomingBubbleSurface)
                        .border(
                            1.5.dp,
                            if (isChatLocked) greyDisabledBorder else CyanPrimary,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = resolvedInitials,
                        fontWeight = FontWeight.Bold,
                        color = if (isChatLocked) greyDisabledText else CyanPrimary,
                        fontSize = 15.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = resolvedName,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isChatLocked) greyDisabledText else LightText
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (isChatLocked) greyDisabledText else CyanAccent,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusText,
                            color = if (isChatLocked) greyDisabledText else CyanAccent,
                            fontSize = 11.sp
                        )
                    }
                }

                Box {
                    IconButton(onClick = { showChatMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = MutedText
                        )
                    }

                    DropdownMenu(
                        expanded = showChatMenu,
                        onDismissRequest = { showChatMenu = false },
                        modifier = Modifier.background(ElevatedCardSurface)
                    ) {
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
                                showChatMenu = false
                                onToggleBlockPeer(peerId)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Messages List (greyed out when chat is locked)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .alpha(if (isChatLocked) 0.45f else 1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                reverseLayout = true
            ) {
                items(messages.reversed(), key = { it.id }) { msg ->
                    ChatMessageBubble(
                        message = msg,
                        onReply = {
                            if (!isChatLocked) {
                                replyingToMessage = msg
                            }
                        }
                    )
                }
            }

            if (isBlockedByPeer) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    color = greyDisabledFill,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = null,
                            tint = greyDisabledText,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "You have been blocked",
                            color = greyDisabledText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Reply Preview Banner
            if (replyingToMessage != null && !isChatLocked) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    color = ElevatedCardSurface,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Replying to:",
                                color = CyanPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = replyingToMessage!!.text,
                                color = LightText,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = { replyingToMessage = null }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel Reply",
                                tint = MutedText
                            )
                        }
                    }
                }
            }

            // Input Bar
            val canSend = !isChatLocked && inputText.isNotBlank()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = if (isChatLocked) "" else inputText,
                    onValueChange = { if (!isChatLocked) inputText = it },
                    placeholder = {
                        Text(
                            text = inputPlaceholderText,
                            color = if (isChatLocked) greyDisabledText else MutedText
                        )
                    },
                    enabled = !isChatLocked,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = ElevatedCardSurface,
                        unfocusedContainerColor = ElevatedCardSurface,
                        disabledContainerColor = greyDisabledFill,
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = IncomingBubbleSurface,
                        disabledBorderColor = greyDisabledBorder,
                        focusedTextColor = LightText,
                        unfocusedTextColor = LightText,
                        disabledTextColor = greyDisabledText,
                        disabledPlaceholderColor = greyDisabledText
                    ),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4
                )

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(enabled = canSend) {
                            if (canSend) {
                                onSendMessage(inputText.trim(), replyingToMessage?.text)
                                inputText = ""
                                replyingToMessage = null
                            }
                        },
                    color = when {
                        isChatLocked -> greyDisabledFill
                        canSend -> CyanPrimary
                        else -> ElevatedCardSurface
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = when {
                                isChatLocked -> greyDisabledText
                                canSend -> DarkNavyBackground
                                else -> MutedText
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    onReply: () -> Unit
) {
    val isOutgoing = message.isOutgoing

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isOutgoing) OutgoingCyanBubble else IncomingBubbleSurface,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isOutgoing) 16.dp else 4.dp,
                bottomEnd = if (isOutgoing) 4.dp else 16.dp
            ),
            modifier = Modifier.width(width = 280.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                if (message.replyToText != null) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Reply,
                                contentDescription = null,
                                tint = if (isOutgoing) DarkNavyBackground else CyanAccent,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = message.replyToText,
                                color = if (isOutgoing) DarkNavyBackground else LightText,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                }

                Text(
                    text = message.text,
                    color = if (isOutgoing) DarkNavyBackground else LightText,
                    fontSize = 15.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.timestampLabel,
                        color = if (isOutgoing) DarkNavyBackground.copy(alpha = 0.7f) else MutedText,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}