package com.intj.layover.wifi

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.intj.layover.ble.GattRoutePreference
import com.intj.layover.ble.PeerAliasRegistry
import com.intj.layover.crypto.EncryptedPayload
import com.intj.layover.diagnostics.LayoverFileLogger
import com.intj.layover.model.ChatMessage
import com.intj.layover.model.PeerDpDownloadState
import com.intj.layover.model.PhotoAttachment
import com.intj.layover.model.PhotoTransferState
import com.intj.layover.p2p.WireFrame
import com.intj.layover.p2p.WireFrameCodec
import com.intj.layover.ui.screens.formatMessageBubbleTimestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/**
 * Direct Sender-as-AP LocalOnlyHotspot photo transfer manager (with optional 1-on-1
 * Receiver-as-AP fallback if the sender's hotspot fails or times out).
 *
 * Flow:
 * 1. Sender picks a photo -> enforces 3 MB cap -> caches original full-res bytes locally
 *    keyed by `transferId` -> generates a ~120px (8–15 KB) JPEG thumbnail -> sends an
 *    encrypted `PHOTO_PREVIEW` over BLE while Wi-Fi remains completely idle.
 * 2. Recipient sees a blurred preview bubble with a **Download** button. Tapping Download
 *    queues/serializes the Wi-Fi session (one active `WifiNetworkSpecifier` at a time)
 *    and sends `PHOTO_DOWNLOAD_REQUEST(transferId)` over BLE to the sender.
 * 3. Sender starts `LocalOnlyHotspot` + temporary `DirectPhotoSocketServer` and replies with
 *    `WIFI_AP_READY(transferId, ssid, passphrase, port)`.
 * 4. Receiver binds to the hotspot via `WifiNetworkSpecifier`, streams encrypted chunks with
 *    progress updates, verifies `SHA-256`, saves the full image, updates the chat bubble,
 *    and immediately tears down all sockets, `NetworkCallback`, and `LocalOnlyHotspotReservation`
 *    while keeping BLE chat uninterrupted.
 */
