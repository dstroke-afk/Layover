package com.intj.layover.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.intj.layover.ble.BlePermissionHelper
import com.intj.layover.diagnostics.LayoverFileLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Active single-transfer LocalOnlyHotspot handle. Closing immediately releases the
 * [WifiManager.LocalOnlyHotspotReservation] so no idle hotspot remains running.
 */
class StartedHotspotSession(
    val ssid: String,
    val passphrase: String,
    val hostIp: String?,
    private val onClose: () -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            onClose()
        }
    }
}

/**
 * Active Wi-Fi client session bound via [WifiNetworkSpecifier].
 * Sockets are created through [Network.getSocketFactory] so mobile data does not hijack routing,
 * and [close] always unregisters the [ConnectivityManager.NetworkCallback].
 */
class HotspotClientConnection(
    val network: Network,
    val resolvedHostIp: String,
    private val connectivityManager: ConnectivityManager,
    private val networkCallback: ConnectivityManager.NetworkCallback
) : Closeable {
    private val closed = AtomicBoolean(false)

    suspend fun openSocket(
        port: Int,
        connectTimeoutMs: Int = PhotoTransferConfig.SOCKET_CONNECT_TIMEOUT_MS.toInt(),
        readTimeoutMs: Int = PhotoTransferConfig.SOCKET_READ_TIMEOUT_MS.toInt()
    ): Socket = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        val maxAttempts = 4
        for (attempt in 1..maxAttempts) {
            var socket: Socket? = null
            try {
                socket = network.socketFactory.createSocket()
                socket.tcpNoDelay = true
                socket.soTimeout = readTimeoutMs
                socket.connect(InetSocketAddress(resolvedHostIp, port), connectTimeoutMs)
                LayoverFileLogger.log(
                    "Bound TCP socket connected over LocalOnlyHotspot: host=$resolvedHostIp:$port (attempt $attempt/$maxAttempts)"
                )
                return@withContext socket
            } catch (e: IOException) {
                runCatching { socket?.close() }
                lastError = e
                LayoverFileLogger.log(
                    "Bound TCP socket connect attempt $attempt/$maxAttempts failed for $resolvedHostIp:$port: ${e.message}"
                )
                if (attempt < maxAttempts) {
                    delay(500L)
                }
            }
        }
        throw lastError ?: IOException("Failed to connect bound socket to $resolvedHostIp:$port")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }.onSuccess {
            LayoverFileLogger.log("Unregistered LocalOnlyHotspot NetworkCallback cleanly.")
        }.onFailure { err ->
            LayoverFileLogger.logError("Failed to unregister LocalOnlyHotspot NetworkCallback", err)
        }
    }
}

/**
 * Manages single-transfer Android [WifiManager.LocalOnlyHotspotReservation] lifecycles
 * (start on-demand, shut down immediately after transfer) and client connections via
 * [WifiNetworkSpecifier] + [ConnectivityManager.requestNetwork].
 */
