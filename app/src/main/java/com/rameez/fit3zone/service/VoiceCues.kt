package com.rameez.fit3zone.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.rameez.fit3zone.data.HeartRateRepository
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spoken run cues through the phone's text-to-speech engine. Music is ducked while a cue plays
 * (transient audio focus). Created at session start only when audio cues are on; [shutdown] at the end.
 * Messages queue behind each other so a step cue and a split cue don't cut each other off.
 */
class VoiceCues(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private val pending = AtomicInteger(0)
    private val ids = AtomicInteger(0)
    @Volatile private var ready = false
    private val early = mutableListOf<String>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            configure()
            ready = true
            synchronized(early) { early.forEach(::speakNow); early.clear() }
        } else {
            HeartRateRepository.log.add("voice", "text-to-speech unavailable (status $status)")
        }
    }

    private fun configure() {
        tts.setAudioAttributes(attributes)
        // Cues are written in English: use an English voice even when the phone is set to Hindi etc.,
        // with the phone's region when there is one (en-IN reads "km" and numbers the Indian way).
        val phone = Locale.getDefault()
        val english = listOf(if (phone.language == "en") phone else Locale.forLanguageTag("en-${phone.country}"), Locale.US)
            .firstOrNull { it.country.isNotEmpty() && tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
        english?.let { tts.language = it }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
        })
    }

    /** Muted mid-run from the Run screen or notification: cues are dropped (not queued) until unmuted. */
    @Volatile var muted = false
        set(value) {
            field = value
            if (value) {
                synchronized(early) { early.clear() }
                if (ready) runCatching { tts.stop() }
                pending.set(0)
                audio.abandonAudioFocusRequest(focus)
            }
        }

    fun say(text: String) {
        if (muted) return
        if (!ready) { synchronized(early) { early += text }; return }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        if (pending.getAndIncrement() == 0) audio.requestAudioFocus(focus)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "cue-${ids.incrementAndGet()}")
        HeartRateRepository.log.add("voice", text)
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            audio.abandonAudioFocusRequest(focus)
        }
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
        audio.abandonAudioFocusRequest(focus)
    }
}
