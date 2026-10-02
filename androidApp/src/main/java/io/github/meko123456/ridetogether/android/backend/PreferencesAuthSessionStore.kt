package io.github.meko123456.ridetogether.android.backend

import android.content.Context
import androidx.core.content.edit
import io.github.meko123456.ridetogether.realtime.rtdb.AuthSession
import io.github.meko123456.ridetogether.realtime.rtdb.AuthSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps the rider's [AuthSession] in the app's private preferences, so every later launch is the
 * same rider rather than a stranger to the ride they were on.
 *
 * In a file of its own, [FILE], for the backup rules to leave behind (res/xml/backup_rules.xml and
 * data_extraction_rules.xml). An identity is made once per install, and that has to stay true
 * through a backup: restored onto a second phone, the session would make both phones one rider,
 * writing over each other's position, so each would vanish from the other's map. A restored phone
 * signs in afresh instead, as the new install it is.
 */
class PreferencesAuthSessionStore(context: Context) : AuthSessionStore {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override suspend fun load(): AuthSession? = withContext(Dispatchers.IO) {
        val uid = preferences.getString(UID, null)
        val refreshToken = preferences.getString(REFRESH_TOKEN, null)
        if (uid != null && refreshToken != null) AuthSession(uid, refreshToken) else null
    }

    override suspend fun save(session: AuthSession) {
        withContext(Dispatchers.IO) {
            // commit, not apply: the next launch must find the session the server just issued, or
            // it will sign up again and the rider will not be who they were.
            preferences.edit(commit = true) {
                putString(UID, session.uid)
                putString(REFRESH_TOKEN, session.refreshToken)
            }
        }
    }

    companion object {
        /** The preferences file, named in the backup rules. Renaming it means renaming it there too. */
        const val FILE = "ridetogether-auth"
        private const val UID = "uid"
        private const val REFRESH_TOKEN = "refreshToken"
    }
}
