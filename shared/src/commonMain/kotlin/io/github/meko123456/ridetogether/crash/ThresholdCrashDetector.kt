package io.github.meko123456.ridetogether.crash

import io.github.meko123456.ridetogether.model.LatLng
import kotlinx.datetime.Instant
import kotlin.math.abs

/**
 * The default detector: an impact spike, **then** a large orientation change, **then** stillness —
 * all three, in that order, within seconds of each other, and only while the rider was actually
 * riding.
 *
 * ## Why all three, in order
 *
 * Each condition on its own has a boring everyday explanation:
 *
 * | Signal alone | What it usually is |
 * |---|---|
 * | Acceleration spike | a pothole, a kerb, the phone dropped onto tarmac |
 * | Orientation change | the phone taken out of a pocket, or the bike put on its side stand |
 * | Stillness | a red light |
 *
 * Requiring the sequence is what makes it mean something: something hit hard, the bike ended up
 * at an angle it is never ridden at, and then nothing moved. That combination has few innocent
 * explanations — and the ones it has are covered by the countdown, which is the real safety
 * valve: **the detector is allowed to be wrong, as long as being wrong costs one tap.**
 *
 * The order matters as much as the conjunction. A phone lifted out of a pocket at a fuel stop
 * produces tilt, then stillness, then possibly a knock as it is put down — the same three signals
 * in the wrong sequence. Only an impact opens the window.
 *
 * ## What it deliberately does not do
 *
 * It does not try to detect a low-side at walking pace, or a rider who stops safely and then
 * collapses. Those need signals a phone in a pocket does not have. A detector that claims to
 * catch everything gets switched off after its second false alarm, and then it catches nothing.
 */
class ThresholdCrashDetector(private val config: CrashConfig = CrashConfig()) : CrashDetector {

    private sealed interface Phase {
        data object Idle : Phase

        /** An impact happened; waiting to see whether tilt and stillness follow. */
        data class ImpactSuspected(
            val impactAt: Instant,
            val tiltBaseline: Double,
            val stillSince: Instant?,
        ) : Phase

        data class CountingDown(val impactAt: Instant, val startedAt: Instant) : Phase

        data object Reported : Phase
    }

    private var phase: Phase = Phase.Idle

    /** Rolling tilt while riding, so "changed by 45°" is measured against how the phone sits. */
    private var tiltBaseline: Double? = null

    /** Last moment the rider was moving at riding speed. Null means never, or long ago. */
    private var lastRidingAt: Instant? = null

    override val state: CrashState
        get() = when (phase) {
            Phase.Idle -> CrashState.IDLE
            is Phase.ImpactSuspected -> CrashState.IMPACT_SUSPECTED
            is Phase.CountingDown -> CrashState.COUNTING_DOWN
            Phase.Reported -> CrashState.CRASH_REPORTED
        }

    override fun onMotion(sample: MotionSample, location: LatLng?): CrashSignal? {
        val speed = sample.speedMps
        val riding = speed != null && speed >= config.armingSpeedMps
        if (riding) lastRidingAt = sample.at

        return when (val current = phase) {
            Phase.Idle -> {
                // The baseline only tracks while nothing is suspected, so an impact cannot drag
                // the reference along with it and hide its own tilt change.
                tiltBaseline = blend(tiltBaseline, sample.tiltDegrees)
                if (isImpact(sample)) openWindow(sample)
                null
            }
            is Phase.ImpactSuspected -> evaluateSuspicion(current, sample)
            is Phase.CountingDown -> evaluateCountdown(current, sample, location, riding)
            // A reported crash is sticky. The group has been told; repeating it, or quietly
            // withdrawing it because the phone went still, would both be wrong. (An earlier
            // early-return here said the same thing twice — mutation testing found the copy was
            // unreachable, so this is now the only place that decides it.)
            Phase.Reported -> null
        }
    }

    override fun cancel(at: Instant): CrashSignal? {
        if (phase !is Phase.CountingDown) return null
        phase = Phase.Idle
        return CrashSignal.CountdownCancelled(at, CancelReason.RIDER)
    }

    override fun reset() {
        phase = Phase.Idle
        tiltBaseline = null
        lastRidingAt = null
    }

    /** An impact only counts if the rider was riding when, or moments before, it happened. */
    private fun isImpact(sample: MotionSample): Boolean {
        if (sample.accelerationMps2 < config.impactAccelerationMps2) return false
        val ridingRecently = lastRidingAt?.let { sample.at - it <= config.armingWindow } == true
        return ridingRecently
    }

    private fun openWindow(sample: MotionSample) {
        phase = Phase.ImpactSuspected(
            impactAt = sample.at,
            tiltBaseline = tiltBaseline ?: sample.tiltDegrees,
            stillSince = null,
        )
    }

    private fun evaluateSuspicion(current: Phase.ImpactSuspected, sample: MotionSample): CrashSignal? {
        // Riding away is the clearest possible "I'm fine": it needs a working rider and a
        // working bike. This is the pothole case, and it must exit before anything else.
        val speed = sample.speedMps
        if (speed != null && speed >= config.armingSpeedMps) {
            phase = Phase.Idle
            return null
        }
        if (sample.at - current.impactAt > config.confirmWindow) {
            // Whatever it was, the picture never completed. Silence is the correct output.
            phase = Phase.Idle
            return null
        }

        val stationary = speed != null && speed <= config.stationarySpeedMps
        val stillSince = if (stationary) current.stillSince ?: sample.at else null
        val stillLongEnough = stillSince != null && sample.at - stillSince >= config.stillnessRequired
        val tiltChanged = abs(sample.tiltDegrees - current.tiltBaseline) >= config.tiltChangeDegrees

        phase = current.copy(stillSince = stillSince)
        if (!(tiltChanged && stillLongEnough)) return null

        val startedAt = sample.at
        phase = Phase.CountingDown(impactAt = current.impactAt, startedAt = startedAt)
        return CrashSignal.CountdownStarted(at = startedAt, expiresAt = startedAt + config.countdown)
    }

    private fun evaluateCountdown(
        current: Phase.CountingDown,
        sample: MotionSample,
        location: LatLng?,
        riding: Boolean,
    ): CrashSignal? {
        if (riding) {
            phase = Phase.Idle
            return CrashSignal.CountdownCancelled(sample.at, CancelReason.RIDING_AGAIN)
        }
        if (sample.at - current.startedAt < config.countdown) return null
        phase = Phase.Reported
        return CrashSignal.CrashConfirmed(
            at = sample.at,
            location = location,
            impactAt = current.impactAt,
        )
    }

    /**
     * Exponential blend towards the newest tilt reading. A plain "last value" baseline would move
     * with the phone as it is jostled and make a real 90° swing look like several small ones.
     */
    private fun blend(previous: Double?, sample: Double): Double =
        if (previous == null) sample else previous * (1 - BASELINE_WEIGHT) + sample * BASELINE_WEIGHT

    private companion object {
        const val BASELINE_WEIGHT = 0.1
    }
}