class WifiPhotoTransferManager(
    private val context: Context,
    private val localPeerIdProvider: () -> String,
    private val sendBleFrame: (targetPeerId: String, rawWireFrame: String) -> Boolean,
    private val sendBleFrameWithRoute: (
        targetPeerId: String,
        rawWireFrame: String,
        routePreference: GattRoutePreference
    ) -> Boolean = { targetPeerId, rawWireFrame, _ -> sendBleFrame(targetPeerId, rawWireFrame) },
    private val ensureEncryptedChannelReady: suspend (peerId: String) -> Boolean = { true },
    private val refreshPeerChannelForRetry: suspend (peerId: String) -> Boolean = { peerId ->
        ensureEncryptedChannelReady(peerId)
    },
    private val encryptBytesForPeer: (peerId: String, plaintextBytes: ByteArray) -> EncryptedPayload?,
    private val decryptBytesFromPeer: (peerId: String, payload: EncryptedPayload) -> ByteArray?,
    private val photoStorageDir: File = File(context.filesDir, "layover_photos"),
    private val onOutgoingPhotoCreated: (peerId: String, message: ChatMessage) -> Unit,
    private val onIncomingPhotoPreview: (peerId: String, message: ChatMessage) -> Unit,
    private val onPhotoStateUpdated: (
        transferId: String,
        state: PhotoTransferState,
        progress: Float,
        localFilePath: String?,
        statusDetail: String?
    ) -> Unit,
    private val onPhotoValidationError: (peerId: String, message: String) -> Unit,
    private val onClearPhotoError: (peerId: String) -> Unit,
    private val peerDpCacheStore: PeerDpCacheStore = PeerDpCacheStore(context),
    private val ownFullResAvatarProvider: () -> OwnFullResAvatarPayload? = { null },
    private val onPeerDpStateUpdated: (
        peerId: String,
        avatarDigest: String,
        state: PeerDpDownloadState
    ) -> Unit = { _, _, _ -> },
    private val downloadRequestRetryIntervalMs: Long = PhotoTransferConfig.DOWNLOAD_REQUEST_RETRY_INTERVAL_MS,
    private val postFinalRetryWaitMs: Long = PhotoTransferConfig.POST_FINAL_RETRY_WAIT_MS,
    private val maxDownloadRequestAttempts: Int = PhotoTransferConfig.MAX_DOWNLOAD_REQUEST_ATTEMPTS
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hotspotController = LocalOnlyHotspotController(context)
    val downloadQueueStateMachine = PhotoDownloadQueueStateMachine()

    private class OutgoingPhotoSession(
        val transferId: String,
        val recipientPeerId: String,
        val fileSizeBytes: Long,
        val mimeType: String,
        val sha256Hex: String,
        val transferKey: SecretKey,
        val originalFilePath: String
    )

    private class IncomingPhotoSession(
        val transferId: String,
        val senderPeerId: String,
        val fileSizeBytes: Long,
        val sha256Hex: String,
        val transferKey: SecretKey
    )

    private data class PendingDpDownloadMeta(
        val transferId: String,
        val peerId: String,
        val peerAliases: Set<String>,
        val avatarDigest: String
    )

    private val outgoingPhotos = ConcurrentHashMap<String, OutgoingPhotoSession>()
    private val incomingPhotos = ConcurrentHashMap<String, IncomingPhotoSession>()
    private val pendingDpMetaByTransferId = ConcurrentHashMap<String, PendingDpDownloadMeta>()
    private val pendingEarlyWifiApReadyByTransferId = ConcurrentHashMap<String, Pair<String, WireFrame.WifiApReady>>()
    private val activeTransferJobs = ConcurrentHashMap<String, Job>()
    private val activeApTimeoutJobs = ConcurrentHashMap<String, Job>()
    private val activeServers = ConcurrentHashMap<String, DirectPhotoSocketServer>()
    private val activeHotspotSessions = ConcurrentHashMap<String, StartedHotspotSession>()
    private val activeClientConnections = ConcurrentHashMap<String, HotspotClientConnection>()
    private val terminalFailureNotified = ConcurrentHashMap.newKeySet<String>()
    private val senderRespondedTransfers = ConcurrentHashMap.newKeySet<String>()

    private fun isDpTransferId(transferId: String): Boolean = transferId.startsWith("dp_")

    private fun extractDigestFromDpTransferId(transferId: String): String? {
        if (!isDpTransferId(transferId)) return null
        val remainder = transferId.substring(3)
        val lastUnderscore = remainder.lastIndexOf('_')
        val candidate = if (lastUnderscore > 0) remainder.substring(0, lastUnderscore) else remainder
        return candidate.takeIf { it.isNotBlank() }
    }

    private fun emitTransferStateUpdate(
        transferId: String,
        state: PhotoTransferState,
        progress: Float,
        localFilePath: String?,
        statusDetail: String?
    ) {
        val dpMeta = pendingDpMetaByTransferId[transferId]
        if (dpMeta != null) {
            onPeerDpStateUpdated(
                dpMeta.peerId,
                dpMeta.avatarDigest,
                PeerDpDownloadState(
                    peerId = dpMeta.peerId,
                    avatarDigest = dpMeta.avatarDigest,
                    transferId = transferId,
                    state = state,
                    progress = progress.coerceIn(0f, 1f),
                    localFilePath = localFilePath,
                    statusDetail = statusDetail
                )
            )
        } else {
            onPhotoStateUpdated(transferId, state, progress, localFilePath, statusDetail)
        }
    }

    init {
        runCatching { photoStorageDir.mkdirs() }
    }

    /**
     * Validates a picked photo against the 3 MB limit, caches the original full-resolution bytes
     * locally keyed by `transferId` (without lossy recompression), creates a ~120px (8–15 KB)
     * preview thumbnail, automatically establishes GATT & E2EE readiness if needed, and sends
     * the encrypted `PHOTO_PREVIEW` over BLE.
     */
    fun sendPhotoFromUri(peerId: String, uri: Uri) {
        scope.launch {
            LayoverFileLogger.log("Photo pick received for peerId=$peerId, uri=$uri")
            when (val loadResult = PhotoSizeValidator.loadAndValidatePhotoUri(context, uri)) {
                is PhotoLoadResult.Rejected -> {
                    LayoverFileLogger.log(
                        "Photo blocked before preview/transfer: peerId=$peerId, " +
                            "sizeBytes=${loadResult.sizeBytes}, reason=${loadResult.message}"
                    )
                    onPhotoValidationError(peerId, loadResult.message)
                    return@launch
                }

                is PhotoLoadResult.Success -> {
                    onClearPhotoError(peerId)
                    val payload = loadResult.payload
                    val previewBytes = PhotoPreviewGenerator.generateCompactPreview(payload.bytes)
                    if (previewBytes == null || previewBytes.isEmpty()) {
                        onPhotoValidationError(peerId, "Unable to process selected photo")
                        return@launch
                    }

                    val transferId = "ph_" + UUID.randomUUID().toString().replace("-", "").take(10)
                    val sha256Hex = EncryptedPhotoStreamProtocol.sha256Hex(payload.bytes)
                    val transferKey = EncryptedPhotoStreamProtocol.generateTransferKey()

                    // Cache original full-resolution photo bytes locally without lossy recompression
                    val cachedOriginalFile = savePhotoBytes(transferId, payload.bytes)
                    if (cachedOriginalFile == null) {
                        onPhotoValidationError(peerId, "Unable to cache photo locally")
                        return@launch
                    }

                    val session = OutgoingPhotoSession(
                        transferId = transferId,
                        recipientPeerId = peerId,
                        fileSizeBytes = payload.bytes.size.toLong(),
                        mimeType = payload.mimeType,
                        sha256Hex = sha256Hex,
                        transferKey = transferKey,
                        originalFilePath = cachedOriginalFile.absolutePath
                    )
                    outgoingPhotos[transferId] = session

                    val now = System.currentTimeMillis()
                    val pendingMsg = ChatMessage(
                        id = "photo_$transferId",
                        senderId = "user",
                        text = "",
                        timestampLabel = formatMessageBubbleTimestamp(now),
                        isOutgoing = true,
                        timestampMillis = now,
                        photoAttachment = PhotoAttachment(
                            transferId = transferId,
                            fileSizeBytes = session.fileSizeBytes,
                            mimeType = session.mimeType,
                            previewBytes = previewBytes,
                            localFilePath = cachedOriginalFile.absolutePath,
                            state = PhotoTransferState.CONNECTING_WIFI,
                            progress = 0f,
                            statusDetail = "Securing connection..."
                        )
                    )
                    onOutgoingPhotoCreated(peerId, pendingMsg)

                    val channelReady = ensureEncryptedChannelReady(peerId)
                    if (!channelReady) {
                        LayoverFileLogger.logError(
                            "Photo send aborted: GATT/E2EE handshake did not complete for peerId=$peerId, transferId=$transferId"
                        )
                        onPhotoValidationError(peerId, "Unable to establish encrypted connection — tap photo to retry")
                        onPhotoStateUpdated(
                            transferId,
                            PhotoTransferState.FAILED,
                            0f,
                            cachedOriginalFile.absolutePath,
                            "Send failed"
                        )
                        return@launch
                    }

                    val previewBundle = EncryptedPhotoStreamProtocol.encodePreviewBundle(
                        fileSizeBytes = payload.bytes.size.toLong(),
                        mimeType = payload.mimeType,
                        sha256Hex = sha256Hex,
                        transferKey = transferKey,
                        previewBytes = previewBytes
                    )
                    val encryptedBundle = encryptBytesForPeer(peerId, previewBundle)
                    if (encryptedBundle == null) {
                        LayoverFileLogger.logError(
                            "Photo send failed: encryptBytesForPeer returned null after E2EE wait for peerId=$peerId, transferId=$transferId"
                        )
                        onPhotoValidationError(peerId, "Encrypted session unavailable — tap photo to retry")
                        onPhotoStateUpdated(
                            transferId,
                            PhotoTransferState.FAILED,
                            0f,
                            cachedOriginalFile.absolutePath,
                            "Send failed"
                        )
                        return@launch
                    }

                    val localId = localPeerIdProvider().ifBlank { "user" }
                    val previewFrame = WireFrameCodec.encodePhotoPreview(
                        transferId = transferId,
                        senderId = localId,
                        encryptedPreviewBundle = encryptedBundle
                    )
                    val sent = sendBleFrame(peerId, previewFrame)
                    if (sent) {
                        LayoverFileLogger.log(
                            "Sent encrypted PHOTO_PREVIEW over BLE: transferId=$transferId, peerId=$peerId, " +
                                "origBytes=${session.fileSizeBytes}, previewBytes=${previewBytes.size}, " +
                                "sha256=${sha256Hex.take(12)}, sent=true"
                        )
                        onClearPhotoError(peerId)
                        onPhotoStateUpdated(
                            transferId,
                            PhotoTransferState.COMPLETED,
                            1f,
                            cachedOriginalFile.absolutePath,
                            null
                        )
                    } else {
                        LayoverFileLogger.logError(
                            "Failed to send encrypted PHOTO_PREVIEW over BLE: transferId=$transferId, " +
                                "peerId=$peerId, origBytes=${session.fileSizeBytes}, previewBytes=${previewBytes.size}, sent=false"
                        )
                        onPhotoValidationError(peerId, "Failed to send photo — tap photo to retry")
                        onPhotoStateUpdated(
                            transferId,
                            PhotoTransferState.FAILED,
                            0f,
                            cachedOriginalFile.absolutePath,
                            "Send failed"
                        )
                    }
                }
            }
        }
    }

    /**
     * Handles an incoming encrypted `PHOTO_PREVIEW` BLE frame. Wi-Fi remains completely idle
     * until the user explicitly taps **Download** on the blurred bubble.
     */
    fun handleIncomingPhotoPreview(senderPeerId: String, frame: WireFrame.PhotoPreview) {
        val iv = runCatching { Base64.decode(frame.ivBase64, Base64.NO_WRAP) }.getOrNull()
        val cipher = runCatching { Base64.decode(frame.ciphertextBase64, Base64.NO_WRAP) }.getOrNull()
        if (iv == null || cipher == null) {
            LayoverFileLogger.logError("Invalid Base64 in PHOTO_PREVIEW ${frame.transferId}")
            return
        }

        val decryptedBundleBytes = decryptBytesFromPeer(senderPeerId, EncryptedPayload(iv = iv, ciphertext = cipher))
        val bundle = decryptedBundleBytes?.let { EncryptedPhotoStreamProtocol.decodePreviewBundle(it) }
        if (bundle == null) {
            LayoverFileLogger.logError("Failed to decrypt/decode PHOTO_PREVIEW ${frame.transferId} from $senderPeerId")
            return
        }

        val sizeCheck = PhotoSizeValidator.validateSize(bundle.fileSizeBytes)
        if (sizeCheck !is PhotoSizeValidationResult.Valid) {
            LayoverFileLogger.log("Rejected PHOTO_PREVIEW ${frame.transferId}: invalid size=${bundle.fileSizeBytes}")
            return
        }

        incomingPhotos[frame.transferId] = IncomingPhotoSession(
            transferId = frame.transferId,
            senderPeerId = senderPeerId,
            fileSizeBytes = bundle.fileSizeBytes,
            sha256Hex = bundle.sha256Hex,
            transferKey = bundle.transferKey
        )

        if (isDpTransferId(frame.transferId)) {
            senderRespondedTransfers.add(frame.transferId)
            val existingMeta = pendingDpMetaByTransferId[frame.transferId]
            if (existingMeta != null) {
                if (senderPeerId.isNotBlank()) {
                    PeerAliasRegistry.linkAliases(listOf(existingMeta.peerId, senderPeerId))
                    val mergedAliases = (
                        existingMeta.peerAliases +
                            senderPeerId +
                            PeerAliasRegistry.resolveStableIdentifiers(listOf(existingMeta.peerId, senderPeerId))
                        ).filter { it.isNotBlank() }.toSet()
                    pendingDpMetaByTransferId[frame.transferId] = existingMeta.copy(peerAliases = mergedAliases)
                }
            } else if (senderPeerId.isNotBlank()) {
                val parsedDigest = extractDigestFromDpTransferId(frame.transferId)
                    ?: bundle.sha256Hex.take(16)
                val stableAliases = (
                    PeerAliasRegistry.resolveStableIdentifiers(senderPeerId) + senderPeerId
                    ).filter { it.isNotBlank() }.toSet()
                pendingDpMetaByTransferId[frame.transferId] = PendingDpDownloadMeta(
                    transferId = frame.transferId,
                    peerId = senderPeerId,
                    peerAliases = stableAliases,
                    avatarDigest = parsedDigest
                )
                downloadQueueStateMachine.registerAvailable(frame.transferId, senderPeerId)
                emitTransferStateUpdate(
                    transferId = frame.transferId,
                    state = PhotoTransferState.AVAILABLE,
                    progress = 0f,
                    localFilePath = null,
                    statusDetail = null
                )
            }
            LayoverFileLogger.log(
                "Received encrypted DP metadata PHOTO_PREVIEW from $senderPeerId: " +
                    "transferId=${frame.transferId}, size=${bundle.fileSizeBytes}, sha256=${bundle.sha256Hex.take(12)}"
            )
            val earlyReady = pendingEarlyWifiApReadyByTransferId.remove(frame.transferId)
            if (earlyReady != null) {
                LayoverFileLogger.log("Processing stashed early WIFI_AP_READY for DP transfer ${frame.transferId}")
                handleIncomingWifiApReady(earlyReady.first, earlyReady.second)
            }
            return
        }

        downloadQueueStateMachine.registerAvailable(frame.transferId, senderPeerId)

        val now = System.currentTimeMillis()
        val msg = ChatMessage(
            id = "photo_${frame.transferId}",
            senderId = senderPeerId,
            text = "",
            timestampLabel = formatMessageBubbleTimestamp(now),
            isOutgoing = false,
            timestampMillis = now,
            photoAttachment = PhotoAttachment(
                transferId = frame.transferId,
                fileSizeBytes = bundle.fileSizeBytes,
                mimeType = bundle.mimeType,
                previewBytes = bundle.previewBytes,
                state = PhotoTransferState.AVAILABLE,
                progress = 0f
            )
        )
        LayoverFileLogger.log(
            "Received encrypted PHOTO_PREVIEW from $senderPeerId: transferId=${frame.transferId}, " +
                "size=${bundle.fileSizeBytes}, previewBytes=${bundle.previewBytes.size}"
        )
        onIncomingPhotoPreview(senderPeerId, msg)
    }

    /**
     * Viewer tapped a peer's circular avatar to open the full-screen DP lightbox.
     * - Checks the local digest-keyed cache first (instant open with zero Wi-Fi).
     * - Deduplicates in-flight/queued requests for the same peer + digest.
     * - Serializes with active chat photo downloads via [downloadQueueStateMachine] so only
     *   one `WifiNetworkSpecifier` session runs at a time.
     */
    fun requestPeerFullResDp(peerId: String, peerAliases: Set<String>, avatarDigest: String) {
        if (peerId.isBlank() || avatarDigest.isBlank()) return
        val normalizedAliases = (peerAliases + peerId).filter { it.isNotBlank() }.toSet()

        val cachedFile = peerDpCacheStore.getCachedPeerDpFile(normalizedAliases, avatarDigest)
        if (cachedFile != null) {
            LayoverFileLogger.log(
                "Peer full-res DP cache hit for peerId=$peerId, digest=$avatarDigest -> ${cachedFile.absolutePath}"
            )
            onPeerDpStateUpdated(
                peerId,
                avatarDigest,
                PeerDpDownloadState(
                    peerId = peerId,
                    avatarDigest = avatarDigest,
                    transferId = "dp_cached_$avatarDigest",
                    state = PhotoTransferState.COMPLETED,
                    progress = 1f,
                    localFilePath = cachedFile.absolutePath,
                    statusDetail = null
                )
            )
            return
        }

        val existingInFlight = pendingDpMetaByTransferId.values.firstOrNull { meta ->
            meta.avatarDigest == avatarDigest &&
                meta.peerAliases.any { existingAlias ->
                    normalizedAliases.any { candidate -> existingAlias.equals(candidate, ignoreCase = true) }
                } &&
                downloadQueueStateMachine.getSnapshot(meta.transferId)?.state in setOf(
                    PhotoTransferState.QUEUED,
                    PhotoTransferState.CONNECTING_WIFI,
                    PhotoTransferState.DOWNLOADING
                )
        }
        if (existingInFlight != null) {
            LayoverFileLogger.log(
                "Peer full-res DP download already in flight for peerId=$peerId, digest=$avatarDigest (transferId=${existingInFlight.transferId})"
            )
            return
        }

        val cleanDigest = PeerDpCacheStore.sanitizeKeySegment(avatarDigest)
        val nonce = UUID.randomUUID().toString().replace("-", "").take(6)
        val transferId = "dp_${cleanDigest}_$nonce"
        pendingDpMetaByTransferId[transferId] = PendingDpDownloadMeta(
            transferId = transferId,
            peerId = peerId,
            peerAliases = normalizedAliases,
            avatarDigest = avatarDigest
        )
        terminalFailureNotified.remove(transferId)
        pendingEarlyWifiApReadyByTransferId.remove(transferId)
        downloadQueueStateMachine.registerAvailable(transferId, peerId)

        LayoverFileLogger.log(
            "Requesting on-demand full-res DP over BLE/Wi-Fi: transferId=$transferId, peerId=$peerId, digest=$avatarDigest"
        )
        when (val outcome = downloadQueueStateMachine.requestDownload(transferId)) {
            is PhotoDownloadQueueStateMachine.DownloadRequestOutcome.StartNow -> {
                startReceiverDownloadRequest(outcome.transferId, outcome.senderPeerId)
            }
            is PhotoDownloadQueueStateMachine.DownloadRequestOutcome.Queued -> {
                LayoverFileLogger.log(
                    "Queued DP download $transferId (position=${outcome.queuePosition}) while " +
                        "${downloadQueueStateMachine.activeTransferId()} is active."
                )
                emitTransferStateUpdate(
                    transferId,
                    PhotoTransferState.QUEUED,
                    0f,
                    null,
                    outcome.statusDetail
                )
            }
            PhotoDownloadQueueStateMachine.DownloadRequestOutcome.Ignored -> Unit
        }
    }

    /**
     * Recipient tapped **Download** (or Retry) on a photo preview bubble.
     * Serializes Wi-Fi sessions so only one `WifiNetworkSpecifier` runs at a time;
     * additional tapped photos are queued automatically with `"Waiting for current download..."`.
     */
    fun requestDownload(peerId: String, transferId: String) {
        val outgoing = outgoingPhotos[transferId]
        if (outgoing != null && !incomingPhotos.containsKey(transferId)) {
            retryOutgoingPhotoSend(peerId.ifBlank { outgoing.recipientPeerId }, outgoing)
            return
        }

        val incoming = incomingPhotos[transferId]
        if (incoming == null) {
            LayoverFileLogger.log("requestDownload ignored: no preview metadata for $transferId")
            return
        }
        val candidates = listOf(peerId, incoming.senderPeerId).filter { it.isNotBlank() }
        if (candidates.size > 1) {
            PeerAliasRegistry.linkAliases(candidates)
        }
        val canonicalSenderPeerId = candidates.firstOrNull { it.startsWith("dev_", ignoreCase = true) }
            ?: incoming.senderPeerId.ifBlank { peerId }
        terminalFailureNotified.remove(transferId)
        senderRespondedTransfers.remove(transferId)
        downloadQueueStateMachine.registerAvailable(transferId, canonicalSenderPeerId)

        when (val outcome = downloadQueueStateMachine.requestDownload(transferId)) {
            is PhotoDownloadQueueStateMachine.DownloadRequestOutcome.StartNow -> {
                startReceiverDownloadRequest(outcome.transferId, outcome.senderPeerId)
            }
            is PhotoDownloadQueueStateMachine.DownloadRequestOutcome.Queued -> {
                LayoverFileLogger.log(
                    "Queued photo download $transferId (position=${outcome.queuePosition}) while " +
                        "${downloadQueueStateMachine.activeTransferId()} is active."
                )
                onPhotoStateUpdated(
                    transferId,
                    PhotoTransferState.QUEUED,
                    0f,
                    null,
                    outcome.statusDetail
                )
            }
            PhotoDownloadQueueStateMachine.DownloadRequestOutcome.Ignored -> Unit
        }
    }

    private fun retryOutgoingPhotoSend(peerId: String, outgoing: OutgoingPhotoSession) {
        scope.launch {
            val file = File(outgoing.originalFilePath)
            if (!file.exists()) {
                LayoverFileLogger.logError("Cannot retry photo send: cached file missing for ${outgoing.transferId}")
                onPhotoValidationError(peerId, "Original photo file unavailable")
                return@launch
            }
            val originalBytes = runCatching { file.readBytes() }.getOrNull()
            val previewBytes = originalBytes?.let { PhotoPreviewGenerator.generateCompactPreview(it) }
            if (originalBytes == null || previewBytes == null || previewBytes.isEmpty()) {
                LayoverFileLogger.logError("Cannot retry photo send: failed regenerating preview for ${outgoing.transferId}")
                onPhotoValidationError(peerId, "Unable to process photo for retry")
                return@launch
            }

            onClearPhotoError(peerId)
            onPhotoStateUpdated(
                outgoing.transferId,
                PhotoTransferState.CONNECTING_WIFI,
                0f,
                outgoing.originalFilePath,
                "Securing connection..."
            )

            val channelReady = ensureEncryptedChannelReady(peerId)
            if (!channelReady) {
                LayoverFileLogger.logError("Retry photo send failed: GATT/E2EE handshake timed out for $peerId")
                onPhotoValidationError(peerId, "Unable to establish encrypted connection — tap photo to retry")
                onPhotoStateUpdated(outgoing.transferId, PhotoTransferState.FAILED, 0f, outgoing.originalFilePath, "Send failed")
                return@launch
            }

            val bundle = EncryptedPhotoStreamProtocol.encodePreviewBundle(
                fileSizeBytes = outgoing.fileSizeBytes,
                mimeType = outgoing.mimeType,
                sha256Hex = outgoing.sha256Hex,
                transferKey = outgoing.transferKey,
                previewBytes = previewBytes
            )
            val encryptedBundle = encryptBytesForPeer(peerId, bundle)
            if (encryptedBundle == null) {
                LayoverFileLogger.logError("Retry photo send failed: E2EE session unavailable for $peerId")
                onPhotoValidationError(peerId, "Encrypted session unavailable — tap photo to retry")
                onPhotoStateUpdated(outgoing.transferId, PhotoTransferState.FAILED, 0f, outgoing.originalFilePath, "Send failed")
                return@launch
            }

            val localId = localPeerIdProvider().ifBlank { "user" }
            val previewFrame = WireFrameCodec.encodePhotoPreview(
                transferId = outgoing.transferId,
                senderId = localId,
                encryptedPreviewBundle = encryptedBundle
            )
            val sent = sendBleFrame(peerId, previewFrame)
            if (sent) {
                LayoverFileLogger.log("Retried encrypted PHOTO_PREVIEW succeeded: transferId=${outgoing.transferId}, peerId=$peerId")
                onClearPhotoError(peerId)
                onPhotoStateUpdated(outgoing.transferId, PhotoTransferState.COMPLETED, 1f, outgoing.originalFilePath, null)
            } else {
                LayoverFileLogger.logError("Retried encrypted PHOTO_PREVIEW failed: transferId=${outgoing.transferId}, peerId=$peerId, sent=false")
                onPhotoValidationError(peerId, "Failed to send photo — tap photo to retry")
                onPhotoStateUpdated(outgoing.transferId, PhotoTransferState.FAILED, 0f, outgoing.originalFilePath, "Send failed")
            }
        }
    }

    /**
     * Sender receives `PHOTO_DOWNLOAD_REQUEST(transferId)` -> starts a temporary
     * `LocalOnlyHotspot` + `DirectPhotoSocketServer`, then sends `WIFI_AP_READY` over BLE.
     * If a retried `PHOTO_DOWNLOAD_REQUEST` arrives while our hotspot & server are already active,
     * immediately re-emits `WIFI_AP_READY` across available GATT routes so a dropped BLE frame
     * recovers without restarting the hotspot.
     */
    fun handleIncomingPhotoDownloadRequest(requesterPeerId: String, frame: WireFrame.PhotoDownloadRequest) {
        if (isDpTransferId(frame.transferId)) {
            handleIncomingDpDownloadRequest(requesterPeerId, frame)
            return
        }

        val outgoing = outgoingPhotos[frame.transferId]
        if (outgoing == null) {
            LayoverFileLogger.log(
                "Received PHOTO_DOWNLOAD_REQUEST for unknown transferId=${frame.transferId} from $requesterPeerId"
            )
            return
        }
        val transferId = outgoing.transferId
        val targetPeerId = requesterPeerId.ifBlank { outgoing.recipientPeerId }
        if (activeTransferJobs[transferId]?.isActive == true) {
            reemitActiveWifiApReadyIfRunning(transferId, targetPeerId)
            return
        }

        val originalFile = File(outgoing.originalFilePath)
        if (!originalFile.exists()) {
            LayoverFileLogger.logError("Cached original photo missing for transferId=${frame.transferId}")
            val localId = localPeerIdProvider().ifBlank { "user" }
            sendBleFrame(
                requesterPeerId,
                WireFrameCodec.encodePhotoTransferDone(frame.transferId, localId, success = false)
            )
            return
        }

        launchSenderHostForSession(targetPeerId = targetPeerId, outgoing = outgoing)
    }

    private fun reemitActiveWifiApReadyIfRunning(transferId: String, targetPeerId: String) {
        val hotspot = activeHotspotSessions[transferId]
        val server = activeServers[transferId]
        if (hotspot != null && server != null && !server.isClosed) {
            val localId = localPeerIdProvider().ifBlank { "user" }
            val readyFrame = WireFrameCodec.encodeWifiApReady(
                transferId = transferId,
                senderId = localId,
                ssid = hotspot.ssid,
                passphrase = hotspot.passphrase,
                port = server.localPort,
                hostIp = hotspot.hostIp.orEmpty(),
                isReceiverFallbackHost = false
            )
            LayoverFileLogger.log(
                "Received retried PHOTO_DOWNLOAD_REQUEST while hotspot is already live for $transferId; " +
                    "re-sending WIFI_AP_READY to $targetPeerId (ssid=${hotspot.ssid}, port=${server.localPort})."
            )
            sendBleFrameWithRoute(targetPeerId, readyFrame, GattRoutePreference.BOTH_IF_AVAILABLE)
        } else {
            LayoverFileLogger.log(
                "Ignoring duplicate PHOTO_DOWNLOAD_REQUEST for already-starting transferId=$transferId from $targetPeerId"
            )
        }
    }

    /**
     * Handles an on-demand full-resolution profile picture download request (`dp_*` transfer ID).
     * Loads our stored full-res avatar (falling back gracefully to compact avatar bytes if legacy),
     * sends an encrypted metadata-only `PHOTO_PREVIEW` (with size, SHA-256, and fresh AES-256 key),
     * then hosts a temporary `LocalOnlyHotspot` + `DirectPhotoSocketServer` to stream the image.
     */
    private fun handleIncomingDpDownloadRequest(
        requesterPeerId: String,
        frame: WireFrame.PhotoDownloadRequest
    ) {
        val transferId = frame.transferId
        if (activeTransferJobs[transferId]?.isActive == true) {
            reemitActiveWifiApReadyIfRunning(transferId, requesterPeerId)
            return
        }

        terminalFailureNotified.remove(transferId)
        teardownTransferResources(transferId, notifyPeer = false)

        val job = scope.launch {
            val thisJob = coroutineContext[Job]
            try {
                val ownDp = ownFullResAvatarProvider()
                if (ownDp == null || ownDp.bytes.isEmpty() ||
                    PhotoSizeValidator.validateSize(ownDp.bytes.size.toLong()) !is PhotoSizeValidationResult.Valid
                ) {
                    LayoverFileLogger.log(
                        "Cannot serve full-res DP for $transferId to $requesterPeerId: local avatar unavailable or exceeds 3 MB"
                    )
                    notifyPeerFailureOnce(transferId, requesterPeerId)
                    return@launch
                }

                val channelReady = ensureEncryptedChannelReady(requesterPeerId)
                if (!channelReady) {
                    LayoverFileLogger.logError(
                        "Cannot serve full-res DP for $transferId: GATT/E2EE not ready for $requesterPeerId"
                    )
                    notifyPeerFailureOnce(transferId, requesterPeerId)
                    return@launch
                }

                val transferKey = EncryptedPhotoStreamProtocol.generateTransferKey()
                val servedFile = savePhotoBytes("dp_serve_latest", ownDp.bytes)
                if (servedFile == null) {
                    LayoverFileLogger.logError("Failed to stage local full-res DP for $transferId")
                    notifyPeerFailureOnce(transferId, requesterPeerId)
                    return@launch
                }

                val session = OutgoingPhotoSession(
                    transferId = transferId,
                    recipientPeerId = requesterPeerId,
                    fileSizeBytes = ownDp.bytes.size.toLong(),
                    mimeType = ownDp.mimeType,
                    sha256Hex = ownDp.sha256Hex,
                    transferKey = transferKey,
                    originalFilePath = servedFile.absolutePath
                )
                outgoingPhotos[transferId] = session

                val metadataBundle = EncryptedPhotoStreamProtocol.encodePreviewBundle(
                    fileSizeBytes = ownDp.bytes.size.toLong(),
                    mimeType = ownDp.mimeType,
                    sha256Hex = ownDp.sha256Hex,
                    transferKey = transferKey,
                    previewBytes = ByteArray(0)
                )
                val encryptedMetadata = encryptBytesForPeer(requesterPeerId, metadataBundle)
                if (encryptedMetadata == null) {
                    LayoverFileLogger.logError("Failed to encrypt DP metadata PHOTO_PREVIEW for $transferId -> $requesterPeerId")
                    notifyPeerFailureOnce(transferId, requesterPeerId)
                    return@launch
                }

                val localId = localPeerIdProvider().ifBlank { "user" }
                val metaFrame = WireFrameCodec.encodePhotoPreview(
                    transferId = transferId,
                    senderId = localId,
                    encryptedPreviewBundle = encryptedMetadata
                )
                val metaSent = sendBleFrame(requesterPeerId, metaFrame)
                if (!metaSent) {
                    LayoverFileLogger.logError("Failed to send DP metadata PHOTO_PREVIEW over BLE for $transferId -> $requesterPeerId")
                    notifyPeerFailureOnce(transferId, requesterPeerId)
                    return@launch
                }
                LayoverFileLogger.log(
                    "Sent encrypted DP metadata PHOTO_PREVIEW for $transferId -> $requesterPeerId " +
                        "(bytes=${ownDp.bytes.size}, sha256=${ownDp.sha256Hex.take(12)}); starting Sender-as-AP."
                )

                runSenderHostOnCurrentCoroutine(
                    targetPeerId = requesterPeerId,
                    outgoing = session,
                    preloadedBytes = ownDp.bytes
                )
            } finally {
                if (thisJob != null) activeTransferJobs.remove(transferId, thisJob)
            }
        }
        activeTransferJobs[transferId] = job
    }

    private fun launchSenderHostForSession(
        targetPeerId: String,
        outgoing: OutgoingPhotoSession
    ) {
        val transferId = outgoing.transferId
        terminalFailureNotified.remove(transferId)
        teardownTransferResources(transferId, notifyPeer = false)

        val job = scope.launch {
            runSenderHostOnCurrentCoroutine(
                targetPeerId = targetPeerId,
                outgoing = outgoing,
                preloadedBytes = null
            )
        }
        activeTransferJobs[transferId] = job
    }

    private suspend fun runSenderHostOnCurrentCoroutine(
        targetPeerId: String,
        outgoing: OutgoingPhotoSession,
        preloadedBytes: ByteArray?
    ) {
        val transferId = outgoing.transferId
        var hotspotSession: StartedHotspotSession? = null
        var server: DirectPhotoSocketServer? = null
        try {
            hotspotSession = hotspotController.startHotspot(PhotoTransferConfig.AP_START_TIMEOUT_MS)
            activeHotspotSessions[transferId] = hotspotSession

            server = DirectPhotoSocketServer()
            activeServers[transferId] = server

            val localId = localPeerIdProvider().ifBlank { "user" }
            val readyFrame = WireFrameCodec.encodeWifiApReady(
                transferId = transferId,
                senderId = localId,
                ssid = hotspotSession.ssid,
                passphrase = hotspotSession.passphrase,
                port = server.localPort,
                hostIp = hotspotSession.hostIp.orEmpty(),
                isReceiverFallbackHost = false
            )
            val readySent = sendBleFrame(targetPeerId, readyFrame)
            if (!readySent) {
                LayoverFileLogger.logError("Failed to send WIFI_AP_READY over BLE for $transferId -> $targetPeerId")
                throw IllegalStateException("Failed to send WIFI_AP_READY over BLE")
            }
            LayoverFileLogger.log(
                "Sender-as-AP ready for $transferId: ssid=${hotspotSession.ssid}, port=${server.localPort}"
            )

            val plaintextBytes = preloadedBytes ?: File(outgoing.originalFilePath).readBytes()
            server.serveAsSenderHost(
                transferId = transferId,
                plaintextBytes = plaintextBytes,
                transferKey = outgoing.transferKey
            )
            LayoverFileLogger.log("Sender-as-AP finished streaming encrypted photo $transferId and received completion ACK.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val cancelledByTeardown = !currentCoroutineContext().isActive ||
                terminalFailureNotified.contains(transferId) ||
                (server?.isClosed == true && e.message?.contains("Socket closed", ignoreCase = true) == true)
            if (cancelledByTeardown) {
                LayoverFileLogger.log("Sender-as-AP stopped cleanly after transfer teardown for $transferId.")
            } else if (hotspotSession == null) {
                LayoverFileLogger.log(
                    "Sender-as-AP failed before hotspot started for $transferId (${e.message}); notifying receiver for 1-on-1 fallback."
                )
                val localId = localPeerIdProvider().ifBlank { "user" }
                sendBleFrame(
                    targetPeerId,
                    WireFrameCodec.encodeWifiApFallback(
                        transferId = transferId,
                        senderId = localId,
                        reason = e.message ?: "sender_hotspot_failed"
                    )
                )
            } else {
                LayoverFileLogger.logError(
                    "Sender-as-AP transfer failed for $transferId after hotspot started (${e.message}); terminating cleanly.",
                    e
                )
                notifyPeerFailureOnce(transferId, targetPeerId)
            }
        } finally {
            val thisJob = currentCoroutineContext()[Job]
            runCatching { server?.close() }
            if (server != null) activeServers.remove(transferId, server)
            runCatching { hotspotSession?.close() }
            if (hotspotSession != null) activeHotspotSessions.remove(transferId, hotspotSession)
            if (thisJob != null) activeTransferJobs.remove(transferId, thisJob)
        }
    }

    /**
     * Handles `WIFI_AP_READY`:
     * - Normal path (`isReceiverFallbackHost == false`): Receiver connects to Sender's hotspot
     *   via `WifiNetworkSpecifier` and streams/verifies the encrypted full-resolution photo.
     * - 1-on-1 fallback path (`isReceiverFallbackHost == true`): Sender connects to Receiver's
     *   hotspot via `WifiNetworkSpecifier` and pushes the encrypted full-resolution photo.
     */
    fun handleIncomingWifiApReady(fromPeerId: String, frame: WireFrame.WifiApReady) {
        cancelTimeoutFor(frame.transferId)
        LayoverFileLogger.log(
            "Received WIFI_AP_READY from $fromPeerId: transferId=${frame.transferId}, " +
                "port=${frame.port}, fallbackHost=${frame.isReceiverFallbackHost}"
        )

        if (frame.isReceiverFallbackHost) {
            val outgoing = outgoingPhotos[frame.transferId] ?: return
            runSenderFallbackClientPush(outgoing, frame)
        } else {
            val incoming = incomingPhotos[frame.transferId]
            if (incoming == null) {
                if (isDpTransferId(frame.transferId) && pendingDpMetaByTransferId.containsKey(frame.transferId)) {
                    pendingEarlyWifiApReadyByTransferId[frame.transferId] = fromPeerId to frame
                    LayoverFileLogger.log(
                        "Stashed early WIFI_AP_READY for DP transfer ${frame.transferId} while awaiting encrypted metadata."
                    )
                }
                return
            }
            val snapshot = downloadQueueStateMachine.getSnapshot(frame.transferId)
            if (snapshot == null ||
                snapshot.state == PhotoTransferState.COMPLETED ||
                snapshot.state == PhotoTransferState.FAILED ||
                downloadQueueStateMachine.activeTransferId() != frame.transferId
            ) {
                LayoverFileLogger.log(
                    "Ignoring stale WIFI_AP_READY for ${frame.transferId} (state=${snapshot?.state}, active=${downloadQueueStateMachine.activeTransferId()})"
                )
                return
            }
            runReceiverClientDownload(incoming, frame)
        }
    }

    /**
     * Handles `WIFI_AP_FALLBACK`:
     * - On Receiver: performs optional 1-on-1 role inversion (Receiver hosts AP) or fails cleanly with retry.
     * - On Sender: cleanly stops any lingering sender AP server so Sender is ready to connect
     *   as a Wi-Fi client to the receiver's fallback hotspot when `WIFI_AP_READY` arrives.
     */
    fun handleIncomingWifiApFallback(fromPeerId: String, frame: WireFrame.WifiApFallback) {
        cancelTimeoutFor(frame.transferId)
        senderRespondedTransfers.add(frame.transferId)
        LayoverFileLogger.log(
            "Received WIFI_AP_FALLBACK from $fromPeerId for ${frame.transferId}: reason=${frame.reason}"
        )
        if (outgoingPhotos.containsKey(frame.transferId) &&
            !incomingPhotos.containsKey(frame.transferId) &&
            !pendingDpMetaByTransferId.containsKey(frame.transferId)
        ) {
            teardownTransferResources(frame.transferId, notifyPeer = false)
            return
        }
        handleSenderApFailureOrTimeout(frame.transferId, frame.reason.ifBlank { "Sender hotspot unavailable" })
    }

    fun handleIncomingPhotoTransferDone(fromPeerId: String, frame: WireFrame.PhotoTransferDone) {
        LayoverFileLogger.log(
            "Received PHOTO_TRANSFER_DONE from $fromPeerId: transferId=${frame.transferId}, success=${frame.success}"
        )
        terminalFailureNotified.add(frame.transferId)
        teardownTransferResources(frame.transferId, notifyPeer = false)
        if (frame.success && isDpTransferId(frame.transferId)) {
            val dpMeta = pendingDpMetaByTransferId[frame.transferId]
            if (dpMeta != null) {
                val cached = peerDpCacheStore.getCachedPeerDpFile(dpMeta.peerAliases + fromPeerId, dpMeta.avatarDigest)
                if (cached != null) {
                    emitTransferStateUpdate(
                        transferId = frame.transferId,
                        state = PhotoTransferState.COMPLETED,
                        progress = 1f,
                        localFilePath = cached.absolutePath,
                        statusDetail = null
                    )
                }
            }
            return
        }
        if (!frame.success &&
            (incomingPhotos.containsKey(frame.transferId) || pendingDpMetaByTransferId.containsKey(frame.transferId))
        ) {
            val snapshot = downloadQueueStateMachine.getSnapshot(frame.transferId)
            if (snapshot != null &&
                snapshot.state != PhotoTransferState.COMPLETED &&
                snapshot.state != PhotoTransferState.FAILED
            ) {
                val failMessage = if (isDpTransferId(frame.transferId) && !incomingPhotos.containsKey(frame.transferId)) {
                    "Full-resolution photo unavailable"
                } else {
                    "Transfer interrupted"
                }
                val next = downloadQueueStateMachine.markFailed(frame.transferId, failMessage)
                emitTransferStateUpdate(frame.transferId, PhotoTransferState.FAILED, 0f, null, failMessage)
                next?.let { startReceiverDownloadRequest(it.transferId, it.senderPeerId) }
            }
        }
    }

    fun cancelTransfer(transferId: String) {
        LayoverFileLogger.log("Cancelling photo transfer $transferId")
        val cancelOutcome = downloadQueueStateMachine.cancel(transferId)
        teardownTransferResources(transferId, notifyPeer = cancelOutcome.wasActive)
        if (incomingPhotos.containsKey(transferId) || pendingDpMetaByTransferId.containsKey(transferId)) {
            emitTransferStateUpdate(transferId, PhotoTransferState.AVAILABLE, 0f, null, null)
        }
        cancelOutcome.nextDownload?.let { next ->
            startReceiverDownloadRequest(next.transferId, next.senderPeerId)
        }
    }

    private fun startReceiverDownloadRequest(transferId: String, senderPeerId: String) {
        terminalFailureNotified.remove(transferId)
        senderRespondedTransfers.remove(transferId)
        cancelTimeoutFor(transferId)
        activeTransferJobs.remove(transferId)?.cancel()

        emitTransferStateUpdate(
            transferId,
            PhotoTransferState.CONNECTING_WIFI,
            0f,
            null,
            PhotoDownloadQueueStateMachine.REQUESTING_HOST_LABEL
        )
        val job = scope.launch {
            val thisJob = coroutineContext[Job]
            try {
                val channelReady = ensureEncryptedChannelReady(senderPeerId)
                if (!channelReady) {
                    LayoverFileLogger.logError(
                        "Cannot send PHOTO_DOWNLOAD_REQUEST: GATT/E2EE not ready for $senderPeerId ($transferId)"
                    )
                    completeFailureAndAdvanceQueue(
                        transferId = transferId,
                        reason = "Encrypted connection unavailable",
                        skipCurrentJobCancel = true,
                        notifyPeer = false
                    )
                    return@launch
                }
                if (downloadQueueStateMachine.activeTransferId() != transferId) {
                    return@launch
                }

                val localId = localPeerIdProvider().ifBlank { "user" }
                val reqFrame = WireFrameCodec.encodePhotoDownloadRequest(
                    transferId = transferId,
                    senderId = localId
                )

                val totalAttempts = maxDownloadRequestAttempts.coerceAtLeast(1)
                for (attempt in 1..totalAttempts) {
                    if (!isActive || downloadQueueStateMachine.activeTransferId() != transferId) {
                        return@launch
                    }

                    val routePref = when (attempt) {
                        1 -> GattRoutePreference.AUTO
                        else -> GattRoutePreference.BOTH_IF_AVAILABLE
                    }

                    if (attempt == totalAttempts && totalAttempts >= 3 && !senderRespondedTransfers.contains(transferId)) {
                        LayoverFileLogger.log(
                            "No sender response for $transferId after attempt ${attempt - 1}; " +
                                "refreshing GATT channel before final PHOTO_DOWNLOAD_REQUEST retry."
                        )
                        refreshPeerChannelForRetry(senderPeerId)
                        if (!isActive || downloadQueueStateMachine.activeTransferId() != transferId) {
                            return@launch
                        }
                    }

                    val reqSent = sendBleFrameWithRoute(senderPeerId, reqFrame, routePref)
                    if (!reqSent && attempt == totalAttempts) {
                        LayoverFileLogger.logError(
                            "Failed to send PHOTO_DOWNLOAD_REQUEST over BLE on final attempt $attempt/$totalAttempts: " +
                                "transferId=$transferId -> $senderPeerId"
                        )
                        completeFailureAndAdvanceQueue(
                            transferId = transferId,
                            reason = "Failed to request photo download",
                            skipCurrentJobCancel = true,
                            notifyPeer = false
                        )
                        return@launch
                    }
                    LayoverFileLogger.log(
                        "Sent PHOTO_DOWNLOAD_REQUEST over BLE (attempt $attempt/$totalAttempts, route=$routePref, sent=$reqSent): " +
                            "transferId=$transferId -> $senderPeerId"
                    )

                    val waitMs = if (attempt < totalAttempts) {
                        downloadRequestRetryIntervalMs
                    } else {
                        postFinalRetryWaitMs
                    }
                    delay(waitMs)

                    // If sender already sent DP metadata while we waited, give its hotspot the remaining AP start window
                    if (senderRespondedTransfers.contains(transferId) &&
                        attempt < totalAttempts &&
                        downloadQueueStateMachine.activeTransferId() == transferId
                    ) {
                        LayoverFileLogger.log(
                            "Sender already responded with metadata for $transferId; waiting for WIFI_AP_READY before retrying."
                        )
                        delay(postFinalRetryWaitMs)
                        if (!isActive || downloadQueueStateMachine.activeTransferId() != transferId) {
                            return@launch
                        }
                    }
                }

                val snapshot = downloadQueueStateMachine.getSnapshot(transferId)
                if (isActive &&
                    downloadQueueStateMachine.activeTransferId() == transferId &&
                    snapshot?.state == PhotoTransferState.CONNECTING_WIFI
                ) {
                    val senderWasReachable = senderRespondedTransfers.contains(transferId)
                    LayoverFileLogger.log(
                        "Timed out waiting for sender WIFI_AP_READY for $transferId after $totalAttempts attempts " +
                            "(senderResponded=$senderWasReachable)."
                    )
                    handleSenderApFailureOrTimeout(
                        transferId = transferId,
                        reason = if (senderWasReachable) "Sender hotspot timed out" else "Sender did not respond",
                        allowRoleInversionFallback = senderWasReachable,
                        skipCurrentJobCancel = true
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LayoverFileLogger.logError("Error initiating PHOTO_DOWNLOAD_REQUEST for $transferId", e)
                completeFailureAndAdvanceQueue(
                    transferId = transferId,
                    reason = "Download request failed",
                    skipCurrentJobCancel = true,
                    notifyPeer = false
                )
            } finally {
                if (thisJob != null) {
                    activeTransferJobs.remove(transferId, thisJob)
                }
                ensureActiveSlotReleasedIfOrphaned(transferId, "Download request ended unexpectedly")
            }
        }
        activeTransferJobs[transferId] = job
    }

    private fun handleSenderApFailureOrTimeout(
        transferId: String,
        reason: String,
        allowRoleInversionFallback: Boolean = true,
        skipCurrentJobCancel: Boolean = false
    ) {
        when (val outcome = downloadQueueStateMachine.handleSenderApFailureOrTimeout(
            transferId = transferId,
            allowRoleInversionFallback = allowRoleInversionFallback,
            reason = reason
        )) {
            is PhotoDownloadQueueStateMachine.SenderApFailureOutcome.StartReceiverFallbackHost -> {
                val incoming = incomingPhotos[transferId]
                if (incoming == null) {
                    completeFailureAndAdvanceQueue(
                        transferId = transferId,
                        reason = reason,
                        skipCurrentJobCancel = skipCurrentJobCancel
                    )
                } else {
                    startReceiverFallbackHost(incoming)
                }
            }
            is PhotoDownloadQueueStateMachine.SenderApFailureOutcome.Failed -> {
                teardownTransferResources(
                    transferId = transferId,
                    notifyPeer = allowRoleInversionFallback,
                    cancelActiveJob = !skipCurrentJobCancel
                )
                emitTransferStateUpdate(transferId, PhotoTransferState.FAILED, 0f, null, outcome.reason)
                outcome.nextDownload?.let { next ->
                    startReceiverDownloadRequest(next.transferId, next.senderPeerId)
                }
            }
            PhotoDownloadQueueStateMachine.SenderApFailureOutcome.Ignored -> Unit
        }
    }

    /**
     * Safety net invoked from receiver coroutine `finally` blocks: if a transfer coroutine exited
     * without being replaced by a successor job (e.g. client download or fallback host) and the
     * queue still holds [transferId] as active, immediately mark it failed and promote the next
     * queued transfer so the download queue can never stall permanently.
     */
    private fun ensureActiveSlotReleasedIfOrphaned(transferId: String, fallbackReason: String) {
        if (activeTransferJobs.containsKey(transferId)) return
        if (downloadQueueStateMachine.activeTransferId() != transferId) return
        val snapshot = downloadQueueStateMachine.getSnapshot(transferId) ?: return
        if (snapshot.state == PhotoTransferState.COMPLETED) return
        LayoverFileLogger.logError(
            "Releasing orphaned active download slot for $transferId (state=${snapshot.state}) to unblock queue."
        )
        val nextQueued = downloadQueueStateMachine.markFailed(transferId, fallbackReason)
        emitTransferStateUpdate(transferId, PhotoTransferState.FAILED, 0f, null, fallbackReason)
        nextQueued?.let { next ->
            startReceiverDownloadRequest(next.transferId, next.senderPeerId)
        }
    }

    private fun runReceiverClientDownload(
        incoming: IncomingPhotoSession,
        readyFrame: WireFrame.WifiApReady
    ) {
        val transferId = incoming.transferId
        senderRespondedTransfers.add(transferId)
        cancelTimeoutFor(transferId)
        val previousJob = activeTransferJobs.remove(transferId)
        val job = scope.launch {
            val thisJob = coroutineContext[Job]
            var wifiConn: HotspotClientConnection? = null
            var socket: Socket? = null
            try {
                downloadQueueStateMachine.markConnectingWifi(
                    transferId,
                    PhotoDownloadQueueStateMachine.CONNECTING_WIFI_LABEL
                )
                emitTransferStateUpdate(
                    transferId,
                    PhotoTransferState.CONNECTING_WIFI,
                    0f,
                    null,
                    PhotoDownloadQueueStateMachine.CONNECTING_WIFI_LABEL
                )

                wifiConn = hotspotController.connectToHotspot(
                    ssid = readyFrame.ssid,
                    passphrase = readyFrame.passphrase,
                    hostIpHint = readyFrame.hostIp.ifBlank { null }
                )
                activeClientConnections[transferId] = wifiConn

                socket = wifiConn.openSocket(readyFrame.port)
                EncryptedPhotoStreamProtocol.writeRoleHandshake(
                    outputStream = socket.getOutputStream(),
                    role = EncryptedPhotoStreamProtocol.SocketRole.RECEIVER,
                    transferId = transferId
                )
                EncryptedPhotoStreamProtocol.readReadyAck(socket.getInputStream())

                downloadQueueStateMachine.markDownloading(transferId, 0f)
                emitTransferStateUpdate(transferId, PhotoTransferState.DOWNLOADING, 0f, null, "Downloading 0%")

                val decryptedBytes = EncryptedPhotoStreamProtocol.readEncryptedPhotoStream(
                    expectedPlaintextSize = incoming.fileSizeBytes,
                    expectedSha256Hex = incoming.sha256Hex,
                    transferId = transferId,
                    key = incoming.transferKey,
                    inputStream = socket.getInputStream(),
                    onProgress = { progress ->
                        downloadQueueStateMachine.markDownloading(transferId, progress)
                        emitTransferStateUpdate(
                            transferId,
                            PhotoTransferState.DOWNLOADING,
                            progress,
                            null,
                            "Downloading ${(progress * 100).toInt()}%"
                        )
                    }
                )

                // Acknowledge full receipt + SHA-256 verification before closing socket & NetworkCallback
                // so the sender releases LocalOnlyHotspot only after every chunk is verified.
                runCatching {
                    EncryptedPhotoStreamProtocol.writeTransferCompleteAck(socket.getOutputStream())
                }

                // Always close socket & unregister Wi-Fi NetworkCallback after transfer + SHA-256 finish
                runCatching { socket.close() }
                socket = null
                runCatching { wifiConn.close() }
                activeClientConnections.remove(transferId, wifiConn)
                wifiConn = null

                completeReceiverDownload(incoming, decryptedBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LayoverFileLogger.logError("Receiver Wi-Fi download failed for $transferId", e)
                runCatching { socket?.close() }
                runCatching { wifiConn?.close() }
                if (wifiConn != null) activeClientConnections.remove(transferId, wifiConn)
                if (!terminalFailureNotified.contains(transferId)) {
                    completeFailureAndAdvanceQueue(
                        transferId = transferId,
                        reason = if (e is SocketTimeoutException) "Wi-Fi transfer timed out" else "Download failed",
                        skipCurrentJobCancel = true
                    )
                }
            } finally {
                runCatching { socket?.close() }
                runCatching { wifiConn?.close() }
                if (wifiConn != null) activeClientConnections.remove(transferId, wifiConn)
                if (thisJob != null) activeTransferJobs.remove(transferId, thisJob)
                ensureActiveSlotReleasedIfOrphaned(transferId, "Download failed")
            }
        }
        activeTransferJobs[transferId] = job
        previousJob?.cancel()
    }

    /**
     * Optional 1-on-1 role-inversion fallback: Receiver temporarily hosts `LocalOnlyHotspot`
     * + `DirectPhotoSocketServer` when Sender's `startLocalOnlyHotspot` fails or times out.
     */
    private fun startReceiverFallbackHost(incoming: IncomingPhotoSession) {
        val transferId = incoming.transferId
        cancelTimeoutFor(transferId)
        val previousJob = activeTransferJobs.remove(transferId)
        val job = scope.launch {
            val thisJob = coroutineContext[Job]
            var hotspotSession: StartedHotspotSession? = null
            var server: DirectPhotoSocketServer? = null
            try {
                emitTransferStateUpdate(
                    transferId,
                    PhotoTransferState.CONNECTING_WIFI,
                    0f,
                    null,
                    PhotoDownloadQueueStateMachine.FALLBACK_HOST_LABEL
                )
                hotspotSession = hotspotController.startHotspot(PhotoTransferConfig.AP_START_TIMEOUT_MS)
                activeHotspotSessions[transferId] = hotspotSession

                server = DirectPhotoSocketServer()
                activeServers[transferId] = server

                val localId = localPeerIdProvider().ifBlank { "user" }
                val readyFrame = WireFrameCodec.encodeWifiApReady(
                    transferId = transferId,
                    senderId = localId,
                    ssid = hotspotSession.ssid,
                    passphrase = hotspotSession.passphrase,
                    port = server.localPort,
                    hostIp = hotspotSession.hostIp.orEmpty(),
                    isReceiverFallbackHost = true
                )
                val readySent = sendBleFrameWithRoute(
                    incoming.senderPeerId,
                    readyFrame,
                    GattRoutePreference.BOTH_IF_AVAILABLE
                )
                if (!readySent) {
                    throw IllegalStateException("Failed to send fallback WIFI_AP_READY over BLE")
                }
                LayoverFileLogger.log(
                    "Receiver 1-on-1 fallback AP ready for $transferId: ssid=${hotspotSession.ssid}, port=${server.localPort}"
                )

                val decryptedBytes = server.serveAsReceiverHost(
                    transferId = transferId,
                    expectedPlaintextSize = incoming.fileSizeBytes,
                    expectedSha256Hex = incoming.sha256Hex,
                    transferKey = incoming.transferKey,
                    onProgress = { progress ->
                        downloadQueueStateMachine.markDownloading(transferId, progress)
                        emitTransferStateUpdate(
                            transferId,
                            PhotoTransferState.DOWNLOADING,
                            progress,
                            null,
                            "Downloading ${(progress * 100).toInt()}%"
                        )
                    }
                )

                runCatching { server.close() }
                activeServers.remove(transferId, server)
                server = null
                runCatching { hotspotSession.close() }
                activeHotspotSessions.remove(transferId, hotspotSession)
                hotspotSession = null

                completeReceiverDownload(incoming, decryptedBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val alreadyTerminatedByPeer = !isActive || terminalFailureNotified.contains(transferId)
                if (!alreadyTerminatedByPeer) {
                    LayoverFileLogger.logError("Receiver 1-on-1 fallback AP failed for $transferId", e)
                } else {
                    LayoverFileLogger.log("Receiver 1-on-1 fallback AP stopped cleanly after peer teardown for $transferId.")
                }
                runCatching { server?.close() }
                if (server != null) activeServers.remove(transferId, server)
                runCatching { hotspotSession?.close() }
                if (hotspotSession != null) activeHotspotSessions.remove(transferId, hotspotSession)
                if (!alreadyTerminatedByPeer) {
                    val failReason = when (e) {
                        is SecurityException -> "Wi-Fi permission required"
                        is SocketTimeoutException -> "Wi-Fi transfer timed out"
                        else -> "Unable to establish Wi-Fi transfer"
                    }
                    completeFailureAndAdvanceQueue(
                        transferId = transferId,
                        reason = failReason,
                        skipCurrentJobCancel = true
                    )
                }
            } finally {
                runCatching { server?.close() }
                if (server != null) activeServers.remove(transferId, server)
                runCatching { hotspotSession?.close() }
                if (hotspotSession != null) activeHotspotSessions.remove(transferId, hotspotSession)
                if (thisJob != null) activeTransferJobs.remove(transferId, thisJob)
                ensureActiveSlotReleasedIfOrphaned(transferId, "Wi-Fi transfer timed out")
            }
        }
        activeTransferJobs[transferId] = job
        previousJob?.cancel()
    }

    private fun runSenderFallbackClientPush(
        outgoing: OutgoingPhotoSession,
        readyFrame: WireFrame.WifiApReady
    ) {
        val transferId = outgoing.transferId
        terminalFailureNotified.remove(transferId)
        teardownTransferResources(transferId, notifyPeer = false)
        val job = scope.launch {
            var wifiConn: HotspotClientConnection? = null
            var socket: Socket? = null
            try {
                wifiConn = hotspotController.connectToHotspot(
                    ssid = readyFrame.ssid,
                    passphrase = readyFrame.passphrase,
                    hostIpHint = readyFrame.hostIp.ifBlank { null }
                )
                activeClientConnections[transferId] = wifiConn

                socket = wifiConn.openSocket(readyFrame.port)
                EncryptedPhotoStreamProtocol.writeRoleHandshake(
                    outputStream = socket.getOutputStream(),
                    role = EncryptedPhotoStreamProtocol.SocketRole.SENDER,
                    transferId = transferId
                )
                EncryptedPhotoStreamProtocol.readReadyAck(socket.getInputStream())

                val plaintextBytes = File(outgoing.originalFilePath).readBytes()
                EncryptedPhotoStreamProtocol.writeEncryptedPhotoStream(
                    plaintextBytes = plaintextBytes,
                    transferId = transferId,
                    key = outgoing.transferKey,
                    outputStream = socket.getOutputStream()
                )
                socket.soTimeout = 10_000
                EncryptedPhotoStreamProtocol.awaitTransferCompleteAck(socket.getInputStream())
                LayoverFileLogger.log("Sender completed 1-on-1 fallback encrypted push for $transferId.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LayoverFileLogger.logError("Sender 1-on-1 fallback push failed for $transferId", e)
                notifyPeerFailureOnce(transferId, outgoing.recipientPeerId)
            } finally {
                val thisJob = coroutineContext[Job]
                runCatching { socket?.close() }
                runCatching { wifiConn?.close() }
                if (wifiConn != null) activeClientConnections.remove(transferId, wifiConn)
                if (thisJob != null) activeTransferJobs.remove(transferId, thisJob)
            }
        }
        activeTransferJobs[transferId] = job
    }

    private fun completeReceiverDownload(incoming: IncomingPhotoSession, decryptedBytes: ByteArray) {
        val dpMeta = pendingDpMetaByTransferId[incoming.transferId]
        val savedFile = if (dpMeta != null) {
            peerDpCacheStore.savePeerDp(
                canonicalPeerKey = dpMeta.peerId,
                peerAliases = dpMeta.peerAliases,
                avatarDigest = dpMeta.avatarDigest,
                bytes = decryptedBytes
            )
        } else {
            savePhotoBytes(incoming.transferId, decryptedBytes)
        }
        if (savedFile == null) {
            completeFailureAndAdvanceQueue(
                transferId = incoming.transferId,
                reason = "Unable to save photo",
                skipCurrentJobCancel = true
            )
            return
        }

        val localId = localPeerIdProvider().ifBlank { "user" }
        sendBleFrame(
            incoming.senderPeerId,
            WireFrameCodec.encodePhotoTransferDone(incoming.transferId, localId, success = true)
        )

        val nextQueued = downloadQueueStateMachine.markCompleted(incoming.transferId)
        pendingEarlyWifiApReadyByTransferId.remove(incoming.transferId)
        emitTransferStateUpdate(
            incoming.transferId,
            PhotoTransferState.COMPLETED,
            1f,
            savedFile.absolutePath,
            null
        )
        LayoverFileLogger.log(
            "${if (dpMeta != null) "Peer full-res DP" else "Photo"} download completed & verified: " +
                "transferId=${incoming.transferId}, bytes=${decryptedBytes.size}, path=${savedFile.absolutePath}"
        )

        nextQueued?.let { next ->
            startReceiverDownloadRequest(next.transferId, next.senderPeerId)
        }
    }

    private fun completeFailureAndAdvanceQueue(
        transferId: String,
        reason: String,
        skipCurrentJobCancel: Boolean = false,
        notifyPeer: Boolean = true
    ) {
        teardownTransferResources(
            transferId = transferId,
            notifyPeer = notifyPeer,
            cancelActiveJob = !skipCurrentJobCancel
        )
        val nextQueued = downloadQueueStateMachine.markFailed(transferId, reason)
        emitTransferStateUpdate(transferId, PhotoTransferState.FAILED, 0f, null, reason)
        nextQueued?.let { next ->
            startReceiverDownloadRequest(next.transferId, next.senderPeerId)
        }
    }

    private fun notifyPeerFailureOnce(transferId: String, explicitPeerId: String? = null) {
        if (!terminalFailureNotified.add(transferId)) return
        val localId = localPeerIdProvider().ifBlank { "user" }
        val peerId = explicitPeerId?.takeIf { it.isNotBlank() }
            ?: incomingPhotos[transferId]?.senderPeerId
            ?: pendingDpMetaByTransferId[transferId]?.peerId
            ?: outgoingPhotos[transferId]?.recipientPeerId
        if (!peerId.isNullOrBlank()) {
            sendBleFrame(
                peerId,
                WireFrameCodec.encodePhotoTransferDone(transferId, localId, success = false)
            )
        }
    }

    private fun teardownTransferResources(
        transferId: String,
        notifyPeer: Boolean,
        cancelActiveJob: Boolean = true
    ) {
        cancelTimeoutFor(transferId)
        senderRespondedTransfers.remove(transferId)
        pendingEarlyWifiApReadyByTransferId.remove(transferId)
        if (cancelActiveJob) {
            activeTransferJobs.remove(transferId)?.cancel()
        }
        activeServers.remove(transferId)?.let { runCatching { it.close() } }
        activeHotspotSessions.remove(transferId)?.let { runCatching { it.close() } }
        activeClientConnections.remove(transferId)?.let { runCatching { it.close() } }

        if (notifyPeer) {
            notifyPeerFailureOnce(transferId)
        }
    }

    private fun cancelTimeoutFor(transferId: String) {
        activeApTimeoutJobs.remove(transferId)?.cancel()
    }

    private fun savePhotoBytes(transferId: String, bytes: ByteArray): File? {
        return runCatching {
            if (!photoStorageDir.exists()) photoStorageDir.mkdirs()
            val outFile = File(photoStorageDir, "$transferId.jpg")
            outFile.writeBytes(bytes)
            outFile
        }.onFailure { err ->
            LayoverFileLogger.logError("Failed to save photo bytes for $transferId", err)
        }.getOrNull()
    }

    fun cleanup() {
        activeApTimeoutJobs.values.forEach { it.cancel() }
        activeApTimeoutJobs.clear()
        activeTransferJobs.values.forEach { it.cancel() }
        activeTransferJobs.clear()
        activeServers.values.forEach { runCatching { it.close() } }
        activeServers.clear()
        activeHotspotSessions.values.forEach { runCatching { it.close() } }
        activeHotspotSessions.clear()
        activeClientConnections.values.forEach { runCatching { it.close() } }
        activeClientConnections.clear()
        pendingEarlyWifiApReadyByTransferId.clear()
        senderRespondedTransfers.clear()
        terminalFailureNotified.clear()
        hotspotController.stopHotspot()
        downloadQueueStateMachine.clearAll()
    }
}
