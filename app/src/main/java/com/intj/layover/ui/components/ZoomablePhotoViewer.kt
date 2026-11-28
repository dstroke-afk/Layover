package com.intj.layover.ui.components

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.intj.layover.model.PeerDpDownloadState
import com.intj.layover.model.PhotoTransferState
import com.intj.layover.ui.theme.LayoverTheme
import java.io.File

const val PHOTO_VIEWER_MIN_SCALE = 1f
const val PHOTO_VIEWER_MAX_SCALE = 5f
const val PHOTO_VIEWER_DOUBLE_TAP_SCALE = 2.5f

/**
 * Pure decision helper determining whether opening the enlarged peer DP viewer should trigger
 * an on-demand BLE/Wi-Fi full-resolution DP download.
 *
 * Never triggers while the viewer is closed (e.g. merely scrolling People Around), never triggers
 * when a verified cropped full-res DP is already cached for [avatarDigest], never triggers for
 * blocked peers, and never duplicates an already in-flight download.
 */
internal fun shouldTriggerOnDemandPeerDpDownload(
    isViewerOpen: Boolean,
    hasCachedFullResDp: Boolean,
    isBlocked: Boolean,
    isBlockedByPeer: Boolean,
    avatarDigest: String?,
    currentDownloadState: PhotoTransferState?
): Boolean {
    if (!isViewerOpen) return false
    if (hasCachedFullResDp) return false
    if (isBlocked || isBlockedByPeer) return false
    if (avatarDigest.isNullOrBlank()) return false
    return when (currentDownloadState) {
        null,
        PhotoTransferState.AVAILABLE,
        PhotoTransferState.FAILED -> true
        PhotoTransferState.QUEUED,
        PhotoTransferState.CONNECTING_WIFI,
        PhotoTransferState.DOWNLOADING,
        PhotoTransferState.COMPLETED -> false
    }
}

internal fun clampZoomOffset(
    offset: Offset,
    scale: Float,
    containerSize: IntSize
): Offset {
    val safeScale = scale.coerceIn(PHOTO_VIEWER_MIN_SCALE, PHOTO_VIEWER_MAX_SCALE)
    if (safeScale <= PHOTO_VIEWER_MIN_SCALE || containerSize.width <= 0 || containerSize.height <= 0) {
        return Offset.Zero
    }
    val maxX = ((safeScale - 1f) * containerSize.width) / 2f
    val maxY = ((safeScale - 1f) * containerSize.height) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY)
    )
}

internal fun computeDoubleTapZoomState(
    currentScale: Float,
    tapOffset: Offset,
    containerSize: IntSize
): Pair<Float, Offset> {
    if (currentScale > PHOTO_VIEWER_MIN_SCALE + 0.05f) {
        return PHOTO_VIEWER_MIN_SCALE to Offset.Zero
    }
    val targetScale = PHOTO_VIEWER_DOUBLE_TAP_SCALE.coerceIn(PHOTO_VIEWER_MIN_SCALE, PHOTO_VIEWER_MAX_SCALE)
    if (containerSize.width <= 0 || containerSize.height <= 0) {
        return targetScale to Offset.Zero
    }
    val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
    val rawOffset = (center - tapOffset) * (targetScale - 1f)
    return targetScale to clampZoomOffset(rawOffset, targetScale, containerSize)
}

/**
 * Safely decodes a local image file with power-of-two downsampling capped to [maxDim]
 * so high-resolution photos render sharply in the zoomable viewer without OOM or GPU texture errors.
 */
