package io.github.meko123456.ridetogether.android.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import io.github.meko123456.ridetogether.android.ui.Voice
import io.github.meko123456.ridetogether.announce.Announcement
import io.github.meko123456.ridetogether.announce.Priority
import java.util.Locale

/**
 * Speaks what the [io.github.meko123456.ridetogether.announce.Announcer] decided, and nothing else.
 *
 * The division is deliberate: *what* to say and *whether* to say it is pure, tested logic in
 * `:shared`; this class is the part that cannot be tested without a device, so it is kept as thin
 * as possible. It makes no decisions about content.
 *
 * Two Android specifics that matter on a bike:
 *
 * - **`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, not `USAGE_MEDIA`.** A Bluetooth headset ducks music
 *   for navigation and mixes it in rather than pausing the world, and a rider who loses their music
 *   every time the app speaks will turn the app off. It is also the usage that keeps working when
 *   the phone is in Do Not Disturb.
 * - **A critical line flushes the queue.** If two things are waiting to be said and one of them is
 *   "someone may have come off", the rider should hear that one first, not after the fuel-stop
 *   message that happened to arrive earlier.
 */
class RideSpeaker(context: Context) : Voice {

    private var ready = false
    private var lastError: String? = null

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (!ready) {
            lastError = "TextToSpeech unavailable (status $status)"
            Log.w(TAG, lastError!!)
        }
    }.apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(TAG, "speaking: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.i(TAG, "done: $utteranceId")
            }

            @Deprecated("The non-deprecated overload is API 21+, but this one still has to exist.")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "failed: $utteranceId")
            }
        })
    }

    /** English for now; the announcer's copy is English, so pretending otherwise would be worse. */
    fun configure(): Boolean {
        if (!ready) return false
        val result = tts.setLanguage(Locale.UK)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            lastError = "no English voice installed"
            Log.w(TAG, lastError!!)
            return false
        }
        return true
    }

    override fun speak(announcements: List<Announcement>) {
        if (announcements.isEmpty()) return
        if (!ready) {
            Log.w(TAG, "not speaking, engine not ready: ${announcements.map { it.text }}")
            return
        }
        for (announcement in announcements) {
            val mode = if (announcement.priority == Priority.CRITICAL) {
                // Jump the queue: an incident should not wait behind a fuel-stop message.
                TextToSpeech.QUEUE_FLUSH
            } else {
                TextToSpeech.QUEUE_ADD
            }
            tts.speak(announcement.text, mode, null, announcement.key)
        }
    }

    /** Stops mid-sentence. Called when a ride ends: nothing should still be talking about it. */
    override fun stop() {
        runCatching { tts.stop() }
    }

    override fun release() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    /** For the diagnostics line, so "nothing was said" has a visible reason. */
    override fun status(): String = when {
        ready && lastError == null -> "Voice ready"
        lastError != null -> "Voice unavailable — $lastError"
        else -> "Voice starting…"
    }

    private companion object {
        const val TAG = "RideSpeaker"
    }
}
