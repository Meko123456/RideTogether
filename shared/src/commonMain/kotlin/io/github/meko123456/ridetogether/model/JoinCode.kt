package io.github.meko123456.ridetogether.model

// Explicit: kotlin.jvm.* is default-imported only on the JVM, so without this the iOS targets
// fail to resolve @JvmInline. The annotation is an @OptionalExpectation, so it simply has no
// effect where it does not apply.
import kotlin.jvm.JvmInline

/**
 * A 6-character room join code, typed out loud in a car park or read off a phone screen.
 *
 * The alphabet is **Crockford's Base32**: the digits plus the letters, minus `I`, `L`, `O` and
 * `U`. The first three are dropped because they are indistinguishable from `1`, `1` and `0`
 * when read aloud through a helmet; `U` is dropped so the encoding cannot spell obscenities.
 *
 * Crucially, ambiguity is solved by **normalising input** rather than by shrinking the alphabet:
 * a typed `O` becomes `0`, and `I` or `L` become `1`. That keeps the full 32^6 ≈ 1.07 billion
 * keyspace *and* forgives the mistake, where a reduced alphabet would give up entropy and still
 * reject the typo. Because the excluded letters are never valid symbols, the mapping can only
 * ever recover the intended code — it can never silently resolve to a *different* room.
 *
 * Length alone is not the security boundary. 30 bits is enumerable by a determined script, so a
 * short code protects a ride only in combination with server-side rate limiting on code
 * resolution, and genuinely private rides should share a high-entropy deep link instead. Codes
 * also die with their room (24 h), which bounds the window.
 */
@JvmInline
value class JoinCode(val value: String) {
    init {
        require(isValid(value)) { "invalid join code: $value" }
    }

    override fun toString(): String = value

    companion object {
        const val LENGTH = 6

        /** Crockford Base32: 0-9 and A-Z without I, L, O, U. */
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /** Total codes in the space — 32^6 = 1,073,741,824. */
        val SPACE_SIZE: Long = (1..LENGTH).fold(1L) { acc, _ -> acc * ALPHABET.length }

        fun isValid(candidate: String): Boolean =
            candidate.length == LENGTH && candidate.all { it in ALPHABET }

        /**
         * Normalises what a human typed: trims, upper-cases, drops separators, and applies
         * Crockford's confusable mapping (`O`→`0`, `I`/`L`→`1`). Since those letters are not
         * symbols in the alphabet, this can only recover the intended code.
         */
        fun normalise(input: String): String = buildString {
            for (raw in input.trim().uppercase()) {
                when (raw) {
                    ' ', '-', '_' -> Unit
                    'O' -> append('0')
                    'I', 'L' -> append('1')
                    'U' -> Unit // never a valid symbol and has no unambiguous intent
                    else -> append(raw)
                }
            }
        }

        /** Parses user input, or null when it isn't a valid code. */
        fun parseOrNull(input: String): JoinCode? =
            normalise(input).takeIf(::isValid)?.let(::JoinCode)

        /** Generates a code from a source of randomness the caller controls (so tests are deterministic). */
        fun generate(nextInt: (bound: Int) -> Int): JoinCode =
            JoinCode((1..LENGTH).map { ALPHABET[nextInt(ALPHABET.length)] }.joinToString(""))
    }
}
