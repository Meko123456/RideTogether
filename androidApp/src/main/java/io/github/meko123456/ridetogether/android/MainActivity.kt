package io.github.meko123456.ridetogether.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.android.location.RideLocation
import io.github.meko123456.ridetogether.android.location.RideLocationService
import io.github.meko123456.ridetogether.android.ui.HomeScreen
import io.github.meko123456.ridetogether.android.ui.LocationDisclosure
import io.github.meko123456.ridetogether.android.ui.RideViewModel
import io.github.meko123456.ridetogether.android.ui.RoomScreen
import io.github.meko123456.ridetogether.android.ui.theme.RideTogetherTheme
import io.github.meko123456.ridetogether.room.JoinPolicy

class MainActivity : ComponentActivity() {

    /** A code carried in by a `ridetogether://join/<CODE>` invite, waiting to be handed to the UI. */
    private var pendingCode by mutableStateOf<String?>(null)

    /** Bumped whenever a permission result comes back, so the composition re-reads the grant. */
    private var permissionTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingCode = codeFrom(intent)

        setContent {
            RideTogetherTheme {
                val vm: RideViewModel = viewModel()
                val snackbarHostState = remember { SnackbarHostState() }

                // An invite prefills the code field rather than joining silently: the rider
                // should see which ride they are about to enter before they are in it.
                LaunchedEffect(pendingCode) {
                    pendingCode?.let {
                        vm.onInviteReceived(it)
                        pendingCode = null
                    }
                }

                LaunchedEffect(vm.notice) {
                    vm.notice?.let {
                        snackbarHostState.showSnackbar(it)
                        vm.consumeNotice()
                    }
                }

                val locationGranted = remember(permissionTick) { hasLocationPermission() }
                // Both permissions, because Android 12+ refuses a FINE-only request when the
                // rider picks Approximate — and then reports the outcome per permission, so
                // "approximate only" is distinguishable from a flat refusal.
                val requestLocation = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    permissionTick++
                    val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
                    val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                    when {
                        fine -> Unit
                        coarse -> vm.onApproximateLocationOnly()
                        else -> vm.onDisclosureDeclined()
                    }
                }

                if (vm.showLocationDisclosure) {
                    LocationDisclosure(
                        onContinue = {
                            vm.onDisclosureAccepted()
                            requestLocation.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                        onDismiss = vm::onDisclosureDeclined,
                    )
                }

                val room = vm.room
                val roomState = room?.state

                // The service follows the room state, and is started only from here — a
                // composition that is on screen. Android refuses a background start for a
                // location-typed service, and this app is built never to need one.
                LaunchedEffect(roomState, locationGranted) {
                    when {
                        roomState == null -> RideLocationService.stop(this@MainActivity)
                        !roomState.sharesLocation ->
                            RideLocationService.syncWith(this@MainActivity, roomState)
                        locationGranted ->
                            RideLocationService.syncWith(this@MainActivity, roomState)
                        vm.needsDisclosure(permissionGranted = false) -> vm.requestDisclosure()
                        // Asked once and refused: the ride runs, just without this rider's pin.
                        else -> Unit
                    }
                }

                val ownFix by RideLocation.own.collectAsState()
                val interval by RideLocation.interval.collectAsState()
                val collecting by RideLocation.running.collectAsState()

                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { insets ->
                    if (room == null) {
                        HomeScreen(
                            rideName = vm.rideName,
                            codeInput = vm.codeInput,
                            resolvedCode = vm.resolvedCode,
                            onRideNameChange = vm::onRideNameChange,
                            onCodeInputChange = vm::onCodeInputChange,
                            onCreateRide = vm::createRide,
                            onJoinRide = vm::joinByCode,
                            rides = vm.rides,
                            modifier = Modifier.padding(insets),
                        )
                    } else {
                        RoomScreen(
                            room = room,
                            onCommand = vm::send,
                            onToggleSweep = vm::toggleSweep,
                            onAddDemoRider = vm::addDemoRider,
                            onShare = ::shareInvite,
                            onBack = vm::leaveRoom,
                            locationLine = locationLine(collecting, locationGranted, ownFix, interval),
                            voiceLine = vm.voiceStatus(),
                            feed = vm.feed,
                            onSendMessage = vm::send,
                            onSimulateMessage = vm::simulateMessageFromAnother,
                            summary = vm.lastSummary,
                            onDismissSummary = vm::dismissSummary,
                            crashSignal = vm.crashSignal,
                            onCancelCrash = vm::cancelCrashCountdown,
                            onAcknowledgeCrash = vm::acknowledgeCrash,
                            onSimulateImpact = vm::simulateImpact,
                            modifier = Modifier.padding(insets),
                        )
                    }
                }
            }
        }
    }

    /**
     * Reached only because the activity is `singleTop`. Without it Android stacks a *second*
     * MainActivity for an incoming invite — a fresh ViewModel on top of the running one, so the
     * ride you were already in disappears behind a duplicate copy of the app. Found on a device;
     * a tapped invite has to bring the app you are using forward, not open another one.
     */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * One line describing what the phone is really doing, rather than what the room state implies
     * it should be. The two can disagree — permission refused, provider still warming up — and a
     * privacy claim a rider cannot check on screen is worth very little. It also has to show that
     * a refusal was taken for an answer.
     */
    private fun locationLine(
        collecting: Boolean,
        granted: Boolean,
        fix: RiderSample?,
        interval: kotlin.time.Duration?,
    ): String = when {
        !granted -> "Not sharing — location permission not granted. The ride works without it."
        !collecting -> "Not sharing — no ride is running."
        fix == null -> "Waiting for the first fix…"
        else -> {
            val speed = fix.speedMps?.let { " · ${(it * 3.6f).toInt()} km/h" } ?: ""
            val every = interval?.let { " · every ${it.inWholeSeconds}s" } ?: ""
            "%.5f, %.5f".format(fix.location.latitude, fix.location.longitude) + speed + every
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingCode = codeFrom(intent)
    }

    private fun codeFrom(intent: Intent?): String? =
        intent?.data?.toString()?.let(JoinPolicy::codeFromDeepLink)

    private fun shareInvite(link: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Join my ride: $link")
        }
        startActivity(Intent.createChooser(send, null))
    }
}
