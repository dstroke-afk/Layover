package com.intj.layover.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.intj.layover.ble.BlePermissionHelper
import com.intj.layover.diagnostics.LayoverFileLogger
import com.intj.layover.model.LayoverUiState
import com.intj.layover.model.UserProfile
import com.intj.layover.navigation.LayoverBackground
import com.intj.layover.ui.theme.LayoverTheme
import com.intj.layover.wifi.EncryptedPhotoStreamProtocol
import com.intj.layover.wifi.PhotoSizeValidationResult
import com.intj.layover.wifi.PhotoSizeValidator
import com.intj.layover.wifi.PhotoTransferConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_CROPPED_FULL_RES_DP_DIM = 2048
private const val MAX_LOCAL_DISPLAY_DP_DIM = 512
private const val MAX_BLE_COMPACT_AVATAR_BYTES = 6 * 1024L

private data class ProcessedAvatarBundle(
    val fullResCroppedBytes: ByteArray,
    val fullResSha256Hex: String,
    val displayBytes: ByteArray,
    val compactTransferBytes: ByteArray,
    val croppedWidth: Int,
    val croppedHeight: Int
)

/**
 * Computes the target square pixel dimension for the cropped full-resolution profile picture
 * based on the source bitmap dimensions, cropper viewport size, and user zoom factor.
 * Preserves native cropped resolution up to [maxDim] without artificially upscaling small images.
 */
internal fun computeCroppedFullResDimension(
    srcWidth: Int,
    srcHeight: Int,
    viewportSizePx: Float,
    zoom: Float,
    maxDim: Int = MAX_CROPPED_FULL_RES_DP_DIM
): Int {
    val safeSrcW = srcWidth.coerceAtLeast(1).toFloat()
    val safeSrcH = srcHeight.coerceAtLeast(1).toFloat()
    val safeViewport = viewportSizePx.coerceAtLeast(1f)
    val safeZoom = zoom.coerceAtLeast(1f)
    val coverScale = max(safeViewport / safeSrcW, safeViewport / safeSrcH)
    val nativeCropSide = (safeViewport / (coverScale * safeZoom)).roundToInt()
    val minSourceSide = min(srcWidth, srcHeight).coerceAtLeast(128)
    val floorDim = min(safeViewport.roundToInt().coerceAtLeast(128), minSourceSide)
    return nativeCropSide.coerceIn(floorDim, maxDim.coerceAtLeast(floorDim))
}

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.preScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.preScale(-1f, 1f)
        }
        else -> return bitmap
    }
    return runCatching {
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed != bitmap) {
            bitmap.recycle()
        }
        transformed
    }.getOrDefault(bitmap)
}

private fun decodeSampledBitmapFromUri(
    context: Context,
    uri: Uri,
    maxDim: Int = MAX_CROPPED_FULL_RES_DP_DIM
): Bitmap? {
    val exifOrientation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
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
    val rawBitmap = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        }
    }.getOrNull() ?: return null

    return applyExifOrientation(rawBitmap, exifOrientation)
}

