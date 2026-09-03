package io.github.meko123456.ridetogether.android.crash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import io.github.meko123456.ridetogether.crash.MotionSample
import io.github.meko123456.ridetogether.model.LatLng
import kotlinx.datetime.Clock
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Turns the phone's motion sensors into the [MotionSample]s the detector expects.
 *
 * Three Android details that matter:
 *
 * - **`TYPE_LINEAR_ACCELERATION` where available, otherwise accelerometer minus gravity.** The
 *   detector's threshold is defined on acceleration with gravity already removed; feeding it raw
 *   accelerometer readings would mean a phone lying still reads 9.8 m/s² forever.
 * - **Tilt is an angle, not an axis reading.** What matters is how far the phone has swung from
 *   however it happened to be sitting, so the gravity vector is turned into a single angle from
 *   vertical, and the detector compares *changes* in it against its own baseline.
 * - **Samples are throttled.** The sensors deliver far faster than any of this needs, and a
 *   detector tick per sensor event would burn battery on a ride that lasts hours.
 */
class CrashSensors(
    context: Context,
    private val locationProvider: () -> Pair<LatLng?, Double?>,
) : SensorEventListener {

    private val manager = context.getSystemService(SensorManager::class.java)
    private val linear = manager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gravity = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)

    private var lastGravity = FloatArray(3)
    private var haveGravity = false
    private var lastAcceleration = 0.0
    private var lastFedAtMillis = 0L

    /** True when there is enough hardware to detect anything at all. */
    val available: Boolean get() = manager != null && (linear != null || accelerometer != null)

    fun start() {
        val m = manager ?: return
        // SENSOR_DELAY_GAME is ~20 ms, fast enough that a 4 g spike lasting a few tens of
        // milliseconds is not missed between samples. The throttle below decides what reaches
        // the detector.
        linear?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (linear == null) {
            accelerometer?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            Log.i(TAG, "no linear-acceleration sensor; deriving it from the accelerometer")
        }
        gravity?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        Log.i(TAG, "sensors started (linear=${linear != null} gravity=${gravity != null})")
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                lastGravity = event.values.copyOf()
                haveGravity = true
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                lastAcceleration = magnitude(event.values)
                maybeFeed()
            }

            Sensor.TYPE_ACCELEROMETER -> {
                // Fallback: subtract gravity so the value means the same thing as the dedicated
                // sensor's would. Without gravity to subtract, the best available guess is to
                // take the deviation from 1 g.
                lastAcceleration = if (haveGravity) {
                    magnitude(
                        floatArrayOf(
                            event.values[0] - lastGravity[0],
                            event.values[1] - lastGravity[1],
                            event.values[2] - lastGravity[2],
                        ),
                    )
                } else {
                    abs(magnitude(event.values) - SensorManager.GRAVITY_EARTH)
                }
                maybeFeed()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun maybeFeed() {
        val now = System.currentTimeMillis()
        if (now - lastFedAtMillis < THROTTLE_MILLIS) return
        lastFedAtMillis = now
        val (location, speed) = locationProvider()
        CrashMonitor.feed(
            MotionSample(
                at = Clock.System.now(),
                accelerationMps2 = lastAcceleration,
                tiltDegrees = tiltDegrees(),
                speedMps = speed,
            ),
            location,
        )
    }

    /**
     * Angle between the gravity vector and the phone's own "up" axis, so a phone in a jacket
     * pocket at some arbitrary angle still produces a stable number that only moves when the
     * phone does.
     */
    private fun tiltDegrees(): Double {
        if (!haveGravity) return 0.0
        val magnitude = magnitude(lastGravity)
        if (magnitude < 1e-3) return 0.0
        val cosine = (lastGravity[1] / magnitude).coerceIn(-1.0, 1.0)
        return acos(cosine) * 180.0 / kotlin.math.PI
    }

    private fun magnitude(values: FloatArray): Double {
        val x = values.getOrElse(0) { 0f }.toDouble()
        val y = values.getOrElse(1) { 0f }.toDouble()
        val z = values.getOrElse(2) { 0f }.toDouble()
        return sqrt(x * x + y * y + z * z)
    }

    private companion object {
        const val TAG = "CrashSensors"

        /** Five samples a second: plenty for windows measured in seconds, cheap over hours. */
        const val THROTTLE_MILLIS = 200L
    }
}
