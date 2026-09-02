package io.github.meko123456.ridetogether.announce

import io.github.meko123456.ridetogether.alerts.Alert
import io.github.meko123456.ridetogether.model.LatLng
import io.github.meko123456.ridetogether.model.QuickMessage
import io.github.meko123456.ridetogether.model.RideEvent
import io.github.meko123456.ridetogether.model.RoomState
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What a rider hears through a helmet. The restraint is the feature, so most of these tests are
 * about what is deliberately *not* said.
 */
class AnnouncerTest {

    private val t0 = Instant.parse("2026-09-02T10:00:00Z")
    private val me = "me"
    private val other = "alex"

    private val names = mapOf(me to "Merab", other to "Alex")
    private val nameOf: (String) -> String = { names[it] ?: "A rider" }

    private fun announcer() = Announcer(selfId = me)

    // ────────────────────────────────── who hears what

    @Test
    fun `my own fall-behind prompt is spoken to me`() {
        val spoken = announcer().announce(
            now = t0,
            alerts = listOf(Alert.FallingBehind(me, t0, 1_600.0)),
            nameOf = nameOf,
        )
        assertEquals(1, spoken.size)
        assertTrue(spoken.single().text.contains("dropped back"), spoken.single().text)
    }

    @Test
    fun `somebody else's fall-behind prompt is not broadcast to me`() {
        // It is a cheap question to one rider. Read out to five others it becomes an accusation
        // and four interruptions, which is how a headset gets ignored.
        val spoken = announcer().announce(
            now = t0,
            alerts = listOf(Alert.FallingBehind(other, t0, 1_600.0)),
            nameOf = nameOf,
        )
        assertTrue(spoken.isEmpty(), "should say nothing: $spoken")
    }

    @Test
    fun `an incident is spoken to everyone and names the rider`() {
        val spoken = announcer().announce(
            now = t0,
            alerts = listOf(Alert.PossibleIncident(other, t0, LatLng(41.7, 44.8), t0)),
            nameOf = nameOf,
        )
        assertEquals(Priority.CRITICAL, spoken.single().priority)
        assertTrue(spoken.single().text.contains("Alex"), spoken.single().text)
    }

    @Test
    fun `my own incident tells me the group has been alerted`() {
        val spoken = announcer().announce(
            now = t0,
            alerts = listOf(Alert.PossibleIncident(me, t0, null, t0)),
            nameOf = nameOf,
        )
        assertTrue(spoken.single().text.contains("group has been alerted"), spoken.single().text)
    }

    @Test
    fun `my own quick message is not read back to me`() {
        val spoken = announcer().announce(
            now = t0,
            events = listOf(RideEvent.Message(t0, me, QuickMessage.SLOW_DOWN)),
            nameOf = nameOf,
        )
        assertTrue(spoken.isEmpty(), "$spoken")
    }

    @Test
    fun `somebody else's quick message is spoken - rephrased for a helmet`() {
        val spoken = announcer().announce(
            now = t0,
            events = listOf(RideEvent.Message(t0, other, QuickMessage.PULL_OVER_NEXT_SAFE_SPOT)),
            nameOf = nameOf,
        )
        val text = spoken.single().text
        assertTrue(text.startsWith("Alex"), text)
        assertTrue(text.contains("pull over"), text)
    }

    // ────────────────────────────────── what is never spoken

    @Test
    fun `joining leaving and answering are never spoken`() {
        // Feed entries. A ride does not need narrating.
        val spoken = announcer().announce(
            now = t0,
            events = listOf(
                RideEvent.Joined(t0, other),
                RideEvent.Left(t0, other),
                RideEvent.Responded(t0, other, io.github.meko123456.ridetogether.model.FallbackResponse.FINE),
            ),
            nameOf = nameOf,
        )
        assertTrue(spoken.isEmpty(), "$spoken")
    }

    @Test
    fun `the duplicate feed entries for alerts are not spoken twice`() {
        // The engine emits an Alert and the room records a RideEvent for the same thing. Only the
        // alert is spoken, or every incident would be announced twice.
        val spoken = announcer().announce(
            now = t0,
            events = listOf(
                RideEvent.PossibleIncident(t0, other, null),
                RideEvent.SignalLost(t0, other),
                RideEvent.FellBehind(t0, other, 1_600.0),
            ),
            nameOf = nameOf,
        )
        assertTrue(spoken.isEmpty(), "$spoken")
    }

    @Test
    fun `my own phone losing signal is not announced to me`() {
        val spoken = announcer().announce(
            now = t0,
            alerts = listOf(Alert.SignalLost(me, t0, t0)),
            nameOf = nameOf,
        )
        assertTrue(spoken.isEmpty(), "$spoken")
    }

