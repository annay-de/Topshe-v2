package org.annay.topshe

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Topshe's voice: Android's built-in text-to-speech (Indian English). */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val tts = TextToSpeech(ctx.applicationContext, this)
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale("en", "IN"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.UK)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { id?.let { pending.remove(it)?.complete(Unit) } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { id?.let { pending.remove(it)?.complete(Unit) } }
            })
        }
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    /** Speaks and waits until finished (so the mic doesn't hear Topshe itself). */
    suspend fun say(text: String) {
        if (!ready.await() || text.isBlank()) return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        withTimeoutOrNull(30_000) { done.await() }
    }

    /** Short beep: "I heard Topshe, go ahead". */
    fun beep() { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150) }

    fun shutdown() {
        tts.shutdown()
        tone?.release()
    }
}
