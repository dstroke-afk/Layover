package com.intj.layover

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.intj.layover.ble.BlePermissionHelper
import com.intj.layover.ble.BleProximityService
import com.intj.layover.ble.LayoverAppRuntime
import com.intj.layover.ble.PeerAliasRegistry
import com.intj.layover.diagnostics.LayoverFileLogger
import com.intj.layover.model.formatPeerNameWithBadge
import com.intj.layover.navigation.LayoverNavGraph
import com.intj.layover.navigation.LayoverRoute
import com.intj.layover.notifications.LayoverNotificationManager
import com.intj.layover.notifications.NotificationDeepLink
import com.intj.layover.ui.screens.OneTimeUsernameDialog
import com.intj.layover.ui.screens.initialsForName
import com.intj.layover.ui.theme.LayoverTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "LayoverBLE"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
    }

    private lateinit var runtime: LayoverAppRuntime
    private val pendingDeepLink = MutableStateFlow<NotificationDeepLink?>(null)

    private fun handleIncomingNotificationIntent(intent: Intent?) {
        val deepLink = LayoverNotificationManager.parseDeepLink(intent)
        if (deepLink != null) {
            LayoverFileLogger.log("MainActivity received notification deep link: $deepLink")
            pendingDeepLink.value = deepLink
            LayoverNotificationManager.clearDeepLinkExtras(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingNotificationIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setOnExitAnimationListener { splashView ->
            splashView.remove()
        }
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        runtime = LayoverAppRuntime.getInstance(applicationContext)
        LayoverNotificationManager.ensureChannelsCreated(applicationContext)
        handleIncomingNotificationIntent(intent)

        LayoverFileLogger.log("MainActivity.onCreate: attached to LayoverAppRuntime.")
        Log.d(TAG, "MainActivity.onCreate called; savedInstanceStateNull=${savedInstanceState == null}")

        val prefs = runtime.prefs
        val initialPermissionFlowDone = prefs.getBoolean("initial_permission_flow_done", false)

        setContent {
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            val bleStatus by runtime.bleStateController.statusState.collectAsState()
            val uiState by runtime.uiState.collectAsState()
            val hasSetUsername by runtime.hasSetUsername.collectAsState()
            val isRuntimeReady by runtime.isRuntimeReady.collectAsState()
            val incomingRequestPeer by runtime.incomingRequestPeer.collectAsState()
            val acceptedRequestPeer by runtime.acceptedRequestPeer.collectAsState()
            val deepLink by pendingDeepLink.collectAsState()

            var hasBlePermissions by remember {
                mutableStateOf(BlePermissionHelper.hasAllRequiredPermissions(context))
            }
            var hasCompletedInitialPermissionFlow by remember {
                mutableStateOf(initialPermissionFlowDone)
            }
            var showUsernameAfterPermissions by remember {
                mutableStateOf(false)
            }
            var blePromptLaunchedInSession by remember {
                mutableStateOf(false)
            }
            var isBlePermissionPromptVisible by remember {
                mutableStateOf(false)
            }
            var notificationPromptLaunchedInSession by remember {
                mutableStateOf(false)
            }

            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                LayoverFileLogger.log("POST_NOTIFICATIONS permission result: granted=$granted")
                Log.d(TAG, "POST_NOTIFICATIONS permission result: granted=$granted")
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permsMap ->
                isBlePermissionPromptVisible = false
                val actualBleGranted = BlePermissionHelper.hasAllRequiredPermissions(context)
                val allBleGranted = if (permsMap.isEmpty()) actualBleGranted else (permsMap.values.all { it } || actualBleGranted)
                runtime.bleStateController.refreshPermissionAndAdapterState()
                hasBlePermissions = allBleGranted

                LayoverFileLogger.log(
                    "BLE permission result: mapEmpty=${permsMap.isEmpty()}, actualBleGranted=$actualBleGranted, " +
                        "allBleGranted=$allBleGranted, hasBlePermissions=$hasBlePermissions, details=$permsMap"
                )
                Log.d(TAG, "BLE permission result: allBleGranted=$allBleGranted, details=$permsMap")

                if (permsMap.isEmpty() && !actualBleGranted) {
                    LayoverFileLogger.log("Ignoring empty BLE permission map while actualBleGranted=false (activity recreate during prompt).")
                    return@rememberLauncherForActivityResult
                }

                hasCompletedInitialPermissionFlow = true
                prefs.edit()
                    .putBoolean("initial_permission_flow_done", true)
                    .putBoolean("ble_permission_prompt_open", false)
                    .apply()

                if (allBleGranted) {
                    prefs.edit().putBoolean("ble_permission_denied_or_dismissed", false).apply()
                    Log.d(TAG, "BLE permissions granted via system dialog.")
                    BleProximityService.syncServiceState(context)
                    runtime.startDiscoveryIfEligible()
                } else {
                    prefs.edit().putBoolean("ble_permission_denied_or_dismissed", true).apply()
                    Log.w(TAG, "BLE permissions denied or dismissed by user; showing persistent card on People Around.")
                }

                if (!hasSetUsername) {
                    showUsernameAfterPermissions = true
                }
            }

            // Keep missing-permission transport callback non-looping: rely on first-launch flow + persistent card in PeopleAroundScreen
            runtime.bleTransport.onMissingPermissions = {
                val bleGrantedNow = BlePermissionHelper.hasAllRequiredPermissions(context)
                hasBlePermissions = bleGrantedNow
                LayoverFileLogger.log("Transport reported missing BLE permissions (bleGrantedNow=$bleGrantedNow); avoiding automatic popup loop.")
                Log.d(TAG, "Transport reported missing BLE permissions; avoiding automatic popup loop.")
            }

            // Request POST_NOTIFICATIONS on Android 13+ once initial BLE permission flow and username setup are complete
            LaunchedEffect(hasCompletedInitialPermissionFlow, hasSetUsername, isBlePermissionPromptVisible) {
                if (hasCompletedInitialPermissionFlow && hasSetUsername && !isBlePermissionPromptVisible) {
                    val notifPerm = BlePermissionHelper.getNotificationPermissionOrNull()
                    val alreadyRequested = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
                    if (notifPerm != null &&
                        !BlePermissionHelper.hasNotificationPermission(context) &&
                        !alreadyRequested &&
                        !notificationPromptLaunchedInSession
                    ) {
                        notificationPromptLaunchedInSession = true
                        prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
                        LayoverFileLogger.log("Requesting POST_NOTIFICATIONS runtime permission on Android 13+.")
                        notificationPermissionLauncher.launch(notifPerm)
                    }
                }
            }

            LaunchedEffect(uiState.isScanning, hasCompletedInitialPermissionFlow, hasBlePermissions, hasSetUsername) {
                if (!hasCompletedInitialPermissionFlow) {
                    LayoverFileLogger.log(
                        "Skipping discovery effect while initial permission flow active: done=$hasCompletedInitialPermissionFlow"
                    )
                    return@LaunchedEffect
                }
                if (uiState.isScanning) {
                    val bleGrantedNow = BlePermissionHelper.hasAllRequiredPermissions(context)
                    hasBlePermissions = bleGrantedNow
                    if (!bleGrantedNow) {
                        LayoverFileLogger.log("BLE permissions missing after initial flow; keeping discovery idle without looping popups.")
                        Log.d(TAG, "BLE permissions missing; skipping automatic popup loop.")
                    } else {
                        LayoverFileLogger.log("Discovery active; syncing BLE service and transport (visible=${uiState.userProfile.isVisibleToNearby && hasSetUsername}).")
                        BleProximityService.syncServiceState(context)
                        runtime.startDiscoveryIfEligible()
                    }
                } else {
                    LayoverFileLogger.log("isScanning=false; stopping BLE discovery and background service.")
                    runtime.bleTransport.stopDiscovery()
                    BleProximityService.stop(context)
                }
            }

            LaunchedEffect(bleStatus.lastError) {
                bleStatus.lastError?.let { err ->
                    LayoverFileLogger.log("bleStatus.lastError observed: $err")
                    runtime.bleTransport.diagnosticsTracker.updateLastErrorMessage(err)
                    if (uiState.isScanning && hasBlePermissions) {
                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            runtime.setAppInForeground(false)
                            if (runtime.isBackgroundListeningEligible()) {
                                LayoverFileLogger.log("Lifecycle ON_STOP: keeping BLE background service active for notifications.")
                            } else {
                                LayoverFileLogger.log("Lifecycle ON_STOP: not eligible for background listening; stopping BLE discovery.")
                                runtime.bleTransport.stopDiscovery()
                                BleProximityService.stop(context)
                            }
                        }
                        Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                            runtime.setAppInForeground(true)
                            runtime.bleStateController.refreshPermissionAndAdapterState()
                            val bleGrantedNow = BlePermissionHelper.hasAllRequiredPermissions(context)
                            hasBlePermissions = bleGrantedNow

                            LayoverFileLogger.log(
                                "Lifecycle $event: bleGranted=$bleGrantedNow, " +
                                    "initialFlowDone=$hasCompletedInitialPermissionFlow, usernameSet=$hasSetUsername, " +
                                    "blePromptLaunched=$blePromptLaunchedInSession"
                            )

                            if (!hasCompletedInitialPermissionFlow && !blePromptLaunchedInSession) {
                                blePromptLaunchedInSession = true
                                isBlePermissionPromptVisible = true
                                val blePerms = BlePermissionHelper.getRequiredPermissions()
                                prefs.edit().putBoolean("ble_permission_prompt_open", true).apply()
                                LayoverFileLogger.log("Starting first-launch BLE permission prompt (${blePerms.joinToString()})")
                                Log.d(TAG, "Lifecycle $event: Launching first-launch BLE permission prompt once.")
                                permissionLauncher.launch(blePerms)
                            } else if (bleGrantedNow) {
                                BleProximityService.syncServiceState(context)
                                runtime.startDiscoveryIfEligible()
                            }
                        }
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            LayoverTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val initialSkipSplash = remember { deepLink != null }
                val isOnSplash = (!initialSkipSplash && currentRoute == null) ||
                    currentRoute == LayoverRoute.Splash.route

                LaunchedEffect(navBackStackEntry) {
                    val route = navBackStackEntry?.destination?.route
                    if (route == LayoverRoute.Chat.ROUTE_PATTERN) {
                        val activePeer = navBackStackEntry?.arguments?.getString("peerId")
                        runtime.setActiveChatPeerId(activePeer)
                    } else {
                        runtime.setActiveChatPeerId(null)
                    }
                }

                LaunchedEffect(deepLink, hasSetUsername) {
                    val link = deepLink ?: return@LaunchedEffect
                    if (!hasSetUsername) return@LaunchedEffect

                    if (navController.currentDestination?.route == LayoverRoute.Splash.route) {
                        navController.navigate(LayoverRoute.PeopleAround.route) {
                            popUpTo(LayoverRoute.Splash.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }

                    when (link) {
                        is NotificationDeepLink.OpenChat -> {
                            val aliases = PeerAliasRegistry.resolveAliases(link.peerId) + link.peerId
                            val matched = uiState.nearbyPeers.find { p ->
                                aliases.any { a ->
                                    p.id.equals(a, ignoreCase = true) ||
                                        p.handle.equals(a, ignoreCase = true) ||
                                        p.displayName.equals(a, ignoreCase = true)
                                }
                            }
                            val targetRouteId = matched?.id?.takeIf { it.isNotBlank() } ?: link.peerId
                            LayoverNotificationManager.cancelMessageNotification(context, targetRouteId)
                            navController.navigate(LayoverRoute.Chat.createRoute(targetRouteId)) {
                                popUpTo(LayoverRoute.PeopleAround.route) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                        is NotificationDeepLink.OpenConnectionRequest -> {
                            LayoverNotificationManager.cancelConnectionRequestNotification(context, link.peerId)
                            navController.navigate(LayoverRoute.PeopleAround.route) {
                                popUpTo(LayoverRoute.PeopleAround.route) { inclusive = false }
                                launchSingleTop = true
                            }
                            runtime.surfacePendingConnectionRequestFromNotification(link.peerId, link.displayName)
                        }
                    }
                    pendingDeepLink.value = null
                }

                val shouldShowUsernameDialog = !isOnSplash &&
                    !hasSetUsername &&
                    (hasCompletedInitialPermissionFlow || showUsernameAfterPermissions)

                if (shouldShowUsernameDialog) {
                    LayoverFileLogger.log(
                        "Showing OneTimeUsernameDialog (username_set=false, initialFlowDone=$hasCompletedInitialPermissionFlow)"
                    )
                    OneTimeUsernameDialog(
                        onConfirmUsername = { chosenName, chosenGender, chosenAge ->
                            runtime.confirmInitialProfile(chosenName, chosenGender, chosenAge)
                            showUsernameAfterPermissions = false
                        }
                    )
                }

                incomingRequestPeer?.let { (peerId, displayName) ->
                    val colors = LayoverTheme.colors
                    val aliases = PeerAliasRegistry.resolveAliases(peerId) +
                        PeerAliasRegistry.resolveAliases(displayName) +
                        listOfNotNull(peerId.takeIf { it.isNotBlank() }, displayName.takeIf { it.isNotBlank() })
                    val matchingPeers = uiState.nearbyPeers.filter { p ->
                        aliases.any { a ->
                            p.id.equals(a, ignoreCase = true) ||
                                p.handle.equals(a, ignoreCase = true) ||
                                (p.displayName.isNotBlank() && p.displayName.equals(a, ignoreCase = true))
                        }
                    }
                    val matchedPeer = matchingPeers.firstOrNull { it.avatar.avatarBase64OrBytes?.isNotEmpty() == true }
                        ?: matchingPeers.firstOrNull()
                    val resolvedName = matchedPeer?.displayName?.takeIf { it.isNotBlank() }
                        ?: displayName.ifBlank { "Layover Peer" }
                    val resolvedAge = matchedPeer?.age ?: matchingPeers.firstNotNullOfOrNull { it.age }
                    val resolvedGender = matchedPeer?.gender ?: matchingPeers.firstNotNullOfOrNull { it.gender }
                    val formattedRequesterName = formatPeerNameWithBadge(
                        displayName = resolvedName,
                        age = resolvedAge,
                        gender = resolvedGender
                    )
                    val avatarBytes = matchingPeers
                        .firstNotNullOfOrNull { it.avatar.avatarBase64OrBytes?.takeIf { b -> b.isNotEmpty() } }
                    val avatarBitmap = remember(avatarBytes) {
                        avatarBytes?.let { bytes ->
                            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
                        }
                    }
                    val initials = remember(resolvedName) { initialsForName(resolvedName) }
                    var showFullScreenRequesterPhoto by remember(peerId) { mutableStateOf(false) }

                    AlertDialog(
                        onDismissRequest = {
                            LayoverFileLogger.log("Incoming connection request dismissed for peerId=$peerId")
                            showFullScreenRequesterPhoto = false
                            runtime.dismissIncomingRequest()
                        },
                        containerColor = colors.cardSurface,
                        title = {
                            Text(
                                text = "Connection Request",
                                color = colors.lightText,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(88.dp)
                                        .clip(CircleShape)
                                        .background(colors.incomingBubble)
                                        .border(2.dp, colors.cyanPrimary, CircleShape)
                                        .then(
                                            if (avatarBitmap != null) {
                                                Modifier.clickable { showFullScreenRequesterPhoto = true }
                                            } else {
                                                Modifier
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (avatarBitmap != null) {
                                        Image(
                                            bitmap = avatarBitmap,
                                            contentDescription = "$formattedRequesterName profile photo",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape)
                                        )
                                    } else {
                                        Text(
                                            text = initials,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.cyanPrimary,
                                            fontSize = 28.sp
                                        )
                                    }
                                }

                                if (avatarBitmap != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Tap photo to enlarge",
                                        color = colors.mutedText,
                                        fontSize = 11.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = formattedRequesterName,
                                    color = colors.lightText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = "would like to connect with you.",
                                    color = colors.mutedText,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showFullScreenRequesterPhoto = false
                                PeerAliasRegistry.linkAliases(
                                    listOfNotNull(
                                        peerId,
                                        matchedPeer?.id,
                                        matchedPeer?.handle?.ifEmpty { null },
                                        resolvedName
                                    )
                                )
                                runtime.acceptIncomingRequest(peerId, resolvedName)
                                val routePeerId = listOfNotNull(
                                    peerId.takeIf { it.startsWith("dev_", ignoreCase = true) },
                                    matchedPeer?.id?.takeIf { it.startsWith("dev_", ignoreCase = true) },
                                    matchedPeer?.id?.takeIf { it.isNotBlank() },
                                    peerId
                                ).first()
                                navController.navigate(LayoverRoute.Chat.createRoute(routePeerId))
                            }) {
                                Text("Accept")
                            }
                        },
                        dismissButton = {
                            Button(onClick = {
                                showFullScreenRequesterPhoto = false
                                runtime.declineIncomingRequest(peerId)
                            }) {
                                Text("Decline")
                            }
                        }
                    )

                    if (showFullScreenRequesterPhoto && avatarBitmap != null) {
                        Dialog(
                            onDismissRequest = { showFullScreenRequesterPhoto = false },
                            properties = DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            BackHandler { showFullScreenRequesterPhoto = false }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.94f))
                                    .clickable { showFullScreenRequesterPhoto = false }
                            ) {
                                Image(
                                    bitmap = avatarBitmap,
                                    contentDescription = "$formattedRequesterName full-screen photo",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp)
                                )

                                Text(
                                    text = formattedRequesterName,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 18.sp,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .statusBarsPadding()
                                        .padding(horizontal = 20.dp, vertical = 16.dp)
                                )

                                IconButton(
                                    onClick = { showFullScreenRequesterPhoto = false },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .statusBarsPadding()
                                        .padding(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close photo",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                acceptedRequestPeer?.let { peerId ->
                    val aliases = PeerAliasRegistry.resolveAliases(peerId) + peerId
                    val peer = uiState.nearbyPeers.find { p ->
                        aliases.any { a -> p.id.equals(a, ignoreCase = true) || p.handle.equals(a, ignoreCase = true) }
                    }
                    val name = peer?.displayName ?: "Peer"
                    AlertDialog(
                        onDismissRequest = { runtime.clearAcceptedRequestDialog() },
                        title = { Text("Request Accepted") },
                        text = { Text("$name accepted your connection request!") },
                        confirmButton = {
                            Button(onClick = {
                                LayoverFileLogger.log("Navigating to chat with accepted peerId=$peerId (routeId=${peer?.id ?: peerId})")
                                runtime.clearAcceptedRequestDialog()
                                val targetRouteId = peer?.id ?: peerId
                                navController.navigate(LayoverRoute.Chat.createRoute(targetRouteId))
                            }) {
                                Text("Message")
                            }
                        },
                        dismissButton = {
                            Button(onClick = {
                                runtime.dismissAcceptedRequest(peerId)
                            }) {
                                Text("Close")
                            }
                        }
                    )
                }

                LayoverNavGraph(
                    uiState = uiState,
                    onUpdateUiState = { newState ->
                        runtime.updateUiState(newState)
                    },
                    modifier = Modifier.fillMaxSize(),
                    navController = navController,
                    skipSplash = initialSkipSplash,
                    isRuntimeReady = isRuntimeReady
                )
            }
        }
    }

    override fun onDestroy() {
        LayoverFileLogger.log("MainActivity.onDestroy called (serviceRunning=${BleProximityService.isRunning}, bgEligible=${runtime.isBackgroundListeningEligible()}).")
        Log.d(TAG, "MainActivity.onDestroy called.")
        runtime.setAppInForeground(false)
        if (!runtime.isBackgroundListeningEligible() && !BleProximityService.isRunning) {
            runtime.bleTransport.stopDiscovery()
        }
        super.onDestroy()
    }
}
