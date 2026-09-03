package io.github.meko123456.ridetogether.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.android.crash.CrashMonitor
import io.github.meko123456.ridetogether.android.crash.CrashSensors
import io.github.meko123456.ridetogether.android.MainActivity
import io.github.meko123456.ridetogether.location.LocationConditions
import io.github.meko123456.ridetogether.location.LocationPolicy
import io.github.meko123456.ridetogether.model.LatLng
import io.github.meko123456.ridetogether.model.RoomState
import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * Collects this rider's position for as long as a ride is running, and not one second longer.
 *
 * The two rules that keep the app out of Google's restricted-permission process are structural
 * here rather than aspirational (see `docs/PLAY_LOCATION_COMPLIANCE.md`):
 *
 * 1. **It is only ever started from a visible activity.** Android 12+ refuses a background start
 *    for this service type anyway, so the app does not rely on policy alone — but the call site
 *    is the "Start ride" tap, which is a user action with the app on screen.
 * 2. **It stops when the ride does.** [ACTION_ROOM_STATE] is sent on every room-state change, and
 *    a state that does not share location stops the service immediately. Not on a timer, and not
 *    when the process happens to die.
 *
 * The interval comes from [LocationPolicy], which is unit-tested; nothing about how often to ask
 * is decided here.
 */
