package com.intj.layover.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.intj.layover.model.NearbyPeer
import com.intj.layover.model.formatPeerNameWithBadge
import com.intj.layover.navigation.LayoverBackground
import com.intj.layover.ui.theme.LayoverTheme

@Composable
fun PeerProfileScreen(
    peer: NearbyPeer?,
    peerIdFallback: String = "",
    age: Int? = null,
    fallbackAvatarBytes: ByteArray? = null,
    isBlocked: Boolean = false,
    isBlockedByPeer: Boolean = false,
    onNavigateBack: () -> Unit
) {
    val colors = LayoverTheme.colors

    val effectivePeerId = peer?.id?.takeIf { it.isNotBlank() } ?: peerIdFallback
    val resolvedName = peer?.displayName?.takeIf { it.isNotBlank() }
        ?: if (effectivePeerId.isNotEmpty()) "Peer (${effectivePeerId.takeLast(4)})" else "Nearby Peer"

    val avatarBytes = peer?.avatar?.avatarBase64OrBytes?.takeIf { it.isNotEmpty() }
        ?: fallbackAvatarBytes?.takeIf { it.isNotEmpty() }

    val avatarBitmap = remember(avatarBytes) {
        avatarBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }

    val resolvedAge = (age ?: peer?.age)?.takeIf { it in 1..255 }
    val resolvedGender = peer?.gender
    val formattedName = formatPeerNameWithBadge(resolvedName, resolvedAge, resolvedGender)
    val ageDisplayText = resolvedAge?.toString() ?: "Not shared"
    var showFullScreenAvatar by remember(effectivePeerId) { mutableStateOf(false) }

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
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = colors.lightText
                    )
                }
                Text(
                    text = "Peer Profile",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.lightText
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(158.dp)
                            .shadow(
                                elevation = 16.dp,
                                shape = CircleShape,
                                ambientColor = colors.cyanPrimary,
                                spotColor = colors.cyanPrimary
                            )
                            .clip(CircleShape)
                            .background(colors.incomingBubble)
                            .border(3.dp, colors.cyanPrimary, CircleShape)
                            .clickable(enabled = avatarBitmap != null) {
                                showFullScreenAvatar = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap,
                                contentDescription = "$resolvedName Profile Avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Default Peer Avatar",
                                tint = colors.cyanAccent,
                                modifier = Modifier.size(86.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Display Name",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.cyanAccent
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = formattedName,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.lightText,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.darkBackground)
                                .border(1.dp, colors.incomingBubble, RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Gender",
                                    fontSize = 12.sp,
                                    color = colors.mutedText,
                                    fontWeight = FontWeight.Medium
                                )
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Read-only field",
                                    tint = colors.mutedText,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = resolvedGender?.label ?: "Not shared",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (resolvedGender != null) colors.lightText else colors.mutedText
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.darkBackground)
                                .border(1.dp, colors.incomingBubble, RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Age",
                                    fontSize = 12.sp,
                                    color = colors.mutedText,
                                    fontWeight = FontWeight.Medium
                                )
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Read-only field",
                                    tint = colors.mutedText,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = ageDisplayText,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (resolvedAge != null) colors.lightText else colors.mutedText
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFullScreenAvatar && avatarBitmap != null) {
        Dialog(
            onDismissRequest = { showFullScreenAvatar = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            BackHandler { showFullScreenAvatar = false }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f))
                    .clickable { showFullScreenAvatar = false }
            ) {
                Image(
                    bitmap = avatarBitmap,
                    contentDescription = "$formattedName full-screen photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )

                Text(
                    text = formattedName,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                )

                IconButton(
                    onClick = { showFullScreenAvatar = false },
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
