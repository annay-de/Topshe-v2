package org.annay.topshe

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.OnlineStream
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.max
import kotlin.math.sqrt

const val SAMPLE_RATE = 16000

/**
 * Topshe's ear. One owner of the microphone, start to finish:
 *  1. A small open-source keyword spotter listens for "Topshe" (the only thing running while idle).
 *  2. After the wake word, records your command until you stop talking.
 *  3. A voice-fingerprint model checks the voice (wake word + command) is yours.
 *  4. Vosk turns the command into text, fully offline.
 *
 * Fix vs v1: v1 re-ran the wake-word engine on a 3 s window every 0.5 s (6x the work
 * and repeated triggers). Here each audio chunk goes through the spotter exactly once.
 */
class Ear(ctx: Context, prefs: Prefs) {
    private val kws: KeywordSpotter = VoiceModels.keywordSpotter(ctx, prefs)
    private var kwsStream: OnlineStream = kws.createStream()
    private val speaker = VoiceModels.speakerExtractor(ctx)
    private val profile = VoiceModels.loadProfile(ctx)
    private val speechModel = Model(Store.speechModel(ctx).absolutePath)

    private val frame = ShortArray(SAMPLE_RATE / 10)   // 100 ms chunks
    private val preroll = ShortRing(SAMPLE_RATE * 3 / 2)   // last 1.5 s of audio (holds the wake word)
    private var rec: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun open() {
        if (rec != null) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SAMPLE_RATE * 2))
        check(r.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not start" }
        r.startRecording()
        rec = r
    }

    private fun readFully(buf: ShortArray): Boolean {
        val r = rec ?: return false
        var off = 0
        while (off < buf.size) {
            val n = r.read(buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    /** Blocks until "Topshe" is heard. Returns false if stopped. */
    fun waitForWake(keepGoing: () -> Boolean): Boolean {
        while (keepGoing()) {
            if (!readFully(frame)) return false
            preroll.push(frame)
            kwsStream.acceptWaveform(VoiceModels.toFloats(frame), SAMPLE_RATE)
            var hit = false
            while (kws.isReady(kwsStream)) {
                kws.decode(kwsStream)
                if (kws.getResult(kwsStream).keyword.isNotBlank()) { kws.reset(kwsStream); hit = true }
            }
            if (hit) return true
        }
        return false
    }

    /** Throws away audio that built up while Topshe was busy or talking. */
    fun flush(ms: Int = 300) {
        val buf = ShortArray(SAMPLE_RATE * ms / 1000)
        readFully(buf)
        preroll.clear()
        // Fresh wake-word stream so half-heard audio from before doesn't carry over
        kwsStream.release()
        kwsStream = kws.createStream()
    }

    class Utterance(val wake: ShortArray, val speech: ShortArray) {
        val all: ShortArray get() = wake + speech
    }

    /**
     * Records until you stop talking.
     * @param waitMs how long to wait for you to start speaking
     * @param endSilenceMs this much silence after speech = you're done
     */
    fun capture(waitMs: Int = 4000, endSilenceMs: Int = 900, maxMs: Int = 12000): Utterance {
        val wake = preroll.toArray()
        val chunk = ShortArray(SAMPLE_RATE * 30 / 1000)   // 30 ms
        val floor = noiseFloor(wake)
        val threshold = max(floor * 2.5, 300.0)
        val out = ArrayList<Short>(SAMPLE_RATE * 4)
        var started = false
        var silentMs = 0
        var totalMs = 0
        while (totalMs < maxMs) {
            if (!readFully(chunk)) break
            totalMs += 30
            val loud = rms(chunk) > threshold
            if (loud) { started = true; silentMs = 0 } else silentMs += 30
            if (started) for (s in chunk) out.add(s)
            if (!started && totalMs >= waitMs) break
            if (started && silentMs >= endSilenceMs) break
        }
        preroll.clear()
        return Utterance(wake, out.toShortArray())
    }

    /** Voice-match score (cosine, about -1..1; you are usually 0.6+) or null if too little audio. */
    fun voiceScore(pcm: ShortArray): Float? =
        runCatching { VoiceModels.embed(speaker, pcm)?.let { VoiceModels.cosine(it, profile) } }.getOrNull()

    /** Offline speech-to-text. */
    fun transcribe(pcm: ShortArray): String {
        if (pcm.isEmpty()) return ""
        val r = Recognizer(speechModel, SAMPLE_RATE.toFloat())
        return try {
            r.acceptWaveForm(pcm, pcm.size)
            JSONObject(r.finalResult).optString("text").trim()
        } finally {
            r.close()
        }
    }

    fun close() {
        rec?.run { runCatching { stop() }; release() }
        rec = null
    }

    fun release() {
        close()
        runCatching { kwsStream.release() }
        runCatching { kws.release() }
        runCatching { speaker.release() }
        runCatching { speechModel.close() }
    }

    private fun rms(x: ShortArray): Double {
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return sqrt(s / max(x.size, 1))
    }

    private fun noiseFloor(x: ShortArray): Double {
        val n = 480
        if (x.size < n * 4) return 300.0
        val levels = (0 until x.size / n).map { rms(x.copyOfRange(it * n, (it + 1) * n)) }.sorted()
        return levels[levels.size / 5]   // quietest 20% = background noise
    }
}

/** Fixed-size ring buffer of audio samples. */
class ShortRing(private val capacity: Int) {
    private val buf = ShortArray(capacity)
    private var pos = 0
    private var filled = 0

    fun push(x: ShortArray) {
        for (s in x) {
            buf[pos] = s
            pos = (pos + 1) % capacity
            if (filled < capacity) filled++
        }
    }

    fun toArray(): ShortArray {
        val out = ShortArray(filled)
        val start = (pos - filled + capacity) % capacity
        for (i in 0 until filled) out[i] = buf[(start + i) % capacity]
        return out
    }

    fun clear() { pos = 0; filled = 0 }
}