    // ────────────────────────────────── resolutions

    @Test
    fun `a rider is only announced as back if the group was told they had gone`() {
        val announcer = announcer()
        val unheralded = announcer.announce(
            now = t0,
            alerts = listOf(Alert.Rejoined(other, t0)),
            nameOf = nameOf,
        )
        assertTrue(unheralded.isEmpty(), "nobody was told Alex had gone: $unheralded")
    }

    @Test
    fun `a rider who was announced as gone is announced as back`() {
        val announcer = announcer()
        announcer.announce(now = t0, alerts = listOf(Alert.SignalLost(other, t0, t0)), nameOf = nameOf)
        val back = announcer.announce(
            now = t0 + 5.minutes,
            alerts = listOf(Alert.SignalRestored(other, t0 + 5.minutes)),
            nameOf = nameOf,
        )
        assertTrue(back.single().text.contains("reporting again"), back.single().text)
        assertEquals(Priority.ROUTINE, back.single().priority)
    }

    @Test
    fun `a resolution is announced once and not again`() {
        val announcer = announcer()
        announcer.announce(now = t0, alerts = listOf(Alert.SignalLost(other, t0, t0)), nameOf = nameOf)
        val first = announcer.announce(
            now = t0 + 5.minutes,
            alerts = listOf(Alert.SignalRestored(other, t0 + 5.minutes)),
            nameOf = nameOf,
        )
        val second = announcer.announce(
            now = t0 + 10.minutes,
            alerts = listOf(Alert.SignalRestored(other, t0 + 10.minutes)),
            nameOf = nameOf,
        )
        assertEquals(1, first.size)
        assertTrue(second.isEmpty(), "the problem was already closed: $second")
    }

    // ────────────────────────────────── the channel

    @Test
    fun `only one non-critical line is spoken per tick`() {
        // A helmet is one channel and a rider absorbs about one sentence. The rest is on screen.
        val spoken = announcer().announce(
            now = t0,
            events = listOf(
                RideEvent.Message(t0, other, QuickMessage.SLOW_DOWN),
                RideEvent.Message(t0, other, QuickMessage.FUEL_STOP_NEEDED),
                RideEvent.StateChanged(t0, other, RoomState.RIDING, RoomState.PAUSED),
            ),
            nameOf = nameOf,
        )
        assertEquals(1, spoken.size, "$spoken")
    }

    @Test
    fun `the most important thing wins the channel`() {
        val spoken = announcer().announce(
            now = t0,
            alerts = listOf(Alert.PossibleIncident(other, t0, null, t0)),
            events = listOf(RideEvent.Message(t0, other, QuickMessage.FUEL_STOP_NEEDED)),
            nameOf = nameOf,
        )
        assertEquals(1, spoken.size, "$spoken")
        assertEquals(Priority.CRITICAL, spoken.single().priority)
    }

    @Test
    fun `priority beats arrival order when two lines compete`() {
        // The gap mutation testing found: every previous multi-line test used candidates of the
        // same priority, so sorting by time alone would have passed them all. Here the routine
        // line arrived first and must still lose.
        val spoken = announcer().announce(
            now = t0 + 1.minutes,
            events = listOf(
                RideEvent.BatterySaver(t0, other, 15),
                RideEvent.Message(t0 + 30.seconds, other, QuickMessage.PULL_OVER_NEXT_SAFE_SPOT),
            ),
            nameOf = nameOf,
        )
        assertEquals(1, spoken.size, "$spoken")
        assertEquals(Priority.IMPORTANT, spoken.single().priority)
        assertTrue(spoken.single().text.contains("pull over"), spoken.single().text)
    }

    @Test
    fun `a critical line is never held back by the quiet period`() {
        val announcer = announcer()
        announcer.announce(
            now = t0,
            events = listOf(RideEvent.Message(t0, other, QuickMessage.FUEL_STOP_NEEDED)),
            nameOf = nameOf,
        )
        val incident = announcer.announce(
            now = t0 + 1.seconds,
            alerts = listOf(Alert.PossibleIncident(other, t0 + 1.seconds, null, t0)),
            nameOf = nameOf,
        )
        assertEquals(1, incident.size, "an incident one second later must still be spoken")
    }

