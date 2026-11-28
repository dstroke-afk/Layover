package com.intj.layover.wifi

import com.intj.layover.model.PhotoTransferState

/**
 * Pure Kotlin state machine & FIFO download queue serializer for direct 1-on-1
 * LocalOnlyHotspot photo downloads on the receiver.
 *
 * Guarantees:
 * - At most one active `WifiNetworkSpecifier` download session at a time.
 * - Additional tapped photos are automatically queued (`QUEUED` with
 *   `"Waiting for current download..."`) and start sequentially once the active hotspot
 *   disconnects and tears down cleanly.
 * - Supports optional 1-on-1 role-inversion fallback (Receiver hosts AP if Sender's
 *   `startLocalOnlyHotspot` fails or times out), failing cleanly to `FAILED` with retry if both fail.
 */
class PhotoDownloadQueueStateMachine {

    companion object {
        const val WAITING_FOR_CURRENT_DOWNLOAD_LABEL = "Waiting for current download..."
        const val CONNECTING_WIFI_LABEL = "Connecting Wi-Fi..."
        const val REQUESTING_HOST_LABEL = "Starting secure Wi-Fi..."
        const val FALLBACK_HOST_LABEL = "Switching Wi-Fi host..."
    }

    data class TransferSnapshot(
        val transferId: String,
        val senderPeerId: String,
        val state: PhotoTransferState,
        val progress: Float = 0f,
        val statusDetail: String? = null,
        val attemptedRoleInversion: Boolean = false
    )

    data class NextDownload(
        val transferId: String,
        val senderPeerId: String
    )

    sealed class DownloadRequestOutcome {
        data class StartNow(val transferId: String, val senderPeerId: String) : DownloadRequestOutcome()
        data class Queued(val transferId: String, val queuePosition: Int, val statusDetail: String) : DownloadRequestOutcome()
        data object Ignored : DownloadRequestOutcome()
    }

    sealed class SenderApFailureOutcome {
        data class StartReceiverFallbackHost(val transferId: String, val senderPeerId: String) : SenderApFailureOutcome()
        data class Failed(val transferId: String, val reason: String, val nextDownload: NextDownload?) : SenderApFailureOutcome()
        data object Ignored : SenderApFailureOutcome()
    }

    data class CancelOutcome(
        val wasActive: Boolean,
        val nextDownload: NextDownload?
    )

    private val entries = LinkedHashMap<String, TransferSnapshot>()
    private val queue = ArrayDeque<String>()
    private var activeId: String? = null

    @Synchronized
    fun activeTransferId(): String? = activeId

    @Synchronized
    fun queuedTransferIds(): List<String> = queue.toList()

    @Synchronized
    fun getSnapshot(transferId: String): TransferSnapshot? = entries[transferId]

    @Synchronized
    fun registerAvailable(transferId: String, senderPeerId: String): TransferSnapshot {
        val existing = entries[transferId]
        if (existing != null) return existing
        val created = TransferSnapshot(
            transferId = transferId,
            senderPeerId = senderPeerId,
            state = PhotoTransferState.AVAILABLE,
            progress = 0f,
            statusDetail = null,
            attemptedRoleInversion = false
        )
        entries[transferId] = created
        return created
    }

    @Synchronized
    fun requestDownload(transferId: String): DownloadRequestOutcome {
        val entry = entries[transferId] ?: return DownloadRequestOutcome.Ignored
        if (entry.state == PhotoTransferState.COMPLETED ||
            entry.state == PhotoTransferState.CONNECTING_WIFI ||
            entry.state == PhotoTransferState.DOWNLOADING ||
            entry.state == PhotoTransferState.QUEUED
        ) {
            return DownloadRequestOutcome.Ignored
        }

        if (activeId == null) {
            activeId = transferId
            entries[transferId] = entry.copy(
                state = PhotoTransferState.CONNECTING_WIFI,
                progress = 0f,
                statusDetail = REQUESTING_HOST_LABEL,
                attemptedRoleInversion = false
            )
            return DownloadRequestOutcome.StartNow(transferId, entry.senderPeerId)
        }

        if (!queue.contains(transferId)) {
            queue.addLast(transferId)
        }
        entries[transferId] = entry.copy(
            state = PhotoTransferState.QUEUED,
            progress = 0f,
            statusDetail = WAITING_FOR_CURRENT_DOWNLOAD_LABEL,
            attemptedRoleInversion = false
        )
        return DownloadRequestOutcome.Queued(
            transferId = transferId,
            queuePosition = queue.indexOf(transferId) + 1,
            statusDetail = WAITING_FOR_CURRENT_DOWNLOAD_LABEL
        )
    }

