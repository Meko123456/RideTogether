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
 * With a shared backend (#10) the id is no longer a constant: it is whoever this install signed in
 * as, known only once the backend has said so. [self] holds it for the one part of the app that
 * cannot be handed it, the location service, which Android constructs. Everything else takes it
 * from the [io.github.meko123456.ridetogether.realtime.RealtimeClient] it writes through, which
 * can only write as one rider and so cannot disagree with itself.
 */
object RiderIdentity {

    /** The rider on the in-memory backend, where nobody else can see them anyway. */
    const val ON_THIS_PHONE = "me"

    /** Who this phone is right now. Set before any ride can start, and the same for its length. */
    @Volatile
    var self: String = ON_THIS_PHONE
        private set

    fun becomes(riderId: String) {
        self = riderId
    }
}
