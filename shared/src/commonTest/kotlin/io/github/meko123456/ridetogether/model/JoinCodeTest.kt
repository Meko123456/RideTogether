package io.github.meko123456.ridetogether.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JoinCodeTest {

    @Test
    fun `the alphabet excludes the characters people misread`() {
        // Read aloud through a helmet, I/1, O/0 and S/5 are the classic confusions.
        for (c in listOf('I', 'L', 'O', 'S', '0', '1', '5')) {
            assertFalse(c in JoinCode.ALPHABET, "$c should not be in the alphabet")
        }
        assertEquals(29, JoinCode.ALPHABET.length)
        assertEquals(JoinCode.ALPHABET.length, JoinCode.ALPHABET.toSet().size, "alphabet has duplicates")
    }

    @Test
    fun `the code space is large enough to be unguessable`() {
        // 29^6 ≈ 594 million.
        assertEquals(594_823_321L, JoinCode.SPACE_SIZE)
    }

    @Test
    fun `valid codes are accepted and invalid ones rejected`() {
        assertTrue(JoinCode.isValid("ABC234"))
        assertFalse(JoinCode.isValid("ABC23"), "too short")
        assertFalse(JoinCode.isValid("ABC2345"), "too long")
        assertFalse(JoinCode.isValid("ABC23O"), "contains an excluded look-alike")
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
    fun `an ambiguous character is rejected rather than guessed`() {
        // Silently "correcting" a typo could drop a rider into a stranger's live ride.
        assertNull(JoinCode.parseOrNull("ABCI34"))
        assertNull(JoinCode.parseOrNull("ABC0 34"))
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
