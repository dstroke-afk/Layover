package com.intj.layover.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intj.layover.model.NearbyPeer
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
    val scrollState = rememberScrollState()
    var showFullScreenPhoto by remember { mutableStateOf(false) }

    fun extractAgeFromAny(obj: Any?): Int? {
        if (obj == null) return null
        return runCatching {
            val raw = obj::class.members.firstOrNull { it.name == "age" }?.call(obj)
            when (raw) {
                is Int -> raw.takeIf { it > 0 }
                is Long -> raw.toInt().takeIf { it > 0 }
                is Number -> raw.toInt().takeIf { it > 0 }
                is String -> raw.trim().toIntOrNull()?.takeIf { it > 0 }
                else -> null
            }
        }.getOrNull()
    }

    val resolvedAge = age
        ?: extractAgeFromAny(peer)
        ?: extractAgeFromAny(peer?.avatar)

    val effectiveId = peer?.id?.takeIf { it.isNotBlank() } ?: peerIdFallback
    val displayName = peer?.displayName?.takeIf { it.isNotBlank() }
        ?: if (effectiveId.isNotEmpty()) "Peer (${effectiveId.takeLast(4)})" else "Nearby Peer"
    val handleText = peer?.handle?.takeIf { it.isNotBlank() } ?: effectiveId
    val initials = remember(peer?.displayName, peer?.avatar?.initials, displayName) {
        if (peer?.displayName?.isNotBlank() == true) {
            initialsForName(peer.displayName)
        } else {
            peer?.avatar?.initials?.takeIf { it.isNotBlank() } ?: initialsForName(displayName)
        }
    }

    val avatarBytes = peer?.avatar?.avatarBase64OrBytes?.takeIf { it.isNotEmpty() }
        ?: fallbackAvatarBytes?.takeIf { it.isNotEmpty() }
    val avatarBitmap = remember(avatarBytes) {
        avatarBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()?.asImageBitmap()
        }
    }

    if (showFullScreenPhoto && avatarBitmap != null) {
        BackHandler {
            showFullScreenPhoto = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LayoverBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
                            tint = colors.lightText
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Peer Profile",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.lightText
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Read-only avatar circle (clickable when photo is present to open full screen)
                Box(
                    modifier = Modifier
                        .size(116.dp)
                        .clip(CircleShape)
                        .then(
                            if (avatarBitmap != null) {
                                Modifier.clickable { showFullScreenPhoto = true }
                            } else {
                                Modifier
                            }
                        )
                        .background(colors.incomingBubble)
                        .border(2.dp, colors.cyanPrimary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap,
                            contentDescription = "$displayName profile picture",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                        )
                    } else {
                        Text(
                            text = initials,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.cyanPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = displayName,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.lightText
                )

                if (handleText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "@$handleText",
                        fontSize = 14.sp,
                        color = colors.mutedText
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, colors.cyanPrimary.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = colors.cyanPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Profile Details",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.lightText
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Display Name",
                                fontSize = 13.sp,
                                color = colors.mutedText
                            )
                            Text(
                                text = displayName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.lightText
                            )
                        }

                        if (resolvedAge != null && resolvedAge > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Age",
                                    fontSize = 13.sp,
                                    color = colors.mutedText
                                )
                                Text(
                                    text = resolvedAge.toString(),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.lightText
                                )
                            }
                        }

                        if (handleText.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Peer ID",
                                    fontSize = 13.sp,
                                    color = colors.mutedText
                                )
                                Text(
                                    text = handleText,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.lightText
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Full-screen photo lightbox overlay
        if (showFullScreenPhoto && avatarBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.96f))
                    .clickable { showFullScreenPhoto = false }
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Image(
                    bitmap = avatarBitmap,
                    contentDescription = "$displayName full screen profile picture",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = displayName,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp)
                    )

                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.14f)
                    ) {
                        IconButton(onClick = { showFullScreenPhoto = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close full screen photo",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}
