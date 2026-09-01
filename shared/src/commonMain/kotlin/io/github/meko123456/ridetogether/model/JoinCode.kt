package io.github.meko123456.ridetogether.model

/**
 * A 6-character room join code, typed out loud in a car park or read off a phone screen.
 *
 * The alphabet deliberately drops the characters people confuse when reading aloud or typing:
 * `I`, `L`, `O`, `S`, `0`, `1`, `5`. That is Crockford's reasoning — a code exists to be
 * transcribed by a human with a helmet on, so ambiguity costs more than entropy.
 *
 * 29 symbols over 6 places is 29^6 ≈ 594 million combinations, which is ample for
 * simultaneous rides while staying impractical to guess. Guessability still matters because a
 * valid code reveals a private ride's live locations, so codes are single-room and expire with
 * the room (24 h) rather than living forever.
 */
@JvmInline
value class JoinCode(val value: String) {
    init {
        require(isValid(value)) { "invalid join code: $value" }
    }

    override fun toString(): String = value

    companion object {
        const val LENGTH = 6

        /** Unambiguous alphabet: no I, L, O, S, 0, 1 or 5. */
        const val ALPHABET = "ABCDEFGHJKMNPQRTUVWXYZ2346789"

        /** Total codes in the space — 29^6. */
        val SPACE_SIZE: Long = ALPHABET.length.toLong().let { n -> (1..LENGTH).fold(1L) { acc, _ -> acc * n } }

        fun isValid(candidate: String): Boolean =
            candidate.length == LENGTH && candidate.all { it in ALPHABET }

        /**
         * Normalises what a human typed: trims, upper-cases, strips spaces and dashes, and maps
         * the excluded look-alikes onto their intended symbol (O→Q is wrong, so O and 0 both map
         * to nothing — instead we map the *typo* direction: someone typing O meant Q? No.)
         *
         * Concretely: we only fix case, whitespace and separators. Ambiguous characters are
         * rejected rather than guessed, because silently turning a typo into a *different valid
         * room* would drop a rider into a stranger's ride.
         */
        fun normalise(input: String): String =
            input.trim().uppercase().filter { it != ' ' && it != '-' && it != '_' }

        /** Parses user input, or null when it isn't a valid code. */
        fun parseOrNull(input: String): JoinCode? =
            normalise(input).takeIf(::isValid)?.let(::JoinCode)

        /** Generates a code from a source of randomness the caller controls (so tests are deterministic). */
        fun generate(nextInt: (bound: Int) -> Int): JoinCode =
            JoinCode((1..LENGTH).map { ALPHABET[nextInt(ALPHABET.length)] }.joinToString(""))
    }
}
