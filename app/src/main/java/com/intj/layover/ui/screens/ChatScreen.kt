package com.intj.layover.ui.screens

import android.graphics.BitmapFactory
import android.util.Log
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.intj.layover.ui.theme.LayoverTheme
import kotlinx.coroutines.delay

private const val TAG = "LayoverChatTrace"

private fun chatTrace(step: String, detail: String = "") {
    if (detail.isEmpty()) {
        Log.d(TAG, "[$step]")
    } else {
        Log.d(TAG, "[$step] $detail")
    }
}

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
    chatTrace("ChatScreen:ENTRY", "peerId=${peer?.id ?: "null"}, messagesCount=${messages.size}, isBlocked=$isBlocked, isBlockedByPeer=$isBlockedByPeer, isMuted=$isMuted")
    val colors = LayoverTheme.colors
    chatTrace("ChatScreen:THEME", "isDark=${colors.isDark}")
    var inputText by remember { mutableStateOf("") }
    var replyingToMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var showChatMenu by remember { mutableStateOf(false) }
    chatTrace("ChatScreen:STATE_INIT", "inputLen=${inputText.length}, replyingTo=${replyingToMessage?.id}, showChatMenu=$showChatMenu")

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    chatTrace("ChatScreen:REMEMBER_REFS", "focusRequesterCreated=true, keyboardControllerNull=${keyboardController == null}, firstVisibleIdx=${listState.firstVisibleItemIndex}")

    val isChatLocked = isBlocked || isBlockedByPeer
    chatTrace("ChatScreen:LOCK_EVAL", "isChatLocked=$isChatLocked")

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
    chatTrace("ChatScreen:PEER_RESOLVED", "peerId=$peerId, resolvedName=$resolvedName, initials=$resolvedInitials")

    val peerAvatarBytes = peer?.avatar?.avatarBase64OrBytes
    val peerAvatarBitmap = remember(peerAvatarBytes) {
        peerAvatarBytes?.takeIf { it.isNotEmpty() }?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }
    chatTrace("ChatScreen:AVATAR_DECODE", "avatarBytesLen=${peerAvatarBytes?.size ?: 0}, bitmapDecoded=${peerAvatarBitmap != null}")

    LaunchedEffect(peerId, isChatLocked) {
        chatTrace("LaunchedEffect(peerId,isChatLocked):START", "peerId=$peerId, isChatLocked=$isChatLocked")
        if (!isChatLocked) {
            chatTrace("LaunchedEffect(peerId,isChatLocked):DELAY_BEFORE_FOCUS", "waiting 100ms")
            delay(100)
            val reqResult = runCatching { focusRequester.requestFocus() }
            chatTrace("LaunchedEffect(peerId,isChatLocked):FOCUS_REQUEST", "success=${reqResult.isSuccess}, err=${reqResult.exceptionOrNull()?.message}")
            val showResult = runCatching { keyboardController?.show() }
            chatTrace("LaunchedEffect(peerId,isChatLocked):KEYBOARD_SHOW", "controllerNull=${keyboardController == null}, success=${showResult.isSuccess}, err=${showResult.exceptionOrNull()?.message}")
        } else {
            chatTrace("LaunchedEffect(peerId,isChatLocked):LOCKED_HIDE_KEYBOARD", "hiding keyboard")
            val hideResult = runCatching { keyboardController?.hide() }
            chatTrace("LaunchedEffect(peerId,isChatLocked):KEYBOARD_HIDE_DONE", "success=${hideResult.isSuccess}")
        }
        chatTrace("LaunchedEffect(peerId,isChatLocked):END", "peerId=$peerId, isChatLocked=$isChatLocked")
    }

    val latestMessageId = messages.lastOrNull()?.id
    LaunchedEffect(messages.size, latestMessageId) {
        chatTrace("LaunchedEffect(messages):START", "messagesSize=${messages.size}, latestMessageId=$latestMessageId")
        if (messages.isNotEmpty()) {
            chatTrace("LaunchedEffect(messages):SCROLL_TO_ZERO", "animating listState to index=0")
            listState.animateScrollToItem(index = 0)
            chatTrace("LaunchedEffect(messages):SCROLL_DONE", "firstVisibleItemIndex=${listState.firstVisibleItemIndex}")
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
    chatTrace("ChatScreen:LABELS", "statusText=$statusText, placeholder=$inputPlaceholderText")

    chatTrace("ChatScreen:RENDER_ROOT_BOX", "entering root Box")
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.darkBackground)
    ) {
        chatTrace("ChatScreen:RENDER_ROOT_COLUMN", "applying statusBarsPadding + padding + WindowInsets.ime")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // Header Card with display picture and display name
            val headerCardShape = RoundedCornerShape(16.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.cyanPrimary, headerCardShape),
                color = colors.cardSurface,
                shape = headerCardShape
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                IconButton(onClick = {
                    chatTrace("TopBar:BACK_CLICK", "peerId=$peerId")
                    onNavigateBack()
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = colors.lightText
                    )
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isChatLocked) greyDisabledFill else colors.incomingBubble)
                        .border(
                            1.5.dp,
                            if (isChatLocked) greyDisabledBorder else colors.cyanPrimary,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (peerAvatarBitmap != null) {
                        Image(
                            bitmap = peerAvatarBitmap.asImageBitmap(),
                            contentDescription = "$resolvedName profile picture",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .alpha(if (isChatLocked) 0.5f else 1f)
                        )
                    } else {
                        Text(
                            text = resolvedInitials,
                            fontWeight = FontWeight.Bold,
                            color = if (isChatLocked) greyDisabledText else colors.cyanPrimary,
                            fontSize = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = resolvedName,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isChatLocked) greyDisabledText else colors.lightText
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (isChatLocked) greyDisabledText else colors.cyanAccent,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusText,
                            color = if (isChatLocked) greyDisabledText else colors.cyanAccent,
                            fontSize = 11.sp
                        )
                    }
                }

                Box {
                    IconButton(onClick = {
                        chatTrace("TopBar:MENU_CLICK", "opening dropdown")
                        showChatMenu = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = colors.mutedText
                        )
                    }

                    DropdownMenu(
                        expanded = showChatMenu,
                        onDismissRequest = { showChatMenu = false },
                        modifier = Modifier.background(colors.cardSurface)
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
                                chatTrace("TopBar:BLOCK_TOGGLE", "peerId=$peerId, currentBlocked=$isBlocked")
                                showChatMenu = false
                                onToggleBlockPeer(peerId)
                            }
                        )
                    }
                }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Messages List (greyed out when chat is locked)
            chatTrace("ChatScreen:LAZY_COLUMN", "count=${messages.size}, reverseLayout=true, locked=$isChatLocked")
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
                    chatTrace("ChatScreen:LAZY_ITEM", "msgId=${msg.id}, isOutgoing=${msg.isOutgoing}")
                    ChatMessageBubble(
                        message = msg,
                        onReply = {
                            chatTrace("ChatScreen:MSG_REPLY_CLICK", "msgId=${msg.id}, locked=$isChatLocked")
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
                    color = colors.cardSurface,
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
                                color = colors.cyanPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = replyingToMessage!!.text,
                                color = colors.lightText,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = { replyingToMessage = null }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel Reply",
                                tint = colors.mutedText
                            )
                        }
                    }
                }
            }

            // Input Bar
            val canSend = !isChatLocked && inputText.isNotBlank()
            chatTrace("ChatScreen:INPUT_BAR_EVAL", "canSend=$canSend, isChatLocked=$isChatLocked, inputLen=${inputText.length}")
            val idleInputSurface = Color(0xFF1D242E)
            val idleInputBorder = Color(0xFF333D4B)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = if (isChatLocked) "" else inputText,
                    onValueChange = { newVal ->
                        chatTrace("InputField:VALUE_CHANGE", "locked=$isChatLocked, newLen=${newVal.length}")
                        if (!isChatLocked) inputText = newVal
                    },
                    placeholder = {
                        Text(
                            text = inputPlaceholderText,
                            color = if (isChatLocked) greyDisabledText else colors.mutedText,
                            fontSize = 15.sp
                        )
                    },
                    enabled = !isChatLocked,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = idleInputSurface,
                        unfocusedContainerColor = idleInputSurface,
                        disabledContainerColor = greyDisabledFill,
                        focusedBorderColor = colors.cyanPrimary,
                        unfocusedBorderColor = idleInputBorder,
                        disabledBorderColor = greyDisabledBorder,
                        focusedTextColor = colors.lightText,
                        unfocusedTextColor = colors.lightText,
                        disabledTextColor = greyDisabledText,
                        disabledPlaceholderColor = greyDisabledText
                    ),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4
                )

                Spacer(modifier = Modifier.width(10.dp))

                Surface(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .clickable(enabled = canSend) {
                            chatTrace("SendButton:CLICK", "canSend=$canSend, trimmedLen=${inputText.trim().length}, replyId=${replyingToMessage?.id}")
                            if (canSend) {
                                val trimmed = inputText.trim()
                                val replyText = replyingToMessage?.text
                                chatTrace("SendButton:DISPATCH", "sending len=${trimmed.length}, hasReply=${replyText != null}")
                                onSendMessage(trimmed, replyText)
                                inputText = ""
                                replyingToMessage = null
                                chatTrace("SendButton:RESET_STATE", "cleared inputText and replyingToMessage")
                            }
                        },
                    color = when {
                        isChatLocked -> greyDisabledFill
                        canSend -> colors.cyanPrimary
                        else -> Color(0xFF28303D)
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = when {
                                isChatLocked -> greyDisabledText
                                canSend -> colors.darkBackground
                                else -> colors.mutedText
                            },
                            modifier = Modifier.size(22.dp)
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
    chatTrace("ChatMessageBubble:ENTRY", "msgId=${message.id}, isOutgoing=${message.isOutgoing}, hasReply=${message.replyToText != null}, textLen=${message.text.length}")
    val colors = LayoverTheme.colors
    val isOutgoing = message.isOutgoing
    val outgoingTextColor = if (colors.isDark) colors.darkBackground else Color.White

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        val bubbleShape = RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = if (isOutgoing) 16.dp else 4.dp,
            bottomEnd = if (isOutgoing) 4.dp else 16.dp
        )
        Surface(
            color = if (isOutgoing) colors.outgoingBubble else colors.incomingBubble,
            shape = bubbleShape,
            modifier = Modifier
                .widthIn(min = 72.dp, max = 280.dp)
                .border(1.dp, colors.cyanPrimary, bubbleShape)
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
                                tint = if (isOutgoing) outgoingTextColor else colors.cyanAccent,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = message.replyToText,
                                color = if (isOutgoing) outgoingTextColor else colors.lightText,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                }

                Text(
                    text = message.text,
                    color = if (isOutgoing) outgoingTextColor else colors.lightText,
                    fontSize = 15.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.timestampLabel,
                        color = if (isOutgoing) outgoingTextColor.copy(alpha = 0.75f) else colors.mutedText,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}