    @Test
    fun `a routine line waits until the channel has been quiet`() {
        val announcer = announcer()
        announcer.announce(
            now = t0,
            events = listOf(RideEvent.Message(t0, other, QuickMessage.FUEL_STOP_NEEDED)),
            nameOf = nameOf,
        )
        val tooSoon = announcer.announce(
            now = t0 + 5.seconds,
            events = listOf(RideEvent.BatterySaver(t0 + 5.seconds, other, 15)),
            nameOf = nameOf,
        )
        assertTrue(tooSoon.isEmpty(), "speech on top of speech loses both: $tooSoon")

        val later = announcer.announce(
            now = t0 + 1.minutes,
            events = listOf(RideEvent.BatterySaver(t0 + 1.minutes, other, 15)),
            nameOf = nameOf,
        )
        assertEquals(1, later.size, "once the channel is idle it should be spoken")
    }

    @Test
    fun `the same line is not repeated while it is still recent`() {
        val announcer = announcer()
        val first = announcer.announce(
            now = t0,
            alerts = listOf(Alert.SignalLost(other, t0, t0)),
            nameOf = nameOf,
        )
        assertEquals(1, first.size)
        // A phone flickering in and out of signal in a valley would otherwise be announced
        // on every single tick.
        val again = announcer.announce(
            now = t0 + 1.minutes,
            alerts = listOf(Alert.SignalLost(other, t0 + 1.minutes, t0)),
            nameOf = nameOf,
        )
        assertTrue(again.isEmpty(), "$again")
    }

    @Test
    fun `an incident does bear repeating - sooner than an ordinary line would`() {
        val announcer = announcer()
        announcer.announce(
            now = t0,
            alerts = listOf(Alert.PossibleIncident(other, t0, null, t0)),
            nameOf = nameOf,
        )
        val soon = announcer.announce(
            now = t0 + 1.minutes,
            alerts = listOf(Alert.PossibleIncident(other, t0 + 1.minutes, null, t0)),
            nameOf = nameOf,
        )
        assertTrue(soon.isEmpty(), "not within two minutes")

        val later = announcer.announce(
            now = t0 + 3.minutes,
            alerts = listOf(Alert.PossibleIncident(other, t0 + 3.minutes, null, t0)),
            nameOf = nameOf,
        )
        assertEquals(1, later.size, "an unresolved incident is worth saying again")
    }

    // ────────────────────────────────── room state

    @Test
    fun `a pause is spoken because a rider a kilometre back would not otherwise know`() {
        val spoken = announcer().announce(
            now = t0,
            events = listOf(RideEvent.StateChanged(t0, other, RoomState.RIDING, RoomState.PAUSED)),
            nameOf = nameOf,
        )
        assertTrue(spoken.single().text.contains("paused"), spoken.single().text)
    }

    @Test
    fun `the end of a ride says that sharing has stopped`() {
        val spoken = announcer().announce(
            now = t0,
            events = listOf(RideEvent.StateChanged(t0, other, RoomState.RIDING, RoomState.ENDED)),
            nameOf = nameOf,
        )
        assertTrue(spoken.single().text.contains("sharing has stopped"), spoken.single().text)
    }

    @Test
    fun `returning to the lobby says nothing`() {
        val spoken = announcer().announce(
            now = t0,
            events = listOf(RideEvent.StateChanged(t0, other, RoomState.ENDED, RoomState.LOBBY)),
            nameOf = nameOf,
        )
        assertTrue(spoken.isEmpty(), "$spoken")
    }

    @Test
    fun `a reset clears the channel for a new ride`() {
        val announcer = announcer()
        announcer.announce(now = t0, alerts = listOf(Alert.SignalLost(other, t0, t0)), nameOf = nameOf)
        announcer.reset()
        val again = announcer.announce(
            now = t0 + 1.seconds,
            alerts = listOf(Alert.SignalLost(other, t0 + 1.seconds, t0)),
            nameOf = nameOf,
        )
        assertEquals(1, again.size, "a new ride starts with nothing suppressed")
    }

    @Test
    fun `an unknown rider still gets a sentence`() {
        val spoken = announcer().announce(
            now = t0,
            alerts = listOf(Alert.PossibleIncident("ghost", t0, null, t0)),
            nameOf = nameOf,
        )
        assertTrue(spoken.single().text.startsWith("A rider"), spoken.single().text)
    }

    @Test
    fun `every quick message has speakable wording`() {
        for (message in QuickMessage.entries) {
            val spoken = Announcer(selfId = me).announce(
                now = t0,
                events = listOf(RideEvent.Message(t0, other, message)),
                nameOf = nameOf,
            )
            val text = spoken.single().text
            assertTrue(text.length > "Alex: ".length, "$message gave \"$text\"")
            // Read out verbatim, the screen labels would be barked commands with no subject.
            assertTrue(text.startsWith("Alex"), text)
        }
    }
}
