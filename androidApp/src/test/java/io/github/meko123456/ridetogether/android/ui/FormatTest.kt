package io.github.meko123456.ridetogether.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatTest {

    @Test
    fun `distance reads the way a rider would say it`() {
        assertEquals("0 m", formatDistance(0.0))
        assertEquals("850 m", formatDistance(850.4))
        assertEquals("1.0 km", formatDistance(1_000.0))
        assertEquals("12.3 km", formatDistance(12_345.0))
    }

    @Test
    fun `a short ride shows seconds rather than zero minutes`() {
        // "0m" of riding reads as a failure rather than a short ride, and a short ride is exactly
        // what a first test run produces.
        assertEquals("6s", formatDuration(6_400))
        assertEquals("59s", formatDuration(59_000))
        assertEquals("1m", formatDuration(60_000))
    }

    @Test
    fun `durations never read like a clock`() {
        // "0h 07m" looks like a time of day; a duration should not.
        assertEquals("7m", formatDuration(7 * 60_000L))
        assertEquals("1h 24m", formatDuration((84 * 60_000L)))
        assertEquals("2h 0m", formatDuration(2 * 60 * 60_000L))
    }

    @Test
    fun `speed is shown in the units on the speedometer`() {
        assertEquals("72 km/h", formatSpeed(20.0))
        assertEquals("0 km/h", formatSpeed(0.0))
    }

    @Test
    fun `an unknown speed is a dash rather than a zero`() {
        // Zero is a claim that the rider was stationary; a dash says the app does not know.
        assertEquals("—", formatSpeed(null))
    }

    @Test
    fun `nothing formats to an empty string`() {
        val outputs = listOf(
            formatDistance(0.0), formatDistance(999.9), formatDistance(50_000.0),
            formatDuration(0), formatDuration(1), formatDuration(86_400_000),
            formatSpeed(null), formatSpeed(0.0), formatSpeed(90.0),
        )
        for (output in outputs) assertTrue(output.isNotBlank(), "empty output")
    }
}
