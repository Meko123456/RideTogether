package io.github.meko123456.ridetogether.android.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.meko123456.ridetogether.android.RiderIdentity
import io.github.meko123456.ridetogether.android.backend.PreferencesAuthSessionStore
import io.github.meko123456.ridetogether.android.backend.RideBackends
import io.github.meko123456.ridetogether.android.backend.RiderNameStore
import io.github.meko123456.ridetogether.realtime.InMemoryRealtimeClient
import io.github.meko123456.ridetogether.realtime.RealtimeClient
import io.github.meko123456.ridetogether.realtime.rtdb.FirebaseBackend
import kotlinx.coroutines.launch

/**
 * Gets the app as far as a [RealtimeClient] to ride through, before anything else is shown,
 * because every screen after this one speaks as the rider that client writes as.
 *
 * On the in-memory backend that is immediate. On Firebase it needs two things. One is a name,
 * asked for once, which is what the other riders will see. The other is an identity: signed in
 * now when there is a connection, as last time when there is not. Only an install that has never
 * signed in, opened with no connection, has to wait, because a new identity is the server's to give.
 */
class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        data object Connecting : State

        /** A shared backend, and the rider has not said what to call them. */
        data object NeedsName : State

        /** Never signed in, and no connection to do it with. Nothing to fall back on but trying again. */
        data object Unreachable : State

        data class Ready(val client: RealtimeClient) : State
    }

    var state by mutableStateOf<State>(State.Connecting)
        private set

    private val names = RiderNameStore(application)
    private val firebase = RideBackends.firebase?.let { FirebaseBackend(it, PreferencesAuthSessionStore(application)) }

    init {
        connect()
    }

    fun submitName(name: String) {
        names.name = name
        connect()
    }

    fun retry() = connect()

    private fun connect() {
        val backend = firebase ?: return ready(InMemoryRealtimeClient(selfId = RiderIdentity.ON_THIS_PHONE))
        val name = names.name ?: run { state = State.NeedsName; return }
        state = State.Connecting
        viewModelScope.launch {
            val riderId = backend.riderId()
            if (riderId == null) state = State.Unreachable else ready(backend.client(riderId, name))
        }
    }

    private fun ready(client: RealtimeClient) {
        // Before the state changes, so nothing can start a ride, and with it the location
        // service, while that service would still publish as the wrong rider.
        RiderIdentity.becomes(client.selfId)
        state = State.Ready(client)
    }
}
