package io.github.meko123456.ridetogether.announce

import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How much a spoken line is allowed to interrupt.
 *
 * The ordering is the point: a helmet is a single channel, and a rider doing 100 km/h can absorb
 * roughly one sentence. So everything competes, and the ranking decides who wins.
 */
enum class Priority {
    /** Someone may be hurt. Always spoken, immediately, however much else is queued. */
    CRITICAL,

    /** Someone needs the group to do something: a quick message, a rider gone quiet. */
    IMPORTANT,

    /** Nice to know. Spoken only when nothing better is competing and the channel is idle. */
    ROUTINE,
}

/** One line to say out loud. */
data class Announcement(
    val text: String,
    val priority: Priority,
    val at: Instant,
    /**
     * Identifies *what this is about*, so the same thing is not said twice. Two alerts about the
     * same rider and the same problem share a key even if their timestamps differ.
     */
    val key: String,
)

/** Tunables for what gets spoken and how often. */
data class AnnounceConfig(
    /**
     * How long the same line stays suppressed. A rider whose phone keeps dropping in and out of
     * signal in a valley would otherwise be announced every tick.
     */
    val repeatWindow: Duration = 10.minutes,

    /**
     * Quiet time after any non-critical line. Speech that arrives while the rider is still
     * processing the last sentence is worse than silence — they lose both.
     */
    val quietAfterSpeaking: Duration = 20.seconds,

    /** How long a critical line suppresses a repeat of itself. Shorter: it bears repeating. */
    val criticalRepeatWindow: Duration = 2.minutes,
)
