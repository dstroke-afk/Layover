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
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

@Composable
fun ChatScreen(
    peer: NearbyPeer?,
    messages: List<ChatMessage>,
    isBlocked: Boolean = false,
    isMuted: Boolean = false,
    onSendMessage: (text: String, replyToText: String?) -> Unit,
    onToggleBlockPeer: (String) -> Unit,
    onToggleMutePeer: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var replyingToMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var showChatMenu by remember { mutableStateOf(false) }

    val peerId = peer?.id ?: ""

    LayoverBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
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
                        .background(IncomingBubbleSurface)
                        .border(
                            1.5.dp,
                            if (isBlocked) MutedText else CyanPrimary,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = peer?.avatar?.initials ?: "?",
                        fontWeight = FontWeight.Bold,
                        color = if (isBlocked) MutedText else CyanPrimary,
                        fontSize = 15.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = peer?.displayName ?: "Unknown Peer",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = LightText
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
                    if (!peer?.handle.isNullOrEmpty()) {
                        Text(
                            text = if (isBlocked) "[BLOCKED]" else "@${peer.handle}",
                            fontSize = 12.sp,
                            color = MutedText
                        )
                    }
                }

                Box {
                    IconButton(onClick = { showChatMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = LightText
                        )
                    }

                    DropdownMenu(
                        expanded = showChatMenu,
                        onDismissRequest = { showChatMenu = false },
                        modifier = Modifier.background(ElevatedCardSurface)
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isMuted) "Unmute Chat" else "Mute Chat", color = LightText) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeOff,
                                    contentDescription = null,
                                    tint = CyanAccent
                                )
                            },
                            onClick = {
                                showChatMenu = false
                                if (peerId.isNotEmpty()) onToggleMutePeer(peerId)
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
                                showChatMenu = false
                                if (peerId.isNotEmpty()) onToggleBlockPeer(peerId)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Security / E2EE & Block Status Banners
            if (isBlocked) {
                Surface(
                    color = Color(0xFFFF5252).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = null,
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Peer is blocked. Unblock to send or receive messages.",
                            fontSize = 12.sp,
                            color = Color(0xFFFF5252),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else {
                Surface(
                    color = Color(0xFF10B981).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "End-to-End Encrypted (AES-GCM-256)",
                            fontSize = 11.sp,
                            color = Color(0xFF10B981),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Message List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatMessageBubble(
                        message = message,
                        onReplyClick = {
                            if (!isBlocked) replyingToMessage = message
                        }
                    )
                }
            }

            // Active Reply Banner if replyingToMessage != null
            replyingToMessage?.let { replyTarget ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .background(ElevatedCardSurface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Replying to message",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyanPrimary
                            )
                            Text(
                                text = replyTarget.text,
                                fontSize = 12.sp,
                                color = LightText,
                                maxLines = 1
                            )
                        }
                    }
                    IconButton(
                        onClick = { replyingToMessage = null },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel reply",
                            tint = MutedText
                        )
                    }
                }
            }

            // Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { if (!isBlocked) inputText = it },
                    placeholder = {
                        Text(
                            text = if (isBlocked) "Unblock peer to message..." else "Type message...",
                            color = MutedText
                        )
                    },
                    enabled = !isBlocked,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = ElevatedCardSurface,
                        unfocusedContainerColor = ElevatedCardSurface,
                        disabledContainerColor = ElevatedCardSurface.copy(alpha = 0.5f),
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = IncomingBubbleSurface,
                        focusedTextColor = LightText,
                        unfocusedTextColor = LightText,
                        disabledTextColor = MutedText
                    ),
                    shape = RoundedCornerShape(24.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        if (!isBlocked && inputText.isNotBlank()) {
                            onSendMessage(inputText.trim(), replyingToMessage?.text)
                            inputText = ""
                            replyingToMessage = null
                        }
                    },
                    enabled = !isBlocked && inputText.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (!isBlocked && inputText.isNotBlank()) CyanPrimary else IncomingBubbleSurface)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (!isBlocked && inputText.isNotBlank()) DarkNavyBackground else MutedText
                    )
                }
            }
        }
    }
}

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    onReplyClick: () -> Unit
) {
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
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clickable { onReplyClick() }
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
                            .background(DarkNavyBackground.copy(alpha = 0.35f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(20.dp)
                                    .background(CyanAccent)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = message.replyToText,
                                fontSize = 12.sp,
                                color = LightText.copy(alpha = 0.9f)
                            )
                        }
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
