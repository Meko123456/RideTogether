package io.github.meko123456.ridetogether.model

/**
 * At most [max] chars of this text, never ending in half of a surrogate pair.
 *
 * Most emoji are two chars (a surrogate pair). A plain `take(max)` whose cut falls between the
 * two keeps the first half alone, which isn't valid Unicode: it shows as a broken character, and
 * on its way to the database it turns into "?". Here the cut moves back one char instead, so an
 * emoji across the cap is left out whole.
 */
fun String.capped(max: Int): String {
    val cut = take(max)
    return if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}