    @Synchronized
    fun markConnectingWifi(transferId: String, detail: String = CONNECTING_WIFI_LABEL): TransferSnapshot? {
        val entry = entries[transferId] ?: return null
        if (activeId != transferId) return null
        val updated = entry.copy(
            state = PhotoTransferState.CONNECTING_WIFI,
            statusDetail = detail
        )
        entries[transferId] = updated
        return updated
    }

    @Synchronized
    fun markDownloading(transferId: String, progress: Float): TransferSnapshot? {
        val entry = entries[transferId] ?: return null
        if (activeId != transferId) return null
        val clamped = progress.coerceIn(0f, 1f)
        val updated = entry.copy(
            state = PhotoTransferState.DOWNLOADING,
            progress = clamped,
            statusDetail = "Downloading ${(clamped * 100).toInt()}%"
        )
        entries[transferId] = updated
        return updated
    }

    @Synchronized
    fun handleSenderApFailureOrTimeout(
        transferId: String,
        allowRoleInversionFallback: Boolean = true,
        reason: String = "Sender hotspot unavailable"
    ): SenderApFailureOutcome {
        val entry = entries[transferId] ?: return SenderApFailureOutcome.Ignored
        if (activeId != transferId) return SenderApFailureOutcome.Ignored

        if (allowRoleInversionFallback && !entry.attemptedRoleInversion) {
            entries[transferId] = entry.copy(
                state = PhotoTransferState.CONNECTING_WIFI,
                progress = 0f,
                statusDetail = FALLBACK_HOST_LABEL,
                attemptedRoleInversion = true
            )
            return SenderApFailureOutcome.StartReceiverFallbackHost(transferId, entry.senderPeerId)
        }

        val next = markFailed(transferId, reason)
        return SenderApFailureOutcome.Failed(transferId, reason, next)
    }

    @Synchronized
    fun markCompleted(transferId: String): NextDownload? {
        val entry = entries[transferId]
        if (entry != null) {
            entries[transferId] = entry.copy(
                state = PhotoTransferState.COMPLETED,
                progress = 1f,
                statusDetail = null
            )
        }
        queue.remove(transferId)
        if (activeId == transferId) {
            activeId = null
            return promoteNextQueuedLocked()
        }
        return null
    }

    @Synchronized
    fun markFailed(transferId: String, reason: String): NextDownload? {
        val entry = entries[transferId]
        val wasActive = activeId == transferId
        if (entry != null) {
            if (entry.state == PhotoTransferState.COMPLETED) return null
            if (entry.state == PhotoTransferState.FAILED && !wasActive) return null
            entries[transferId] = entry.copy(
                state = PhotoTransferState.FAILED,
                progress = 0f,
                statusDetail = reason
            )
        }
        queue.remove(transferId)
        if (wasActive) {
            activeId = null
            return promoteNextQueuedLocked()
        }
        return null
    }

    @Synchronized
    fun cancel(transferId: String): CancelOutcome {
        val wasActive = activeId == transferId
        val entry = entries[transferId]
        if (entry != null && entry.state != PhotoTransferState.COMPLETED) {
            entries[transferId] = entry.copy(
                state = PhotoTransferState.AVAILABLE,
                progress = 0f,
                statusDetail = null,
                attemptedRoleInversion = false
            )
        }
        queue.remove(transferId)
        val next = if (wasActive) {
            activeId = null
            promoteNextQueuedLocked()
        } else {
            null
        }
        return CancelOutcome(wasActive = wasActive, nextDownload = next)
    }

    @Synchronized
    fun clearAll() {
        entries.clear()
        queue.clear()
        activeId = null
    }

    private fun promoteNextQueuedLocked(): NextDownload? {
        while (queue.isNotEmpty()) {
            val nextId = queue.removeFirst()
            val nextEntry = entries[nextId] ?: continue
            if (nextEntry.state == PhotoTransferState.QUEUED) {
                activeId = nextId
                entries[nextId] = nextEntry.copy(
                    state = PhotoTransferState.CONNECTING_WIFI,
                    progress = 0f,
                    statusDetail = REQUESTING_HOST_LABEL,
                    attemptedRoleInversion = false
                )
                return NextDownload(nextId, nextEntry.senderPeerId)
            }
        }
        return null
    }
}
