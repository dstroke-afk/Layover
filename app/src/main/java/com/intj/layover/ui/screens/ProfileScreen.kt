package com.intj.layover.ui.screens

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
    val profile = uiState.userProfile
    var selectedAge by remember(profile.age) { mutableStateOf(profile.age) }
    var ageDraft by remember(profile.age) { mutableStateOf(profile.age?.toString() ?: "") }
    var showAgeDialog by remember { mutableStateOf(false) }
    var isVisible by remember(profile.isVisibleToNearby) {
        mutableStateOf(profile.isVisibleToNearby)
    }
    var showSavedDialog by remember { mutableStateOf(false) }

    val draftParsedAge: Int? = ageDraft.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    val isDraftAgeValid = ageDraft.trim().isEmpty() || (draftParsedAge != null && draftParsedAge in 13..120)

    if (showAgeDialog) {
        AlertDialog(
            onDismissRequest = { showAgeDialog = false },
            containerColor = ElevatedCardSurface,
            title = {
                Text(
                    text = "Set Age",
                    color = LightText,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Enter your age (13–120) or leave blank.",
                        color = MutedText,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = ageDraft,
                        onValueChange = { raw ->
                            if (raw.length <= 3 && raw.all { it.isDigit() }) {
                                ageDraft = raw
                            }
                        },
                        singleLine = true,
                        isError = !isDraftAgeValid,
                        placeholder = { Text("e.g. 29", color = MutedText) },
                        supportingText = {
                            if (!isDraftAgeValid) {
                                Text(
                                    text = "Please enter a valid age between 13 and 120.",
                                    fontSize = 12.sp
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkNavyBackground,
                            unfocusedContainerColor = DarkNavyBackground,
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = IncomingBubbleSurface,
                            focusedTextColor = LightText,
                            unfocusedTextColor = LightText
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = isDraftAgeValid,
                    onClick = {
                        selectedAge = draftParsedAge
                        showAgeDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = DarkNavyBackground
                    )
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAgeDialog = false }) {
                    Text("Cancel", color = MutedText)
                }
            }
        )
    }

    if (showSavedDialog) {
        AlertDialog(
            onDismissRequest = {
                showSavedDialog = false
            },
            containerColor = ElevatedCardSurface,
            title = {
                Text(
                    text = "Changes saved",
                    color = LightText,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Changes are saved.",
                    color = MutedText
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSavedDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = DarkNavyBackground
                    )
                ) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            }
        )
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
                    text = "My Profile",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = LightText
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ElevatedCardSurface),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Prominent Cyan-Ringed Circular Avatar (20% larger)
                    Box(
                        modifier = Modifier
                            .size(158.dp)
                            .shadow(16.dp, CircleShape, ambientColor = CyanPrimary, spotColor = CyanPrimary)
                            .clip(CircleShape)
                            .background(IncomingBubbleSurface)
                            .border(3.dp, CyanPrimary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Profile Avatar",
                            tint = CyanAccent,
                            modifier = Modifier.size(86.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = "Display Name",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = CyanAccent
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = profile.displayName,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = LightText,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Two-column metadata row directly below display name:
                    // Read-only Gender + Editable Age matching Gender card styling
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        // Read-only Gender column
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(DarkNavyBackground)
                                .border(1.dp, IncomingBubbleSurface, RoundedCornerShape(14.dp))
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
                                    color = MutedText,
                                    fontWeight = FontWeight.Medium
                                )
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Gender non-modifiable",
                                    tint = MutedText,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = profile.gender.label,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = LightText
                            )
                        }

                        // Editable Age column matching Gender card styling
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(DarkNavyBackground)
                                .border(1.dp, IncomingBubbleSurface, RoundedCornerShape(14.dp))
                                .clickable {
                                    ageDraft = selectedAge?.toString() ?: ""
                                    showAgeDialog = true
                                }
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
                                    color = MutedText,
                                    fontWeight = FontWeight.Medium
                                )
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit age",
                                    tint = CyanAccent,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = selectedAge?.toString() ?: "Set age",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selectedAge != null) LightText else MutedText
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    // Save Changes Button
                    Button(
                        onClick = {
                            val updatedProfile = profile.copy(
                                age = selectedAge,
                                isVisibleToNearby = isVisible
                            )
                            onSaveProfile(updatedProfile)
                            showSavedDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPrimary,
                            contentColor = DarkNavyBackground
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Save Changes", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    Spacer(modifier = Modifier.height(22.dp))

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
                                val updatedProfile = profile.copy(
                                    age = selectedAge,
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