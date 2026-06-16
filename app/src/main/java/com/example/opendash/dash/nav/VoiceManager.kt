package com.example.opendash.dash.nav

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class VoiceMode { OFF, CHIME, FULL }

/**
 * Turn-by-turn voice guidance. We own this layer (CLAUDE.md feature 2), so it's a plain
 * per-trip setting: OFF (silent), CHIME (a beep before each turn), or FULL (spoken
 * directions). Singleton so the RouteScreen toggle and the nav loop in [DashViewModel]
 * share one engine + persisted mode.
 *
 * Announcements run through [maybeAnnounce]: as the rider approaches a maneuver it fires
 * once at a FAR range ("in 400 m, turn left onto X") and once NEAR it ("turn left now"),
 * de-duped per maneuver so it never nags.
 */
class VoiceManager private constructor(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("voice", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(
        runCatching { VoiceMode.valueOf(prefs.getString(KEY_MODE, VoiceMode.CHIME.name)!!) }
            .getOrDefault(VoiceMode.CHIME)
    )
    val mode = _mode.asStateFlow()

    fun setMode(m: VoiceMode) {
        prefs.edit().putString(KEY_MODE, m.name).apply()
        _mode.value = m
        if (m == VoiceMode.OFF) tts?.stop()
        else if (m == VoiceMode.FULL) ensureTts()  // warm up so the first turn isn't silent
    }

    // ── TTS engine (lazy; created only when FULL is actually used) ──────────────
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var tone: ToneGenerator? = null

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
            else Log.w(TAG, "TextToSpeech init failed: $status")
        }
    }

    private fun chime() {
        runCatching {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
        }.onFailure { Log.w(TAG, "chime failed: ${it.message}") }
    }

    private fun speak(text: String) {
        ensureTts()
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
        else chime() // engine not ready yet → at least beep
    }

    // ── Announcement scheduling ────────────────────────────────────────────────
    private var lastManeuverKey = -1.0   // cumulativeMeters of the maneuver we're tracking
    private var farDone = false
    private var nearDone = false
    private var arrived = false

    /**
     * Call every nav tick. [maneuver] is the upcoming turn (null = none), [distanceM] the
     * distance to it, [remainingM] distance to the destination.
     */
    fun maybeAnnounce(maneuver: Maneuver?, distanceM: Double, remainingM: Double) {
        if (_mode.value == VoiceMode.OFF) return

        // Arrival (announced once).
        if (remainingM in 0.0..ARRIVE_M && !arrived) {
            arrived = true
            if (_mode.value == VoiceMode.FULL) speak("You have arrived at your destination") else chime()
            return
        }
        if (remainingM > ARRIVE_M) arrived = false

        if (maneuver == null) return
        // New maneuver → reset the per-turn de-dupe flags.
        if (maneuver.cumulativeMeters != lastManeuverKey) {
            lastManeuverKey = maneuver.cumulativeMeters
            farDone = false; nearDone = false
        }
        when {
            distanceM in 0.0..NEAR_M && !nearDone -> {
                nearDone = true; farDone = true
                if (_mode.value == VoiceMode.FULL) speak("Now, ${turnPhrase(maneuver)}") else chime()
            }
            distanceM in NEAR_M..FAR_M && !farDone -> {
                farDone = true
                if (_mode.value == VoiceMode.FULL) speak("In ${roundDist(distanceM)}, ${turnPhrase(maneuver)}") else chime()
            }
        }
    }

    /** Reset trip state when nav starts/stops so the next route announces cleanly. */
    fun resetTrip() { lastManeuverKey = -1.0; farDone = false; nearDone = false; arrived = false }

    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null; ttsReady = false
        runCatching { tone?.release() }; tone = null
    }

    private fun turnPhrase(m: Maneuver): String = when (m.type) {
        ManeuverType.TURN_LEFT    -> "turn left"
        ManeuverType.TURN_RIGHT   -> "turn right"
        ManeuverType.SLIGHT_LEFT  -> "keep left"
        ManeuverType.SLIGHT_RIGHT -> "keep right"
        ManeuverType.SHARP_LEFT   -> "sharp left"
        ManeuverType.SHARP_RIGHT  -> "sharp right"
        ManeuverType.UTURN        -> "make a U-turn"
        ManeuverType.ROUNDABOUT   -> "enter the roundabout"
        ManeuverType.ARRIVE       -> "you have arrived"
        else                      -> "continue straight"
    }

    private fun roundDist(m: Double): String = when {
        m >= 1000 -> "%.1f kilometers".format(m / 1000.0)
        m >= 500  -> "500 meters"
        m >= 200  -> "200 meters"
        else      -> "${(m / 50).toInt() * 50} meters"
    }

    companion object {
        private const val TAG = "VoiceManager"
        private const val FAR_M = 450.0
        private const val NEAR_M = 60.0
        private const val ARRIVE_M = 30.0
        private const val KEY_MODE = "mode"

        @Volatile private var instance: VoiceManager? = null
        fun get(context: Context): VoiceManager =
            instance ?: synchronized(this) {
                instance ?: VoiceManager(context).also { instance = it }
            }
    }
}
