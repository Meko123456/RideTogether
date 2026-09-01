package io.github.meko123456.ridetogether.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JoinCodeTest {

    @Test
    fun `the alphabet is Crockford Base32 - no I, L, O or U`() {
        for (c in listOf('I', 'L', 'O', 'U')) {
            assertFalse(c in JoinCode.ALPHABET, "$c should not be a symbol")
        }
        assertEquals(32, JoinCode.ALPHABET.length)
        assertEquals(JoinCode.ALPHABET.length, JoinCode.ALPHABET.toSet().size, "alphabet has duplicates")
    }

    @Test
    fun `the code space keeps the full 30 bits`() {
        // 32^6 = 1,073,741,824 — kept by normalising input rather than shrinking the alphabet.
        assertEquals(1_073_741_824L, JoinCode.SPACE_SIZE)
    }

    @Test
    fun `valid codes are accepted and invalid ones rejected`() {
        assertTrue(JoinCode.isValid("ABC234"))
        assertFalse(JoinCode.isValid("ABC23"), "too short")
        assertFalse(JoinCode.isValid("ABC2345"), "too long")
        assertFalse(JoinCode.isValid("ABC23O"), "O is not a symbol - it must be normalised to 0 first")
        assertFalse(JoinCode.isValid("abc234"), "lower case is not the canonical form")
        assertFalse(JoinCode.isValid(""))
    }

    @Test
    fun `parsing forgives case, spaces and dashes`() {
        assertEquals(JoinCode("ABC234"), JoinCode.parseOrNull("abc234"))
        assertEquals(JoinCode("ABC234"), JoinCode.parseOrNull(" ABC-234 "))
        assertEquals(JoinCode("ABC234"), JoinCode.parseOrNull("abc 234"))
    }

    @Test
    fun `confusable letters are recovered, not rejected`() {
        // Typing O for 0 or I/L for 1 is the classic helmet-and-gloves mistake. Because those
        // letters are never valid symbols, mapping them can only recover the intended code —
        // it can never resolve to a different room.
        assertEquals(JoinCode.parseOrNull("ABC034"), JoinCode.parseOrNull("ABCO34"))
        assertEquals(JoinCode.parseOrNull("ABC134"), JoinCode.parseOrNull("ABCI34"))
        assertEquals(JoinCode.parseOrNull("ABC134"), JoinCode.parseOrNull("ABCL34"))
    }

    @Test
    fun `genuinely malformed input still yields nothing`() {
        assertNull(JoinCode.parseOrNull("ABC"))
        assertNull(JoinCode.parseOrNull("ABC23456"))
        assertNull(JoinCode.parseOrNull("AB!234"))
    }

    @Test
    fun `constructing an invalid code throws`() {
        assertFailsWith<IllegalArgumentException> { JoinCode("nope") }
    }

    @Test
    fun `generation is deterministic given the randomness source`() {
        var i = 0
        val code = JoinCode.generate { i++ % JoinCode.ALPHABET.length }
        assertEquals(JoinCode.ALPHABET.take(6), code.value)
        assertTrue(JoinCode.isValid(code.value))
    }

    @Test
    fun `generated codes only ever use the safe alphabet`() {
        val rng = kotlin.random.Random(42)
        repeat(500) {
            val code = JoinCode.generate { rng.nextInt(it) }
            assertTrue(code.value.all { c -> c in JoinCode.ALPHABET }, "bad code: $code")
        }
    }
}
