package io.github.meko123456.ridetogether.model

import kotlin.test.Test
import kotlin.test.assertEquals

class TextCapTest {

    private val bike = "🏍️" // 🏍️: a surrogate pair and a variation selector

    @Test
    fun textWithinTheCapIsUntouched() {
        assertEquals("Nino", "Nino".capped(40))
        assertEquals("Nino $bike", "Nino $bike".capped(8))
        assertEquals("", "".capped(40))
    }

    @Test
    fun aLongTextIsCutAtTheCap() {
        assertEquals("x".repeat(40), "x".repeat(100).capped(40))
    }

    @Test
    fun anEmojiAcrossTheCapIsLeftOutWholeNotKeptInHalf() {
        assertEquals("x".repeat(39), ("x".repeat(39) + bike).capped(40))
        // Cut after the pair, only the variation selector is lost and 🏍 is still a motorcycle.
        assertEquals("x".repeat(38) + "🏍", ("x".repeat(38) + bike).capped(40))
    }
}