class LocalOnlyHotspotController(
    private val context: Context
) {
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val activeReservation = AtomicReference<WifiManager.LocalOnlyHotspotReservation?>(null)

    /**
     * Starts a fresh temporary LocalOnlyHotspot for a single photo transfer.
     * Any previous stale reservation is closed first; the returned [StartedHotspotSession]
     * must always be closed in a `finally` block so the hotspot never lingers idle.
     */
    @SuppressLint("MissingPermission")
    suspend fun startHotspot(
        timeoutMs: Long = PhotoTransferConfig.AP_START_TIMEOUT_MS
    ): StartedHotspotSession {
        stopHotspot()

        val wm = wifiManager ?: throw IllegalStateException("WifiManager unavailable")
        val missingPerms = BlePermissionHelper.getMissingWifiHotspotPermissions(context)
        if (missingPerms.isNotEmpty()) {
            LayoverFileLogger.logError(
                "Cannot start LocalOnlyHotspot on API ${Build.VERSION.SDK_INT}: missing permissions=${missingPerms.joinToString()}"
            )
            throw SecurityException("Wi-Fi hotspot permission missing (${missingPerms.joinToString()})")
        }

        val deferred = CompletableDeferred<WifiManager.LocalOnlyHotspotReservation>()
        val startedRef = AtomicReference<WifiManager.LocalOnlyHotspotReservation?>(null)
        val mainHandler = Handler(Looper.getMainLooper())

        try {
            wm.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        startedRef.set(reservation)
                        if (!deferred.complete(reservation)) {
                            runCatching { reservation.close() }
                            startedRef.compareAndSet(reservation, null)
                        }
                    }

                    override fun onStopped() {
                        LayoverFileLogger.log("LocalOnlyHotspot stopped by system.")
                        activeReservation.set(null)
                    }

                    override fun onFailed(reason: Int) {
                        val reasonLabel = hotspotFailureReasonLabel(reason)
                        LayoverFileLogger.logError("startLocalOnlyHotspot onFailed: $reasonLabel ($reason)")
                        deferred.completeExceptionally(
                            IllegalStateException("startLocalOnlyHotspot failed: $reasonLabel ($reason)")
                        )
                    }
                },
                mainHandler
            )
        } catch (e: SecurityException) {
            LayoverFileLogger.logError(
                "startLocalOnlyHotspot threw SecurityException on API ${Build.VERSION.SDK_INT}: ${e.message}",
                e
            )
            throw SecurityException("Wi-Fi hotspot permission denied by system", e)
        } catch (e: Exception) {
            throw IllegalStateException("startLocalOnlyHotspot threw ${e.message}", e)
        }

        val reservation = try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: Exception) {
            startedRef.getAndSet(null)?.let { runCatching { it.close() } }
            throw e
        }

        val (ssid, passphrase) = extractCredentials(reservation)
        if (ssid.isNullOrBlank() || passphrase.isNullOrBlank()) {
            runCatching { reservation.close() }
            throw IllegalStateException("LocalOnlyHotspot started without usable SSID/passphrase")
        }

        activeReservation.getAndSet(reservation)?.let { old -> runCatching { old.close() } }

        // Wait briefly for the dedicated softAP interface (swlan/ap) to acquire its IPv4 address.
        // Never fall back to home Wi-Fi's wlan0 address, which is unreachable from the hotspot subnet.
        var hostIp = resolveLocalHotspotIpv4()
        if (hostIp == null) {
            repeat(4) {
                delay(100L)
                hostIp = resolveLocalHotspotIpv4()
                if (hostIp != null) return@repeat
            }
        }
        LayoverFileLogger.log("Started temporary Layover LocalOnlyHotspot (ssid=$ssid, hostIp=$hostIp)")

        return StartedHotspotSession(
            ssid = ssid,
            passphrase = passphrase,
            hostIp = hostIp,
            onClose = {
                if (activeReservation.compareAndSet(reservation, null)) {
                    runCatching { reservation.close() }
                        .onSuccess { LayoverFileLogger.log("Closed LocalOnlyHotspotReservation immediately.") }
                        .onFailure { LayoverFileLogger.logError("Error closing LocalOnlyHotspotReservation", it) }
                } else {
                    runCatching { reservation.close() }
                }
            }
        )
    }

    fun stopHotspot() {
        activeReservation.getAndSet(null)?.let { reservation ->
            runCatching { reservation.close() }
                .onSuccess { LayoverFileLogger.log("Closed active LocalOnlyHotspotReservation.") }
                .onFailure { LayoverFileLogger.logError("Failed closing LocalOnlyHotspotReservation", it) }
        }
    }

    /**
     * Connects to the peer's LocalOnlyHotspot using [WifiNetworkSpecifier] and keeps the
     * [ConnectivityManager.NetworkCallback] registered until [HotspotClientConnection.close]
     * is called after transfer and SHA-256 verification finish.
     */
    suspend fun connectToHotspot(
        ssid: String,
        passphrase: String,
        hostIpHint: String?,
        timeoutMs: Long = PhotoTransferConfig.WIFI_NETWORK_CONNECT_TIMEOUT_MS
    ): HotspotClientConnection {
        val cm = connectivityManager ?: throw IllegalStateException("ConnectivityManager unavailable")
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val availableDeferred = CompletableDeferred<Network>()
        val linkPropsDeferred = CompletableDeferred<LinkProperties>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                availableDeferred.complete(network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (!linkPropsDeferred.isCompleted && extractHostIpFromLinkProperties(linkProperties) != null) {
                    linkPropsDeferred.complete(linkProperties)
                }
            }

            override fun onLost(network: Network) {
                LayoverFileLogger.log("LocalOnlyHotspot Wi-Fi network lost (ssid=$ssid)")
            }

            override fun onUnavailable() {
                availableDeferred.completeExceptionally(
                    IllegalStateException("LocalOnlyHotspot Wi-Fi network unavailable (ssid=$ssid)")
                )
            }
        }

        cm.requestNetwork(request, callback, timeoutMs.toInt())

        val network = try {
            withTimeout(timeoutMs + 2_000L) {
                availableDeferred.await()
            }
        } catch (e: Exception) {
            runCatching { cm.unregisterNetworkCallback(callback) }
            throw e
        }

        val immediateProps = runCatching { cm.getLinkProperties(network) }.getOrNull()
        val resolvedLinkProps = if (extractHostIpFromLinkProperties(immediateProps) != null) {
            immediateProps
        } else {
            withTimeoutOrNull(2_500L) { linkPropsDeferred.await() }
                ?: runCatching { cm.getLinkProperties(network) }.getOrNull()
        }

        val resolvedIp = resolveGatewayIp(resolvedLinkProps, hostIpHint)
        LayoverFileLogger.log("Connected to peer LocalOnlyHotspot ssid=$ssid, resolvedHostIp=$resolvedIp (hint=$hostIpHint)")
        return HotspotClientConnection(
            network = network,
            resolvedHostIp = resolvedIp,
            connectivityManager = cm,
            networkCallback = callback
        )
    }

    private fun hotspotFailureReasonLabel(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "ERROR_NO_CHANNEL"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "ERROR_GENERIC"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "ERROR_INCOMPATIBLE_MODE"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "ERROR_TETHERING_DISALLOWED"
        else -> "ERROR_UNKNOWN"
    }

    @Suppress("DEPRECATION")
    private fun extractCredentials(
        reservation: WifiManager.LocalOnlyHotspotReservation
    ): Pair<String?, String?> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val softAp = reservation.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                softAp.wifiSsid?.bytes
                    ?.let { String(it, Charsets.UTF_8) }
                    ?.takeIf { it.isNotBlank() }
                    ?: softAp.ssid?.trim('"')
            } else {
                softAp.ssid?.trim('"')
            }
            val pass = softAp.passphrase
            if (!ssid.isNullOrBlank() && !pass.isNullOrBlank()) {
                return ssid to pass
            }
        }
        val legacy = reservation.wifiConfiguration
        return legacy?.SSID?.trim('"') to legacy?.preSharedKey?.trim('"')
    }

    private fun extractHostIpFromLinkProperties(linkProps: LinkProperties?): String? {
        val dhcpAddr = linkProps?.dhcpServerAddress?.hostAddress?.takeIf { isValidIpv4(it) }
        if (dhcpAddr != null) return dhcpAddr

        return linkProps?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress
            ?.takeIf { isValidIpv4(it) }
    }

    private fun resolveGatewayIp(
        linkProps: LinkProperties?,
        hostIpHint: String?
    ): String {
        // Prioritize the bound LocalOnlyHotspot network's own DHCP server / default gateway first,
        // because the hotspot host IS the DHCP server on the exact active subnet.
        extractHostIpFromLinkProperties(linkProps)?.let { return it }

        val cleanHint = hostIpHint?.trim()?.takeIf { isValidIpv4(it) }
        if (cleanHint != null) return cleanHint

        return "192.168.43.1"
    }

    private fun resolveLocalHotspotIpv4(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val hotspotPrefixes = listOf("swlan", "ap", "softap", "p2p", "wlan1", "wlan2")
            for (prefix in hotspotPrefixes) {
                val match = interfaces
                    .filter { it.isUp && !it.isLoopback && it.name.startsWith(prefix, ignoreCase = true) }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { addr -> addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress }
                if (match?.hostAddress != null) {
                    return match.hostAddress
                }
            }
            null
        }.getOrNull()
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
