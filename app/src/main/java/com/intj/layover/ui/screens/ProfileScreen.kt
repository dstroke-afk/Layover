package com.intj.layover.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.intj.layover.diagnostics.LayoverFileLogger
import com.intj.layover.model.LayoverUiState
import com.intj.layover.model.UserProfile
import com.intj.layover.navigation.LayoverBackground
import com.intj.layover.ui.theme.LayoverTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private data class ProcessedAvatarBundle(
    val displayBytes: ByteArray,
    val compactTransferBytes: ByteArray,
    val croppedWidth: Int,
    val croppedHeight: Int
)

private fun decodeSampledBitmap(context: Context, uri: Uri, maxDim: Int = 2048): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null

    var sampleSize = 1
    while (w / sampleSize > maxDim || h / sampleSize > maxDim) {
        sampleSize *= 2
    }

    val decodeOpts = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, decodeOpts)
    }
}

private fun bitmapToPngOrJpegBytes(bitmap: Bitmap, compressAsJpeg: Boolean, quality: Int = 85): ByteArray {
    val out = ByteArrayOutputStream()
    if (compressAsJpeg) {
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    } else {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    return out.toByteArray()
}

private fun computeScaledBitmapSizePx(
    srcW: Float,
    srcH: Float,
    viewportSizePx: Float,
    zoom: Float
): Pair<Float, Float> {
    val coverScale = max(viewportSizePx / srcW, viewportSizePx / srcH)
    val scale = coverScale * zoom
    return Pair(srcW * scale, srcH * scale)
}

private fun clampPanOffset(
    rawOffset: Offset,
    srcW: Float,
    srcH: Float,
    viewportSizePx: Float,
    zoom: Float
): Offset {
    val (scaledW, scaledH) = computeScaledBitmapSizePx(srcW, srcH, viewportSizePx, zoom)
    val maxPanX = max(0f, (scaledW - viewportSizePx) / 2f)
    val maxPanY = max(0f, (scaledH - viewportSizePx) / 2f)
    return Offset(
        x = rawOffset.x.coerceIn(-maxPanX, maxPanX),
        y = rawOffset.y.coerceIn(-maxPanY, maxPanY)
    )
}

private fun cropCircularAndDownscale(
    source: Bitmap,
    panOffsetPx: Offset,
    zoom: Float,
    viewportSizePx: Float,
    transferSizePx: Int = 112
): ProcessedAvatarBundle {
    val srcW = source.width.toFloat().coerceAtLeast(1f)
    val srcH = source.height.toFloat().coerceAtLeast(1f)

    val (scaledW, scaledH) = computeScaledBitmapSizePx(srcW, srcH, viewportSizePx, zoom)
    val clampedPan = clampPanOffset(panOffsetPx, srcW, srcH, viewportSizePx, zoom)

    val center = viewportSizePx / 2f
    val drawLeft = center - scaledW / 2f + clampedPan.x
    val drawTop = center - scaledH / 2f + clampedPan.y

    val pxPerSrcX = scaledW / srcW
    val pxPerSrcY = scaledH / srcH

    val srcLeft = ((0f - drawLeft) / pxPerSrcX).coerceIn(0f, srcW - 1f)
    val srcTop = ((0f - drawTop) / pxPerSrcY).coerceIn(0f, srcH - 1f)
    val srcRight = ((viewportSizePx - drawLeft) / pxPerSrcX).coerceIn(srcLeft + 1f, srcW)
    val srcBottom = ((viewportSizePx - drawTop) / pxPerSrcY).coerceIn(srcTop + 1f, srcH)

    val cropW = max(1, (srcRight - srcLeft).roundToInt())
    val cropH = max(1, (srcBottom - srcTop).roundToInt())
    val squareSide = max(1, min(cropW, cropH))

    val cx = (srcLeft + srcRight) / 2f
    val cy = (srcTop + srcBottom) / 2f
    val sqLeft = (cx - squareSide / 2f).roundToInt().coerceIn(0, max(0, source.width - squareSide))
    val sqTop = (cy - squareSide / 2f).roundToInt().coerceIn(0, max(0, source.height - squareSide))
    val actualW = min(squareSide, source.width - sqLeft).coerceAtLeast(1)
    val actualH = min(squareSide, source.height - sqTop).coerceAtLeast(1)

    val croppedSquare = Bitmap.createBitmap(source, sqLeft, sqTop, actualW, actualH)
    val outDim = max(1, min(croppedSquare.width, croppedSquare.height))
    val normalizedSquare = if (croppedSquare.width == outDim && croppedSquare.height == outDim) {
        croppedSquare
    } else {
        Bitmap.createScaledBitmap(croppedSquare, outDim, outDim, true).also {
            croppedSquare.recycle()
        }
    }

    val maskedCropped = Bitmap.createBitmap(outDim, outDim, Bitmap.Config.ARGB_8888)
    val maskCanvas = Canvas(maskedCropped)
    val radius = outDim / 2f
    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    maskCanvas.drawCircle(radius, radius, radius, maskPaint)
    maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    maskCanvas.drawBitmap(normalizedSquare, 0f, 0f, maskPaint)
    normalizedSquare.recycle()

    val clampedTransferDim = transferSizePx.coerceIn(96, 128)
    val compactBitmap = Bitmap.createScaledBitmap(
        maskedCropped,
        clampedTransferDim,
        clampedTransferDim,
        true
    )

    val displayBytes = bitmapToPngOrJpegBytes(maskedCropped, compressAsJpeg = false)
    val compactBytes = bitmapToPngOrJpegBytes(compactBitmap, compressAsJpeg = true, quality = 82)

    maskedCropped.recycle()
    compactBitmap.recycle()

    return ProcessedAvatarBundle(
        displayBytes = displayBytes,
        compactTransferBytes = compactBytes,
        croppedWidth = outDim,
        croppedHeight = outDim
    )
}

@Composable
fun ProfileScreen(
    uiState: LayoverUiState,
    onSaveProfile: (UserProfile) -> Unit,
    onNavigateBack: () -> Unit
) {
    val colors = LayoverTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val profile = uiState.userProfile
    var selectedAge by remember(profile.age) { mutableStateOf(profile.age) }
    var ageDraft by remember(profile.age) { mutableStateOf(profile.age?.toString() ?: "") }
    var showAgeDialog by remember { mutableStateOf(false) }

    var avatarBytes by remember(profile.avatarBytes) { mutableStateOf(profile.avatarBytes) }
    var compactAvatarBytes by remember(profile.compactTransferAvatarBytes) {
        mutableStateOf(profile.compactTransferAvatarBytes)
    }
    var resolutionLabel by remember(profile.croppedResolutionLabel) {
        mutableStateOf(profile.croppedResolutionLabel)
    }

    var pendingSourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessingImage by remember { mutableStateOf(false) }

    var isVisible by remember(profile.isVisibleToNearby) {
        mutableStateOf(profile.isVisibleToNearby)
    }
    var showSavedDialog by remember { mutableStateOf(false) }

    val draftParsedAge: Int? = ageDraft.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    val isDraftAgeValid = ageDraft.trim().isEmpty() || (draftParsedAge != null && draftParsedAge in 13..120)

    val avatarBitmap = remember(avatarBytes) {
        avatarBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isProcessingImage = true
                val decoded = withContext(Dispatchers.IO) {
                    decodeSampledBitmap(context, uri)
                }
                isProcessingImage = false
                if (decoded != null) {
                    pendingSourceBitmap = decoded
                }
            }
        }
    }

    pendingSourceBitmap?.let { sourceBmp ->
        CircularCropperDialog(
            sourceBitmap = sourceBmp,
            onDismiss = {
                sourceBmp.recycle()
                pendingSourceBitmap = null
            },
            onConfirmCrop = { panOffsetPx, zoom, viewportSizePx ->
                scope.launch {
                    isProcessingImage = true
                    val processed = withContext(Dispatchers.Default) {
                        cropCircularAndDownscale(
                            source = sourceBmp,
                            panOffsetPx = panOffsetPx,
                            zoom = zoom,
                            viewportSizePx = viewportSizePx,
                            transferSizePx = 112
                        )
                    }
                    sourceBmp.recycle()
                    pendingSourceBitmap = null
                    isProcessingImage = false

                    avatarBytes = processed.displayBytes
                    compactAvatarBytes = processed.compactTransferBytes
                    resolutionLabel = "${processed.croppedWidth} x ${processed.croppedHeight} px"
                    LayoverFileLogger.log("ProfileScreen cropped avatar: display=${processed.displayBytes.size}B, compact=${processed.compactTransferBytes.size}B")
                }
            }
        )
    }

    if (showAgeDialog) {
        AlertDialog(
            onDismissRequest = { showAgeDialog = false },
            containerColor = colors.cardSurface,
            title = {
                Text(
                    text = "Set Age",
                    color = colors.lightText,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Enter your age (13–120) or leave blank.",
                        color = colors.mutedText,
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
                        placeholder = { Text("e.g. 29", color = colors.mutedText) },
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
                            focusedContainerColor = colors.darkBackground,
                            unfocusedContainerColor = colors.darkBackground,
                            focusedBorderColor = colors.cyanPrimary,
                            unfocusedBorderColor = colors.incomingBubble,
                            focusedTextColor = colors.lightText,
                            unfocusedTextColor = colors.lightText
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
                        containerColor = colors.cyanPrimary,
                        contentColor = colors.darkBackground
                    )
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAgeDialog = false }) {
                    Text("Cancel", color = colors.mutedText)
                }
            }
        )
    }

    if (showSavedDialog) {
        AlertDialog(
            onDismissRequest = {
                showSavedDialog = false
            },
            containerColor = colors.cardSurface,
            title = {
                Text(
                    text = "Changes saved",
                    color = colors.lightText,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Changes are saved.",
                    color = colors.mutedText
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSavedDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.cyanPrimary,
                        contentColor = colors.darkBackground
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
                    text = "My Profile",
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
                        modifier = Modifier.size(158.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .shadow(16.dp, CircleShape, ambientColor = colors.cyanPrimary, spotColor = colors.cyanPrimary)
                                .clip(CircleShape)
                                .background(colors.incomingBubble)
                                .border(3.dp, colors.cyanPrimary, CircleShape)
                                .clickable { pickerLauncher.launch(arrayOf("image/*")) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isProcessingImage) {
                                CircularProgressIndicator(
                                    color = colors.cyanPrimary,
                                    modifier = Modifier.size(36.dp)
                                )
                            } else if (avatarBitmap != null) {
                                Image(
                                    bitmap = avatarBitmap,
                                    contentDescription = "Profile Avatar",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Profile Avatar",
                                    tint = colors.cyanAccent,
                                    modifier = Modifier.size(86.dp)
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = (-4).dp, y = (-4).dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { pickerLauncher.launch(arrayOf("image/*")) },
                            color = colors.cyanPrimary,
                            shape = CircleShape,
                            shadowElevation = 6.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit profile picture",
                                    tint = colors.darkBackground,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    if (resolutionLabel != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Cropped: $resolutionLabel",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.mutedText
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Display Name",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.cyanAccent
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = profile.displayName,
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
                                    contentDescription = "Gender non-modifiable",
                                    tint = colors.mutedText,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = profile.gender.label,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.lightText
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.darkBackground)
                                .border(1.dp, colors.incomingBubble, RoundedCornerShape(14.dp))
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
                                    color = colors.mutedText,
                                    fontWeight = FontWeight.Medium
                                )
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit age",
                                    tint = colors.cyanAccent,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = selectedAge?.toString() ?: "Set age",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selectedAge != null) colors.lightText else colors.mutedText
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    Button(
                        onClick = {
                            val effectiveCompact = compactAvatarBytes ?: avatarBytes
                            LayoverFileLogger.log("ProfileScreen saving profile: compactBytes=${effectiveCompact?.size ?: 0}")
                            val updatedProfile = profile.copy(
                                age = selectedAge,
                                avatarBytes = avatarBytes,
                                compactTransferAvatarBytes = effectiveCompact,
                                croppedResolutionLabel = resolutionLabel,
                                isVisibleToNearby = isVisible
                            )
                            onSaveProfile(updatedProfile)
                            showSavedDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.cyanPrimary,
                            contentColor = if (colors.isDark) colors.darkBackground else Color.White
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Save Changes", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.incomingBubble)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Visible to nearby users",
                                fontWeight = FontWeight.SemiBold,
                                color = colors.lightText,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Allows nearby BLE nodes to discover your profile",
                                color = colors.mutedText,
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
                                    avatarBytes = avatarBytes,
                                    compactTransferAvatarBytes = compactAvatarBytes ?: avatarBytes,
                                    croppedResolutionLabel = resolutionLabel,
                                    isVisibleToNearby = checked
                                )
                                onSaveProfile(updatedProfile)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.darkBackground,
                                checkedTrackColor = colors.cyanPrimary,
                                uncheckedThumbColor = colors.mutedText,
                                uncheckedTrackColor = colors.darkBackground
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CircularCropperDialog(
    sourceBitmap: Bitmap,
    onDismiss: () -> Unit,
    onConfirmCrop: (panOffsetPx: Offset, zoom: Float, viewportSizePx: Float) -> Unit
) {
    val colors = LayoverTheme.colors
    val density = LocalDensity.current
    val viewportDp = 260.dp
    val viewportPx = with(density) { viewportDp.toPx() }

    val srcW = sourceBitmap.width.toFloat().coerceAtLeast(1f)
    val srcH = sourceBitmap.height.toFloat().coerceAtLeast(1f)

    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val imageBitmap = remember(sourceBitmap) { sourceBitmap.asImageBitmap() }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Crop Profile Picture",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.lightText
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Drag to pan • Pinch to zoom (${sourceBitmap.width} x ${sourceBitmap.height} px)",
                    fontSize = 12.sp,
                    color = colors.mutedText,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .size(viewportDp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF08101A))
                        .pointerInput(srcW, srcH, viewportPx) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                val nextZoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                                zoom = nextZoom
                                panOffset = clampPanOffset(
                                    rawOffset = panOffset + pan,
                                    srcW = srcW,
                                    srcH = srcH,
                                    viewportSizePx = viewportPx,
                                    zoom = nextZoom
                                )
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val (scaledW, scaledH) = computeScaledBitmapSizePx(
                            srcW = srcW,
                            srcH = srcH,
                            viewportSizePx = size.minDimension,
                            zoom = zoom
                        )
                        val clamped = clampPanOffset(
                            rawOffset = panOffset,
                            srcW = srcW,
                            srcH = srcH,
                            viewportSizePx = size.minDimension,
                            zoom = zoom
                        )
                        val dstLeft = (size.width - scaledW) / 2f + clamped.x
                        val dstTop = (size.height - scaledH) / 2f + clamped.y

                        drawImage(
                            image = imageBitmap,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(sourceBitmap.width, sourceBitmap.height),
                            dstOffset = IntOffset(dstLeft.roundToInt(), dstTop.roundToInt()),
                            dstSize = IntSize(scaledW.roundToInt(), scaledH.roundToInt())
                        )

                        val radius = size.minDimension / 2f
                        val overlayPath = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(0f, 0f, size.width, size.height))
                            addOval(
                                Rect(
                                    left = center.x - radius,
                                    top = center.y - radius,
                                    right = center.x + radius,
                                    bottom = center.y + radius
                                )
                            )
                        }
                        drawPath(
                            path = overlayPath,
                            color = Color.Black.copy(alpha = 0.52f)
                        )
                        drawCircle(
                            color = colors.cyanPrimary,
                            radius = radius,
                            center = center,
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = colors.mutedText)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirmCrop(panOffset, zoom, viewportPx) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.cyanPrimary,
                            contentColor = if (colors.isDark) colors.darkBackground else Color.White
                        )
                    ) {
                        Text("Use Photo", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}