fun decodeSampledImageBitmap(filePath: String, maxDim: Int = 2048): ImageBitmap? {
    return runCatching {
        val file = File(filePath)
        if (!file.exists() || file.length() <= 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        var sampleSize = 1
        while (w / sampleSize > maxDim || h / sampleSize > maxDim) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeFile(filePath, opts)?.asImageBitmap()
    }.getOrNull()
}

/**
 * Full-screen interactive photo lightbox with pinch-to-zoom (`1f..5f`), clamped panning,
 * and double-tap zoom/reset (`1f` <-> `2.5f`). State resets automatically when [imageKey]
 * changes or when the dialog is dismissed.
 */
@Composable
fun ZoomablePhotoViewerDialog(
    bitmap: ImageBitmap,
    contentDescription: String,
    imageKey: Any,
    title: String? = null,
    onDismiss: () -> Unit,
    bottomOverlay: (@Composable BoxScope.() -> Unit)? = null
) {
    var scale by remember(imageKey) { mutableFloatStateOf(PHOTO_VIEWER_MIN_SCALE) }
    var offset by remember(imageKey) { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val handleDismiss = {
        scale = PHOTO_VIEWER_MIN_SCALE
        offset = Offset.Zero
        onDismiss()
    }

    Dialog(
        onDismissRequest = handleDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler { handleDismiss() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF50D1117))
                .onSizeChanged { newSize ->
                    containerSize = newSize
                    if (scale > PHOTO_VIEWER_MIN_SCALE) {
                        offset = clampZoomOffset(offset, scale, newSize)
                    } else {
                        offset = Offset.Zero
                    }
                }
                .pointerInput(imageKey) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val updatedScale = (scale * zoom).coerceIn(PHOTO_VIEWER_MIN_SCALE, PHOTO_VIEWER_MAX_SCALE)
                        offset = if (updatedScale > PHOTO_VIEWER_MIN_SCALE) {
                            clampZoomOffset(offset + pan, updatedScale, containerSize)
                        } else {
                            Offset.Zero
                        }
                        scale = updatedScale
                    }
                }
                .pointerInput(imageKey) {
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            val (nextScale, nextOffset) = computeDoubleTapZoomState(
                                currentScale = scale,
                                tapOffset = tapOffset,
                                containerSize = containerSize
                            )
                            scale = nextScale
                            offset = nextOffset
                        }
                    )
                }
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .align(Alignment.Center)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )

            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }

            IconButton(
                onClick = handleDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.52f))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close photo",
                    tint = Color.White
                )
            }

            bottomOverlay?.invoke(this)
        }
    }
}

/**
 * Shared full-screen zoomable peer DP viewer used from both **People Around** and **Peer Profile**.
 *
 * - Displays [fullResBitmap] immediately if cached, or falls back to [thumbnailBitmap] while fetching.
 * - Triggers an on-demand sender-hosted `LocalOnlyHotspot` download ONLY when opened (never on list scroll)
 *   and ONLY when no verified cropped full-res DP is cached for [avatarDigest].
 * - Swaps seamlessly to [fullResBitmap] upon completion while preserving zoom/pan interactivity.
 */
@Composable
fun PeerFullResAvatarViewerDialog(
    peerId: String,
    title: String,
    thumbnailBitmap: ImageBitmap?,
    fullResBitmap: ImageBitmap?,
    avatarDigest: String?,
    dpDownloadState: PeerDpDownloadState?,
    isBlocked: Boolean,
    isBlockedByPeer: Boolean,
    onRequestFullResDp: (avatarDigest: String) -> Unit,
    onDismiss: () -> Unit
) {
    val displayBitmap = fullResBitmap ?: thumbnailBitmap ?: return
    val colors = LayoverTheme.colors

    LaunchedEffect(peerId, avatarDigest) {
        if (shouldTriggerOnDemandPeerDpDownload(
                isViewerOpen = true,
                hasCachedFullResDp = fullResBitmap != null,
                isBlocked = isBlocked,
                isBlockedByPeer = isBlockedByPeer,
                avatarDigest = avatarDigest,
                currentDownloadState = dpDownloadState?.state
            )
        ) {
            onRequestFullResDp(avatarDigest!!)
        }
    }

    ZoomablePhotoViewerDialog(
        bitmap = displayBitmap,
        contentDescription = "$title full-screen photo",
        imageKey = "${peerId}_${avatarDigest.orEmpty()}",
        title = title,
        onDismiss = onDismiss,
        bottomOverlay = {
            if (fullResBitmap == null && !isBlocked && !isBlockedByPeer && !avatarDigest.isNullOrBlank()) {
                val downloadState = dpDownloadState?.state
                when (downloadState) {
                    PhotoTransferState.QUEUED,
                    PhotoTransferState.CONNECTING_WIFI,
                    PhotoTransferState.DOWNLOADING -> {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = Color(0xCC161B22),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 28.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                val progress = dpDownloadState.progress
                                if (downloadState == PhotoTransferState.DOWNLOADING && progress > 0f) {
                                    CircularProgressIndicator(
                                        progress = { progress.coerceIn(0f, 1f) },
                                        color = colors.cyanPrimary,
                                        strokeWidth = 2.5.dp,
                                        modifier = Modifier.size(16.dp)
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        color = colors.cyanPrimary,
                                        strokeWidth = 2.5.dp,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = dpDownloadState.statusDetail ?: "Loading full resolution...",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    PhotoTransferState.FAILED -> {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = Color(0xDD161B22),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 28.dp)
                                .border(1.dp, colors.cyanPrimary.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                                .clip(RoundedCornerShape(24.dp))
                                .clickable {
                                    onRequestFullResDp(avatarDigest)
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Retry full-res download",
                                    tint = colors.cyanPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${dpDownloadState.statusDetail ?: "Full-res unavailable"} • Retry",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }
    )
}

