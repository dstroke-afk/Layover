package com.intj.layover.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.graphics.drawable.toDrawable
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AttachFile
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.intj.layover.R
import com.intj.layover.model.ChatMessage
import com.intj.layover.model.NearbyPeer
import com.intj.layover.ui.theme.LayoverTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private const val TAG = "LayoverChatTrace"
private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L

/**
 * Formats a chat bubble arrival/delivery timestamp in the device's local timezone and default locale:
 * - Same local calendar day and within 24 hours: `h:mm a` (e.g., `1:35 PM`)
 * - Previous calendar day or older than 24 hours: `d MMM, h:mm a` (e.g., `27 Nov, 1:35 PM`)
 * - Invalid/unset (`<= 0L`) timestamps fall back to [nowMillis] instead of `"Just now"`.
 */
fun formatMessageBubbleTimestamp(
    timestampMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String {
    val safeNowMillis = if (nowMillis > 0L) nowMillis else System.currentTimeMillis()
    val effectiveMillis = if (timestampMillis > 0L) timestampMillis else safeNowMillis
    val msgDateTime = Instant.ofEpochMilli(effectiveMillis).atZone(zoneId)
    val nowDateTime = Instant.ofEpochMilli(safeNowMillis).atZone(zoneId)
    val isSameLocalDay = msgDateTime.toLocalDate() == nowDateTime.toLocalDate()
    val isTodayAndWithin24Hours = isSameLocalDay && abs(safeNowMillis - effectiveMillis) < ONE_DAY_MILLIS
    val pattern = if (isTodayAndWithin24Hours) "h:mm a" else "d MMM, h:mm a"
    return DateTimeFormatter.ofPattern(pattern, locale).format(msgDateTime)
}

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
    fallbackAvatarBytes: ByteArray? = null,
    onToggleBlockPeer: (String) -> Unit,
    onToggleMutePeer: (String) -> Unit,
    onOpenPeerProfile: (String) -> Unit = {},
    onSendPhotoUri: (Uri) -> Unit = {},
    onNavigateBack: () -> Unit
) {
    chatTrace("ChatScreen:ENTRY", "peerId=${peer?.id ?: "null"}, messagesCount=${messages.size}, isBlocked=$isBlocked, isBlockedByPeer=$isBlockedByPeer, isMuted=$isMuted")
    val colors = LayoverTheme.colors
    chatTrace("ChatScreen:THEME", "isDark=${colors.isDark}")
    var inputText by remember { mutableStateOf("") }
    var replyingToMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var showChatMenu by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val isInspectionMode = LocalInspectionMode.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val inputInteractionSource = remember { MutableInteractionSource() }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        showAttachmentSheet = false
        if (uri != null) {
            onSendPhotoUri(uri)
        }
    }

    BackHandler(enabled = showAttachmentSheet) {
        showAttachmentSheet = false
    }

    LaunchedEffect(inputInteractionSource) {
        inputInteractionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                showAttachmentSheet = false
            }
        }
    }
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

    val peerAvatarBytes = peer?.avatar?.avatarBase64OrBytes?.takeIf { it.isNotEmpty() }
        ?: fallbackAvatarBytes?.takeIf { it.isNotEmpty() }
    val peerAvatarBitmap = remember(peerAvatarBytes) {
        peerAvatarBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }
    chatTrace("ChatScreen:AVATAR_DECODE", "avatarBytesLen=${peerAvatarBytes?.size ?: 0}, bitmapDecoded=${peerAvatarBitmap != null}")

    LaunchedEffect(isChatLocked) {
        chatTrace("LaunchedEffect(isChatLocked):START", "isChatLocked=$isChatLocked")
        if (isChatLocked) {
            chatTrace("LaunchedEffect(isChatLocked):LOCKED_HIDE_KEYBOARD", "hiding keyboard")
            val hideResult = runCatching { keyboardController?.hide() }
            chatTrace("LaunchedEffect(isChatLocked):KEYBOARD_HIDE_DONE", "success=${hideResult.isSuccess}")
        }
        chatTrace("LaunchedEffect(isChatLocked):END", "isChatLocked=$isChatLocked")
    }

    val latestMessageId = messages.lastOrNull()?.id
    LaunchedEffect(messages.size, latestMessageId) {
        chatTrace("LaunchedEffect(messages):START", "messagesSize=${messages.size}, latestMessageId=$latestMessageId")
        if (messages.isNotEmpty()) {
            val targetIdx = (messages.size - 1).coerceAtLeast(0)
            chatTrace("LaunchedEffect(messages):SCROLL_TO_BOTTOM", "snapping listState to index=$targetIdx")
            listState.scrollToItem(index = targetIdx)
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
        chatTrace("ChatScreen:RENDER_ROOT_COLUMN", "pinning header to top and applying nav+ime insets to bottom bar")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Box(modifier = Modifier.statusBarsPadding().padding(top = 4.dp)) {
            // Header Card with display picture and display name (clickable to view read-only peer profile)
            val headerCardShape = RoundedCornerShape(16.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(headerCardShape)
                    .clickable {
                        chatTrace("TopBar:PEER_PROFILE_CLICK", "peerId=$peerId")
                        onOpenPeerProfile(peerId)
                    }
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
                            text = {
                                Text(
                                    text = if (isMuted) "Unmute Peer" else "Mute Peer",
                                    color = colors.lightText
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                    contentDescription = null,
                                    tint = colors.cyanPrimary
                                )
                            },
                            onClick = {
                                chatTrace("TopBar:MUTE_TOGGLE", "peerId=$peerId, currentMuted=$isMuted")
                                showChatMenu = false
                                onToggleMutePeer(peerId)
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
                                chatTrace("TopBar:BLOCK_TOGGLE", "peerId=$peerId, currentBlocked=$isBlocked")
                                showChatMenu = false
                                onToggleBlockPeer(peerId)
                            }
                        )
                    }
                }
                }
            }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Messages List (greyed out when chat is locked)
            chatTrace("ChatScreen:LAZY_COLUMN", "count=${messages.size}, reverseLayout=false, locked=$isChatLocked")
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .alpha(if (isChatLocked) 0.45f else 1f),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                contentPadding = PaddingValues(vertical = 8.dp),
                reverseLayout = false
            ) {
                items(messages, key = { it.id }) { msg ->
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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
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

            // Input Bar (perceptually matched lighter slate grey in white theme so it matches the black theme's visual appearance)
            val canSend = !isChatLocked && inputText.isNotBlank()
            chatTrace("ChatScreen:INPUT_BAR_EVAL", "canSend=$canSend, isChatLocked=$isChatLocked, inputLen=${inputText.length}")
            val idleInputSurface = if (colors.isDark) Color(0xFF4A5260) else Color(0xFF6B7585)
            val idleInputBorder = if (colors.isDark) Color(0xFF6B768A) else Color(0xFF8A96A8)
            val idleSendSurface = if (colors.isDark) Color(0xFF28303D) else Color(0xFF465060)
            LayoverTheme(darkTheme = true) {
                val inputBarColors = LayoverTheme.colors
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
                            if (!isChatLocked) {
                                showAttachmentSheet = false
                                inputText = newVal
                            }
                        },
                        placeholder = {
                            Text(
                                text = inputPlaceholderText,
                                color = if (isChatLocked) greyDisabledText else Color.White.copy(alpha = 0.72f),
                                fontSize = 15.sp
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (!isChatLocked) {
                                        showAttachmentSheet = !showAttachmentSheet
                                    }
                                },
                                enabled = !isChatLocked,
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = "Attach photo",
                                    tint = if (isChatLocked) greyDisabledText else inputBarColors.cyanPrimary
                                )
                            }
                        },
                        interactionSource = inputInteractionSource,
                        enabled = !isChatLocked,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = idleInputSurface,
                            unfocusedContainerColor = idleInputSurface,
                            disabledContainerColor = greyDisabledFill,
                            focusedBorderColor = inputBarColors.cyanPrimary,
                            unfocusedBorderColor = idleInputBorder,
                            disabledBorderColor = greyDisabledBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color.White,
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
                                    showAttachmentSheet = false
                                    chatTrace("SendButton:RESET_STATE", "cleared inputText and replyingToMessage")
                                }
                            },
                        color = when {
                            isChatLocked -> greyDisabledFill
                            canSend -> inputBarColors.cyanPrimary
                            else -> idleSendSurface
                        }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = when {
                                    isChatLocked -> greyDisabledText
                                    canSend -> inputBarColors.darkBackground
                                    else -> inputBarColors.mutedText
                                },
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            // Inline bottom overflow panel when the software keyboard is not currently visible
            if (showAttachmentSheet && !isChatLocked && (imeBottomPx == 0 || isInspectionMode)) {
                ClippyPhotoOverflowCard(
                    onAddPhotosClick = { photoPickerLauncher.launch(arrayOf("image/*")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 4.dp)
                        .height(220.dp)
                )
            }
            }
        }

        // Semi-transparent keyboard overlay when the software keyboard is visible
        if (showAttachmentSheet && !isChatLocked && imeBottomPx > 0 && !isInspectionMode) {
            KeyboardPhotoOverflowWindow(
                imeHeightPx = imeBottomPx,
                onDismissRequest = { showAttachmentSheet = false },
                onAddPhotosClick = { photoPickerLauncher.launch(arrayOf("image/*")) }
            )
        }
    }
}

