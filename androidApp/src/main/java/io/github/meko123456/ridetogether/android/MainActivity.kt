package io.github.meko123456.ridetogether.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.meko123456.ridetogether.android.ui.HomeScreen
import io.github.meko123456.ridetogether.android.ui.RideViewModel
import io.github.meko123456.ridetogether.android.ui.RoomScreen
import io.github.meko123456.ridetogether.android.ui.theme.RideTogetherTheme
import io.github.meko123456.ridetogether.room.JoinPolicy

class MainActivity : ComponentActivity() {

    /** A code carried in by a `ridetogether://join/<CODE>` invite, waiting to be handed to the UI. */
    private var pendingCode by mutableStateOf<String?>(null)

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
                        vm.onCodeInputChange(it)
                        pendingCode = null
                    }
                }

                LaunchedEffect(vm.notice) {
                    vm.notice?.let {
                        snackbarHostState.showSnackbar(it)
                        vm.consumeNotice()
                    }
                }

                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { insets ->
                    val room = vm.room
                    if (room == null) {
                        HomeScreen(
                            rideName = vm.rideName,
                            codeInput = vm.codeInput,
                            resolvedCode = vm.resolvedCode,
                            onRideNameChange = vm::onRideNameChange,
                            onCodeInputChange = vm::onCodeInputChange,
                            onCreateRide = vm::createRide,
                            onJoinRide = vm::joinByCode,
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
                            modifier = Modifier.padding(insets),
                        )
                    }
                }
            }
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
