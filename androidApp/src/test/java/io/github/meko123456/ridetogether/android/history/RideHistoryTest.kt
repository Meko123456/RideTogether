package io.github.meko123456.ridetogether.android.history

import io.github.meko123456.ridetogether.model.LatLng
import io.github.meko123456.ridetogether.summary.RideSummariser
import io.github.meko123456.ridetogether.summary.TracePoint
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant

/**
 * Persistence, tested because this is the one place in the app where a bug loses something the
 * rider cannot get back: a ride they actually rode.
 */
class RideHistoryTest {

    private val directory: File = Files.createTempDirectory("ride-history").toFile()
    private val file = File(directory, "history.json")
    private val history = RideHistory(file)
    private val t0 = Instant.parse("2026-09-03T09:00:00Z")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun east(meters: Double) = LatLng(0.0, meters / 111_320.0)

    /** A real summary, built by the real summariser, so the mapping is exercised end to end. */
    private fun summary(speed: Double = 20.0, seconds: Int = 60, from: Instant = t0) =
        RideSummariser().summarise(
            "room",
            mapOf(
                "me" to buildList {
                    var at = from
                    var position = 0.0
                    repeat(seconds) {
                        add(TracePoint(at, east(position), speed))
                        position += speed
                        at += 1.seconds
                    }
                },
            ),
        )

    @Test
    fun `nothing saved reads back as no rides rather than an error`() {
        assertTrue(history.load().isEmpty())
    }

    @Test
    fun `a saved ride survives a round trip`() {
        val stored = history.save("ABC234", "Gudauri and back", summary())
        assertTrue(stored != null)

        val loaded = history.load().single()
        assertEquals("ABC234", loaded.roomId)
        assertEquals("Gudauri and back", loaded.name)
        assertEquals(stored.distanceMeters, loaded.distanceMeters, 0.001)
        assertEquals(stored.movingMillis, loaded.movingMillis)
        assertEquals(stored.stopCount, loaded.stopCount)
    }

    @Test
    fun `rides come back newest first`() {
        history.save("OLD111", "First", summary(from = t0))
        history.save("NEW222", "Second", summary(from = t0 + 3600.seconds))
        assertEquals(listOf("NEW222", "OLD111"), history.load().map { it.roomId })
    }

    @Test
    fun `an unknown average is not read back as a zero`() {
        // Zero would claim the rider stood still for the whole ride; unknown has to stay unknown.
        val stationary = RideSummariser().summarise(
            "room",
            mapOf("me" to listOf(TracePoint(t0, east(0.0), 0.0), TracePoint(t0 + 30.seconds, east(0.0), 0.0))),
        )
        history.save("STILL1", "Parked", stationary)
        val loaded = history.load().single()
        assertNull(loaded.averageMovingSpeedMps, "no moving time means no average")
    }

    @Test
    fun `a corrupt file costs the history but never throws`() {
        history.save("ABC234", "Ride", summary())
        file.writeText("{ this is not the json you are looking for")
        assertTrue(history.load().isEmpty(), "unreadable is empty, not a crash")

        // And it recovers: the next save rewrites the file rather than refusing forever.
        assertTrue(history.save("DEF567", "After", summary()) != null)
        assertEquals(listOf("DEF567"), history.load().map { it.roomId })
    }

    @Test
    fun `no temporary file is left behind by a save`() {
        // The write goes to a temp file and is renamed, so a kill mid-write cannot leave a
        // half-written history. Worth being straight about what this test does and does not
        // cover: the atomicity itself is not observable from inside the process — mutation
        // testing confirmed that replacing the rename with a direct write breaks nothing here —
        // so this only checks the temp file does not survive. The rename stays because it is
        // correct, not because a test proves it.
        history.save("ABC234", "Ride", summary())
        assertTrue(file.exists())
        assertTrue(
            directory.listFiles()?.none { it.name.endsWith(".tmp") } == true,
            "the temporary file should not be left behind",
        )
    }

    @Test
    fun `a ride with too little trace to summarise is not stored as an empty row`() {
        val nothing = RideSummariser().summarise("room", emptyMap())
        assertNull(history.save("EMPTY1", "Nothing", nothing))
        assertTrue(history.load().isEmpty())
    }

    @Test
    fun `the history is capped so it cannot grow without limit`() {
        repeat(105) { index ->
            history.save("R$index", "Ride $index", summary(from = t0 + (index * 3600).seconds))
        }
        val loaded = history.load()
        assertEquals(100, loaded.size)
        // The cap drops the oldest, not the newest.
        assertEquals("R104", loaded.first().roomId)
    }
}
