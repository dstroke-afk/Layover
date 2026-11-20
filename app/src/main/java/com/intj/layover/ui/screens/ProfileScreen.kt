package com.intj.layover.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intj.layover.model.LayoverUiState
import com.intj.layover.model.UserProfile
import com.intj.layover.navigation.LayoverBackground
import com.intj.layover.ui.theme.CyanAccent
import com.intj.layover.ui.theme.CyanPrimary
import com.intj.layover.ui.theme.DarkNavyBackground
import com.intj.layover.ui.theme.ElevatedCardSurface
import com.intj.layover.ui.theme.IncomingBubbleSurface
import com.intj.layover.ui.theme.LightText
import com.intj.layover.ui.theme.MutedText

@Composable
fun ProfileScreen(
    uiState: LayoverUiState,
    onSaveProfile: (UserProfile) -> Unit,
    onNavigateBack: () -> Unit
) {
    var displayNameText by remember(uiState.userProfile) { mutableStateOf(uiState.userProfile.displayName) }
    var handleText by remember(uiState.userProfile) { mutableStateOf(uiState.userProfile.handle) }
    var isVisible by remember(uiState.userProfile) { mutableStateOf(uiState.userProfile.isVisibleToNearby) }

    val initials = remember(displayNameText) {
        displayNameText.split(" ")
            .mapNotNull { it.firstOrNull()?.toString() }
            .take(2)
            .joinToString("")
            .ifEmpty { "BE" }
            .uppercase()
    }

    LayoverBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Top Bar
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
                        tint = LightText
                    )
                }
                Text(
                    text = "Your Profile",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = LightText
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                    // Large Cyan-rimmed circular avatar
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .clip(CircleShape)
                            .background(IncomingBubbleSurface)
                            .border(2.5.dp, CyanPrimary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initials,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Editable Display Name Field
                    OutlinedTextField(
                        value = displayNameText,
                        onValueChange = { displayNameText = it },
                        label = { Text("Display Name", color = MutedText) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkNavyBackground,
                            unfocusedContainerColor = DarkNavyBackground,
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = IncomingBubbleSurface,
                            focusedTextColor = LightText,
                            unfocusedTextColor = LightText
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Editable Handle Field
                    OutlinedTextField(
                        value = handleText,
                        onValueChange = { handleText = it },
                        label = { Text("Handle", color = MutedText) },
                        prefix = { Text("@", color = CyanAccent) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkNavyBackground,
                            unfocusedContainerColor = DarkNavyBackground,
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = IncomingBubbleSurface,
                            focusedTextColor = LightText,
                            unfocusedTextColor = LightText
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Save Profile Button
                    Button(
                        onClick = {
                            val updatedProfile = uiState.userProfile.copy(
                                displayName = displayNameText.trim(),
                                handle = handleText.trim().removePrefix("@"),
                                isVisibleToNearby = isVisible
                            )
                            onSaveProfile(updatedProfile)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPrimary,
                            contentColor = DarkNavyBackground
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Save Profile", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Visible to nearby users Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(IncomingBubbleSurface)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Visible to nearby users",
                                fontWeight = FontWeight.SemiBold,
                                color = LightText,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Allows nearby BLE nodes to discover your profile",
                                color = MutedText,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        Switch(
                            checked = isVisible,
                            onCheckedChange = { checked ->
                                isVisible = checked
                                if (checked) {
                                    uiState.transport.startDiscovery(true)
                                } else {
                                    uiState.transport.stopDiscovery()
                                }
                                val updatedProfile = uiState.userProfile.copy(
                                    displayName = displayNameText.trim(),
                                    handle = handleText.trim().removePrefix("@"),
                                    isVisibleToNearby = checked
                                )
                                onSaveProfile(updatedProfile)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DarkNavyBackground,
                                checkedTrackColor = CyanPrimary,
                                uncheckedThumbColor = MutedText,
                                uncheckedTrackColor = DarkNavyBackground
                            )
                        )
                    }
                }
            }
        }
    }
}