class RideLocationService : Service() {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }

    /**
     * Crash detection is armed by exactly the same thing as location collection: a ride being in
     * progress. Hosting the sensors here rather than in their own service means the two can never
     * disagree about whether a ride is happening.
     */
    private val sensors by lazy {
        CrashSensors(this) {
            val fix = RideLocation.own.value
            fix?.location to fix?.speedMps?.toDouble()
        }
    }
    private var sensorsRunning = false
    private var roomState: RoomState = RoomState.RIDING
    private var currentInterval: Duration? = null
    private var lastPublished: LatLng? = null
    private var lastPublishedAt: Instant? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onFix)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_ROOM_STATE -> {
                roomState = intent.getStringExtra(EXTRA_ROOM_STATE)
                    ?.let { runCatching { RoomState.valueOf(it) }.getOrNull() }
                    ?: roomState
                if (!roomState.sharesLocation) {
                    stopCollecting("room state is $roomState")
                    return START_NOT_STICKY
                }
                if (!hasPermission()) {
                    stopCollecting("location permission is not granted")
                    return START_NOT_STICKY
                }
                val started = runCatching { startForeground(NOTIFICATION_ID, notification()) }
                if (started.isFailure) {
                    Log.e(TAG, "could not go foreground", started.exceptionOrNull())
                    stopCollecting("the service could not start in the foreground")
                    return START_NOT_STICKY
                }
                RideLocation.setRunning(true)
                startSensors()
                requestUpdates()
            }

            ACTION_STOP -> stopCollecting("the rider left or ended the ride")

            else -> stopCollecting("started with no action")
        }
        return START_NOT_STICKY
    }

    /**
     * Asks the provider for the interval the policy wants, and only re-asks when it changes —
     * every call to requestLocationUpdates resets the provider's own batching, so re-requesting on
     * each fix would quietly cost battery.
     */
    @SuppressLint("MissingPermission") // checked in onStartCommand and again before each request
    private fun requestUpdates(speedMps: Double? = null) {
        val wanted = LocationPolicy.intervalFor(
            LocationConditions(
                roomState = roomState,
                speedMps = speedMps,
                batteryPercent = batteryPercent(),
            ),
        )
        if (wanted == null) {
            stopCollecting("the policy says not to collect in state $roomState")
            return
        }
        if (wanted == currentInterval) return
        if (!hasPermission()) {
            stopCollecting("location permission was revoked")
            return
        }

        currentInterval = wanted
        RideLocation.setInterval(wanted)
        val millis = wanted.inWholeMilliseconds
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, millis)
            // A floor, not a target: the provider may deliver sooner, and shouldPublish decides
            // what is worth keeping.
            .setMinUpdateIntervalMillis(millis / 2)
            .setWaitForAccurateLocation(false)
            .build()
        client.removeLocationUpdates(callback)
        client.requestLocationUpdates(request, callback, mainLooper)
        Log.i(TAG, "requesting updates every $wanted")
    }

    private fun onFix(location: Location) {
        val at = Instant.fromEpochMilliseconds(location.time)
        val here = LatLng(location.latitude, location.longitude)
        val speed = if (location.hasSpeed()) location.speed.toDouble() else null

        // The provider delivers more often than asked; the policy decides what counts.
        val interval = currentInterval
        if (interval != null &&
            !LocationPolicy.shouldPublish(lastPublished, lastPublishedAt, here, at, interval)
        ) {
            return
        }
        lastPublished = here
        lastPublishedAt = at
        RideLocation.publish(
            RiderSample(
                riderId = SELF_ID,
                location = here,
                speedMps = speed?.toFloat(),
                at = at,
                reportingInterval = interval,
            ),
        )
        // Speed changes the interval, so re-evaluate now that this fix has told us the speed.
        requestUpdates(speedMps = speed)
    }

    private fun startSensors() {
        if (sensorsRunning) return
        if (!sensors.available) {
            Log.w(TAG, "no motion sensors; crash detection is off on this device")
            return
        }
        sensors.start()
        sensorsRunning = true
    }

    private fun stopSensors() {
        if (!sensorsRunning) return
        sensors.stop()
        sensorsRunning = false
        // A ride that is over cannot be crashed out of.
        CrashMonitor.reset()
    }

    private fun stopCollecting(why: String) {
        Log.i(TAG, "stopping: $why")
        stopSensors()
        runCatching { client.removeLocationUpdates(callback) }
        currentInterval = null
        lastPublished = null
        lastPublishedAt = null
        RideLocation.setInterval(null)
        RideLocation.setRunning(false)
        stopSelf()
    }

    override fun onDestroy() {
        stopSensors()
        runCatching { client.removeLocationUpdates(callback) }
        RideLocation.setInterval(null)
        RideLocation.setRunning(false)
        super.onDestroy()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun batteryPercent(): Int? {
        val manager = getSystemService(BatteryManager::class.java) ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level.takeIf { it in 0..100 }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ride in progress", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Shown for as long as your location is being shared with the group."
                },
        )
    }

    /**
     * The notification is not decoration: it is the visible half of the promise that collection
     * only happens during a ride, and the reason the while-in-use grant is enough.
     */
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sharing your location with the ride")
            .setContentText("Stops as soon as the ride ends.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "RideLocation"
        private const val CHANNEL_ID = "ride-location"
        private const val NOTIFICATION_ID = 7

        /** Stands in for the signed-in rider until accounts land. Matches RideViewModel. */
        const val SELF_ID = "me"

        const val ACTION_START = "io.github.meko123456.ridetogether.LOCATION_START"
        const val ACTION_ROOM_STATE = "io.github.meko123456.ridetogether.LOCATION_ROOM_STATE"
        const val ACTION_STOP = "io.github.meko123456.ridetogether.LOCATION_STOP"
        private const val EXTRA_ROOM_STATE = "roomState"

        /**
         * Starts or updates collection. Must be called from a visible activity — see the class
         * docs; Android will refuse it otherwise, and the app depends on that being true.
         */
        fun syncWith(context: Context, state: RoomState) {
            val intent = Intent(context, RideLocationService::class.java).apply {
                action = if (state.sharesLocation) ACTION_ROOM_STATE else ACTION_STOP
                putExtra(EXTRA_ROOM_STATE, state.name)
            }
            if (state.sharesLocation) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                // Nothing to keep alive: a plain start is enough to deliver the stop.
                runCatching { context.startService(intent) }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, RideLocationService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
