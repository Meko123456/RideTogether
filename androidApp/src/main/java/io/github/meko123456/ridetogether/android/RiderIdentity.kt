package io.github.meko123456.ridetogether.android

/**
 * Who this phone is, in one place.
 *
 * It was in three: the view model, the location service and the composition that passes an id to
 * the map. They all happened to say "me", and nothing would have complained if one of them had
 * stopped — the positions would simply have been published under a rider id that is not in the
 * room, so this phone would vanish from its own map while everything else carried on working.
 * That is a bad failure to debug and a trivial one to prevent.
 *
 * When accounts land this becomes the signed-in user's id, and there is exactly one line to change.
 */
object RiderIdentity {
    const val SELF = "me"
}
