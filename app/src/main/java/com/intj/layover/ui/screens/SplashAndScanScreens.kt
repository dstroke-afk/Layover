package com.intj.layover.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intj.layover.diagnostics.LayoverFileLogger
import com.intj.layover.model.LayoverUiState
import com.intj.layover.navigation.LayoverBackground
import com.intj.layover.ui.theme.LayoverTheme

@Composable
fun SplashScreen(
    onNavigateToScan: () -> Unit
) {
    val colors = LayoverTheme.colors
    var hasNavigated by remember { mutableStateOf(false) }

    val navigateNext = {
        if (!hasNavigated) {
            hasNavigated = true
            onNavigateToScan()
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1200)
        navigateNext()
    }

    LayoverBackground(
        modifier = Modifier
            .fillMaxSize()
            .clickable { navigateNext() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(colors.cardSurface)
                    .border(2.dp, colors.cyanPrimary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Radar,
                    contentDescription = null,
                    tint = colors.cyanPrimary,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Layover",
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.Serif,
                color = colors.lightText,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Offline Nearby Social Mesh",
                fontSize = 14.sp,
                color = colors.mutedText,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(64.dp))

            Text(
                text = "Tap anywhere to skip",
                fontSize = 12.sp,
                color = colors.mutedText.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun ScanScreen(
    uiState: LayoverUiState,
    onToggleScan: () -> Unit,
    onNavigateToPeople: (Boolean) -> Unit,
    onNavigateToProfile: () -> Unit
) {
    val colors = LayoverTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "ScanPulse")
    val scale1 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "RingScale1"
    )

    val scale2 by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "RingScale2"
    )

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    if (showDiagnosticsDialog) {
        DiagnosticsDialog(uiState = uiState, onDismiss = { showDiagnosticsDialog = false })
    }

    LayoverBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Layover",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic,
                    fontFamily = FontFamily.Serif,
                    color = colors.lightText
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onNavigateToPeople(true) }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = colors.lightText
                        )
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menu",
                                tint = colors.lightText
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(colors.cardSurface)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Your Profile", color = colors.lightText) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = colors.cyanPrimary
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onNavigateToProfile()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Diagnostics", color = colors.lightText) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.BugReport,
                                        contentDescription = null,
                                        tint = colors.cyanPrimary
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showDiagnosticsDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("About", color = colors.lightText) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = colors.cyanAccent
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showAboutDialog = true
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Center SCAN glowing cyan button & radar animation
            Box(
                modifier = Modifier.size(300.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .scale(scale2)
                        .clip(CircleShape)
                        .background(colors.cyanPrimary.copy(alpha = 0.05f))
                        .border(1.dp, colors.cyanPrimary.copy(alpha = 0.2f), CircleShape)
                )

                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .scale(scale1)
                        .clip(CircleShape)
                        .background(colors.cyanPrimary.copy(alpha = 0.1f))
                        .border(1.5.dp, colors.cyanPrimary.copy(alpha = 0.4f), CircleShape)
                )

                Surface(
                    modifier = Modifier
                        .size(130.dp)
                        .clickable {
                            uiState.transport.startDiscovery(uiState.userProfile.isVisibleToNearby)
                            onToggleScan()
                            onNavigateToPeople(false)
                        },
                    shape = CircleShape,
                    color = colors.cyanPrimary,
                    shadowElevation = 12.dp
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Radar,
                                contentDescription = "Scan",
                                tint = colors.darkBackground,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "SCAN",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = colors.darkBackground,
                                letterSpacing = 2.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (uiState.isScanning) "Searching for nearby peers..." else "Tap SCAN to discover nearby travelers",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = colors.lightText,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Zero Internet Required • Bluetooth Mesh & Wi-Fi Direct",
                fontSize = 12.sp,
                color = colors.mutedText,
                modifier = Modifier.padding(top = 6.dp),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { onNavigateToPeople(false) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.cardSurface,
                    contentColor = colors.lightText
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = "People Around (${uiState.nearbyPeers.size})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val colors = LayoverTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.cardSurface,
        titleContentColor = colors.lightText,
        textContentColor = colors.lightText,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Radar,
                    contentDescription = null,
                    tint = colors.cyanPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "About Layover",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Layover is a decentralized, off-grid peer-to-peer social messaging application designed for travelers in transit.",
                    fontSize = 14.sp,
                    color = colors.lightText
                )
                Surface(
                    color = colors.incomingBubble,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "• Transport: BLE Discovery & Wi-Fi Direct Mesh",
                            fontSize = 12.sp,
                            color = colors.cyanAccent,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Security: End-to-End Encrypted (E2EE) Signal Channels",
                            fontSize = 12.sp,
                            color = colors.cyanAccent,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = colors.cyanPrimary, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun DiagnosticsDialog(
    uiState: LayoverUiState,
    onDismiss: () -> Unit
) {
    val colors = LayoverTheme.colors
    val diag = uiState.diagnostics
    val context = LocalContext.current
    var exportStatusText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.cardSurface,
        titleContentColor = colors.lightText,
        textContentColor = colors.lightText,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    tint = colors.cyanPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Session Diagnostics",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                DiagnosticMetricRow("Discovered Peers", "${diag.discoveredPeersCount}")
                DiagnosticMetricRow("RFCOMM State", diag.rfcommStateLabel)
                DiagnosticMetricRow(
                    "Key Handshake",
                    if (diag.isKeyHandshakeComplete) "Complete" else "Pending"
                )
                DiagnosticMetricRow(
                    "Session Fingerprint",
                    diag.sessionFingerprint ?: "N/A"
                )
                DiagnosticMetricRow("Messages Sent", "${diag.messagesSent}")
                DiagnosticMetricRow("Messages Received", "${diag.messagesReceived}")
                DiagnosticMetricRow("Rate Limited Drops", "${diag.rateLimitedDrops}")
                DiagnosticMetricRow("Blocked Drops", "${diag.blockedDrops}")
                DiagnosticMetricRow(
                    "Last Error",
                    diag.lastErrorMessage ?: "None",
                    isError = diag.lastErrorMessage != null
                )

                if (exportStatusText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = exportStatusText!!,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.cyanAccent
                    )
                }

                if (diag.lastErrorMessage?.contains("permission", ignoreCase = true) == true) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.cyanAccent,
                            contentColor = colors.darkBackground
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open App Settings", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val result = LayoverFileLogger.exportLogToDownloads(context, diag)
                            exportStatusText = result.getOrElse { e -> e.message ?: "Export failed" }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.cyanPrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save to Downloads", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            uiState.transport.resetDiagnosticsCounters()
                            exportStatusText = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.cyanPrimary,
                            contentColor = colors.darkBackground
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Reset", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    TextButton(onClick = onDismiss) {
                        Text("Close", color = colors.cyanPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    )
}

@Composable
private fun DiagnosticMetricRow(
    label: String,
    value: String,
    isError: Boolean = false
) {
    val colors = LayoverTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = colors.mutedText
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isError) Color(0xFFFF5252) else colors.cyanAccent,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