@Composable
private fun KeyboardPhotoOverflowWindow(
    imeHeightPx: Int,
    onDismissRequest: () -> Unit,
    onAddPhotosClick: () -> Unit
) {
    val density = LocalDensity.current
    val imeHeightDp = with(density) { imeHeightPx.toDp() }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.apply {
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
                setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
                setGravity(Gravity.BOTTOM)
                setWindowAnimations(0)
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, imeHeightPx)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(imeHeightDp)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            ClippyPhotoOverflowCard(
                onAddPhotosClick = onAddPhotosClick,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ClippyPhotoOverflowCard(
    onAddPhotosClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LayoverTheme.colors
    val cardShape = RoundedCornerShape(16.dp)
    val overlayAlpha = if (colors.isDark) 0.76f else 0.80f
    Box(
        modifier = modifier
            .clip(cardShape)
            .background(colors.cardSurface.copy(alpha = overlayAlpha))
            .border(1.dp, colors.cyanPrimary.copy(alpha = 0.65f), cardShape)
            .clickable { onAddPhotosClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_add_photos_sunrise),
                contentDescription = "Add Photos",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Click to add Photos",
                color = colors.lightText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 760)
@Composable
private fun ChatScreenClippyPreview() {
    LayoverTheme(darkTheme = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LayoverTheme.colors.darkBackground)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Bottom
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        placeholder = { Text("Type encrypted message...", color = Color.White.copy(alpha = 0.72f)) },
                        trailingIcon = {
                            IconButton(onClick = {}, modifier = Modifier.padding(end = 4.dp)) {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = "Attach photo",
                                    tint = LayoverTheme.colors.cyanPrimary
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF4A5260),
                            unfocusedContainerColor = Color(0xFF4A5260),
                            unfocusedBorderColor = Color(0xFF6B768A)
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Surface(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape),
                        color = Color(0xFF28303D)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = LayoverTheme.colors.mutedText,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                ClippyPhotoOverflowCard(
                    onAddPhotosClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(220.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 760)
@Composable
private fun ChatScreenLightInputBarPreview() {
    LayoverTheme(darkTheme = false) {
        ChatScreen(
            peer = NearbyPeer(id = "peer_1", displayName = "Balaji", handle = "balaji"),
            messages = emptyList(),
            onSendMessage = { _, _ -> },
            onToggleBlockPeer = {},
            onToggleMutePeer = {},
            onNavigateBack = {}
        )
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
                .clickable { onReply() }
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
                    val formattedTime = formatMessageBubbleTimestamp(message.timestampMillis)
                    Text(
                        text = formattedTime,
                        color = if (isOutgoing) outgoingTextColor.copy(alpha = 0.75f) else colors.mutedText,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}