private fun compressJpegWithinByteCap(
    bitmap: Bitmap,
    maxBytes: Long,
    qualities: IntArray = intArrayOf(92, 85, 78, 70, 60, 50)
): ByteArray? {
    var working = bitmap
    var ownsWorking = false
    try {
        repeat(4) {
            for (quality in qualities) {
                val out = ByteArrayOutputStream()
                working.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val encoded = out.toByteArray()
                if (encoded.isNotEmpty() && encoded.size.toLong() <= maxBytes) {
                    return encoded
                }
            }
            val nextW = (working.width * 0.8f).roundToInt().coerceAtLeast(128)
            val nextH = (working.height * 0.8f).roundToInt().coerceAtLeast(128)
            if (nextW == working.width && nextH == working.height) return null
            val scaled = Bitmap.createScaledBitmap(working, nextW, nextH, true)
            if (ownsWorking && working != scaled) {
                working.recycle()
            }
            working = scaled
            ownsWorking = true
        }
    } finally {
        if (ownsWorking) {
            working.recycle()
        }
    }
    return null
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
    rotationDegrees: Float,
    viewportSizePx: Float,
    transferSizePx: Int = 112
): ProcessedAvatarBundle? {
    val srcW = source.width.toFloat().coerceAtLeast(1f)
    val srcH = source.height.toFloat().coerceAtLeast(1f)

    val (scaledW, scaledH) = computeScaledBitmapSizePx(srcW, srcH, viewportSizePx, zoom)
    val clampedPan = clampPanOffset(panOffsetPx, srcW, srcH, viewportSizePx, zoom)

    val fullResDim = computeCroppedFullResDimension(
        srcWidth = source.width,
        srcHeight = source.height,
        viewportSizePx = viewportSizePx,
        zoom = zoom
    )
    val renderedCropped = Bitmap.createBitmap(fullResDim, fullResDim, Bitmap.Config.ARGB_8888)
    val vpCanvas = Canvas(renderedCropped)

    val scaleRatio = fullResDim.toFloat() / viewportSizePx.coerceAtLeast(1f)
    val centerPx = fullResDim / 2f
    val drawScaledW = scaledW * scaleRatio
    val drawScaledH = scaledH * scaleRatio
    val drawLeft = centerPx - drawScaledW / 2f + clampedPan.x * scaleRatio
    val drawTop = centerPx - drawScaledH / 2f + clampedPan.y * scaleRatio

    vpCanvas.save()
    vpCanvas.rotate(rotationDegrees, centerPx, centerPx)
    val dstRect = android.graphics.RectF(drawLeft, drawTop, drawLeft + drawScaledW, drawTop + drawScaledH)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    vpCanvas.drawBitmap(source, null, dstRect, paint)
    vpCanvas.restore()

    return try {
        val fullResCroppedBytes = compressJpegWithinByteCap(
            bitmap = renderedCropped,
            maxBytes = PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES
        ) ?: return null
        if (PhotoSizeValidator.validateSize(fullResCroppedBytes.size.toLong()) !is PhotoSizeValidationResult.Valid) {
            return null
        }
        val fullResSha256Hex = EncryptedPhotoStreamProtocol.sha256Hex(fullResCroppedBytes)

        val displayDim = min(fullResDim, MAX_LOCAL_DISPLAY_DP_DIM)
        val displayBitmap = if (displayDim < fullResDim) {
            Bitmap.createScaledBitmap(renderedCropped, displayDim, displayDim, true)
        } else {
            renderedCropped
        }
        val displayBytes = try {
            compressJpegWithinByteCap(
                bitmap = displayBitmap,
                maxBytes = 256 * 1024L,
                qualities = intArrayOf(88, 80, 72)
            ) ?: fullResCroppedBytes
        } finally {
            if (displayBitmap != renderedCropped) {
                displayBitmap.recycle()
            }
        }

        val clampedTransferDim = transferSizePx.coerceIn(96, 128)
        val compactBitmap = Bitmap.createScaledBitmap(
            renderedCropped,
            clampedTransferDim,
            clampedTransferDim,
            true
        )
        val compactBytes = try {
            compressJpegWithinByteCap(
                bitmap = compactBitmap,
                maxBytes = MAX_BLE_COMPACT_AVATAR_BYTES,
                qualities = intArrayOf(82, 74, 65, 55, 45)
            ) ?: run {
                val fallbackOut = ByteArrayOutputStream()
                compactBitmap.compress(Bitmap.CompressFormat.JPEG, 70, fallbackOut)
                fallbackOut.toByteArray()
            }
        } finally {
            compactBitmap.recycle()
        }

        ProcessedAvatarBundle(
            fullResCroppedBytes = fullResCroppedBytes,
            fullResSha256Hex = fullResSha256Hex,
            displayBytes = displayBytes,
            compactTransferBytes = compactBytes,
            croppedWidth = fullResDim,
            croppedHeight = fullResDim
        )
    } finally {
        renderedCropped.recycle()
    }
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
    var savedFullResBytes by remember { mutableStateOf<ByteArray?>(null) }
    var avatarErrorMessage by remember { mutableStateOf<String?>(null) }
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

    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isProcessingImage = true
                val decoded = withContext(Dispatchers.IO) {
                    decodeSampledBitmapFromUri(context, uri)
                }
                isProcessingImage = false
                if (decoded != null) {
                    avatarErrorMessage = null
                    pendingSourceBitmap = decoded
                } else {
                    avatarErrorMessage = "Unable to decode selected photo"
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
            onConfirmCrop = { panOffsetPx, zoom, rotationDegrees, viewportSizePx ->
                scope.launch {
                    isProcessingImage = true
                    val processed = withContext(Dispatchers.Default) {
                        cropCircularAndDownscale(
                            source = sourceBmp,
                            panOffsetPx = panOffsetPx,
                            zoom = zoom,
                            rotationDegrees = rotationDegrees,
                            viewportSizePx = viewportSizePx,
                            transferSizePx = 112
                        )
                    }
                    sourceBmp.recycle()
                    pendingSourceBitmap = null
                    isProcessingImage = false

                    if (processed == null) {
                        avatarErrorMessage = "Cropped photo exceeds 3 MB limit or could not be encoded"
                        return@launch
                    }

                    avatarBytes = processed.displayBytes
                    compactAvatarBytes = processed.compactTransferBytes
                    savedFullResBytes = processed.fullResCroppedBytes
                    resolutionLabel = "${processed.croppedWidth} x ${processed.croppedHeight} px"
                    avatarErrorMessage = null
                    LayoverFileLogger.log(
                        "ProfileScreen cropped avatar: croppedFullRes=${processed.fullResCroppedBytes.size}B, " +
                            "sha256=${processed.fullResSha256Hex.take(12)}, " +
                            "display=${processed.displayBytes.size}B, " +
                            "compact=${processed.compactTransferBytes.size}B, " +
                            "dim=${processed.croppedWidth}x${processed.croppedHeight}"
                    )
                    val missingWifi = BlePermissionHelper.getMissingWifiHotspotPermissions(context)
                    if (missingWifi.isNotEmpty()) {
                        wifiPermissionLauncher.launch(missingWifi.toTypedArray())
                    }
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

                    avatarErrorMessage?.let { errMsg ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x33FF5252))
                                .border(1.dp, Color(0x88FF5252), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = errMsg,
                                color = Color(0xFFFF8A80),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { avatarErrorMessage = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss error",
                                    tint = Color(0xFFFF8A80),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
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
                            LayoverFileLogger.log(
                                "ProfileScreen saving profile: compactBytes=${effectiveCompact?.size ?: 0}, " +
                                    "pendingFullResBytes=${savedFullResBytes?.size ?: 0}"
                            )
                            val updatedProfile = profile.copy(
                                displayName = profile.displayName.trim().take(7),
                                age = selectedAge,
                                avatarBytes = avatarBytes,
                                compactTransferAvatarBytes = effectiveCompact,
                                croppedResolutionLabel = resolutionLabel,
                                pendingFullResAvatarBytes = savedFullResBytes,
                                isVisibleToNearby = isVisible
                            )
                            onSaveProfile(updatedProfile)
                            savedFullResBytes = null
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
                                    displayName = profile.displayName.trim().take(7),
                                    age = selectedAge,
                                    avatarBytes = avatarBytes,
                                    compactTransferAvatarBytes = compactAvatarBytes ?: avatarBytes,
                                    croppedResolutionLabel = resolutionLabel,
                                    pendingFullResAvatarBytes = savedFullResBytes,
                                    isVisibleToNearby = checked
                                )
                                onSaveProfile(updatedProfile)
                                savedFullResBytes = null
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
    onConfirmCrop: (panOffsetPx: Offset, zoom: Float, rotationDegrees: Float, viewportSizePx: Float) -> Unit
) {
    val colors = LayoverTheme.colors
    val density = LocalDensity.current
    val viewportDp = 260.dp
    val viewportPx = with(density) { viewportDp.toPx() }

    val srcW = sourceBitmap.width.toFloat().coerceAtLeast(1f)
    val srcH = sourceBitmap.height.toFloat().coerceAtLeast(1f)

    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var rotationDegrees by remember { mutableFloatStateOf(0f) }
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
                    text = "Drag to pan • Pinch to zoom • Twist to rotate",
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
                            detectTransformGestures(panZoomLock = false) { _, pan, gestureZoom, gestureRotation ->
                                val nextZoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                                zoom = nextZoom
                                rotationDegrees = (rotationDegrees + gestureRotation) % 360f
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
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                rotationZ = rotationDegrees
                            }
                    ) {
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
                    }

                    Canvas(modifier = Modifier.fillMaxSize()) {
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
                        onClick = { onConfirmCrop(panOffset, zoom, rotationDegrees, viewportPx) },
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