package io.github.meko123456.ridetogether.android.ui

import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.announce.Announcement
import io.github.meko123456.ridetogether.crash.CrashSignal
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Instant

/**
 * The three things [RideViewModel] needs from the platform, as interfaces.
 *
 * They exist so the view model can be tested. That is not a theoretical benefit: the wiring in
 * this layer is where the bug lived that let a rider create a ride and then be refused permission
 * to start it, and the only reason it was found is that somebody ran the app. A view model that
 * needs a TextToSpeech engine, a foreground service and a sensor stack to construct cannot be
 * tested anywhere else.
 *
 * Kept deliberately small — each is the narrowest slice of its implementation that the view model
 * actually uses, rather than the whole class behind an interface.
 */

/** Speech, as the view model needs it. Implemented by `RideSpeaker`. */
interface Voice {
    fun speak(announcements: List<Announcement>)

    /** Stop mid-sentence: a ride that has ended should not still be being talked about. */
    fun stop()

    fun release()

    /** For the diagnostics line, so "nothing was said" has a visible reason. */
    fun status(): String
}

/** This phone's own position, as published by the location service. Implemented by `RideLocation`. */
interface OwnLocation {
    val own: StateFlow<RiderSample?>
}

/** The crash detector, as the view model drives it. Implemented by `CrashMonitor`. */
interface CrashDetection {
    val signal: StateFlow<CrashSignal?>

    /** The rider tapped "I'm fine". */
    fun cancel(at: Instant)

    /** Clears a signal the UI has finished acting on, without resetting the detector. */
    fun consumeSignal()

    /** A new ride starts with nothing remembered. */
    fun reset()

    /** Drives the real detector with a synthetic impact, for testing the countdown by hand. */
    fun simulateImpact(now: Instant)
}
