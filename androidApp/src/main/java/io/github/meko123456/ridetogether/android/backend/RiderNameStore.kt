package io.github.meko123456.ridetogether.android.backend

import android.content.Context
import androidx.core.content.edit
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.capped

/**
 * The name this rider goes by on a shared backend, asked once and kept. Unlike the sign-in session
 * it may be backed up: carried to a new phone, it is still what the rider is called.
 */
class RiderNameStore(context: Context) {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var name: String?
        get() = preferences.getString(NAME, null)?.let(::tidy)?.takeIf(String::isNotEmpty)
        set(value) = preferences.edit { putString(NAME, value?.let(::tidy)) }

    companion object {
        private const val FILE = "ridetogether-rider"
        private const val NAME = "name"

        /** Trimmed, and no longer than the database rules accept, which would refuse it outright. */
        fun tidy(name: String): String = name.trim().capped(Member.MAX_NAME_LENGTH).trim()
    }
}
