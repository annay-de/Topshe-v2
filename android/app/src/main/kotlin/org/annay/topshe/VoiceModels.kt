package org.annay.topshe

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/**
 * Open-source voice models (sherpa-onnx, Apache-2.0), downloaded once into the app:
 *  - wake word: a small keyword spotter that listens for any word you type ("ALFRED")
 *  - voice check: a speaker-embedding model (WeSpeaker ResNet34, trained on VoxCeleb)
 *  - speech-to-text: OpenAI Whisper "base" (multilingual, int8), run offline by sherpa-onnx
 *  - Bengali speech-to-text: a streaming zipformer trained on Bengali (Vosk), also by sherpa-onnx
 * No account, no key, no time limit.
 */
object VoiceModels {
    private const val KWS_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
    private const val SPEAKER_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/"
    private val SPEAKER_FILES = listOf(
        "wespeaker_en_voxceleb_resnet34.onnx",
        "wespeaker_en_voxceleb_resnet34_LM.onnx",
        "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
    )
    private const val BN_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09.tar.bz2"
    private const val STT_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2"

    // ---------- download ----------

    /** Opens a download (following GitHub's redirect to its file host). Says which host failed and why. */
    private fun open(url: String): Pair<InputStream, Long> {
        var u = URL(url)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 30_000; c.readTimeout = 90_000
            c.setRequestProperty("User-Agent", "Alfred-Android")
            val code = try { c.responseCode } catch (e: Exception) {
                throw IllegalStateException("can't reach ${u.host} (${e.javaClass.simpleName}: ${e.message})", e)
            }
            when (code) {
                in 200..299 -> return c.inputStream.buffered(1 shl 16) to c.contentLengthLong
                in 300..399 -> u = URL(u, c.getHeaderField("Location") ?: throw IllegalStateException("redirect without location from ${u.host}"))
                else -> throw IllegalStateException("${u.host} answered HTTP $code for ${u.path.substringAfterLast('/')}")
            }
        }
        throw IllegalStateException("too many redirects")
    }

    /** Wraps a download so [progress] hears "label: 12 / 40 MB" about every 2 MB. */
    private fun counted(input: InputStream, total: Long, label: String, progress: (String) -> Unit): InputStream =
        object : java.io.FilterInputStream(input) {
            var n = 0L
            var shown = -1L
            fun tick(k: Int) {
                if (k <= 0) return
                n += k
                val mb = n / (2L shl 20)
                if (mb != shown) {
                    shown = mb
                    progress(if (total > 0) "$label: ${n shr 20} / ${total shr 20} MB" else "$label: ${n shr 20} MB")
                }
            }
            override fun read(): Int = super.read().also { if (it >= 0) tick(1) }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { tick(it) }
        }

    /** Tries [block] up to 3 times (networks drop). The last error is passed on. */
    private fun <T> withRetries(label: String, progress: (String) -> Unit, block: () -> T): T {
        var last: Throwable? = null
        for (attempt in 1..3) {
            try { return block() } catch (e: Exception) {
                last = e
                if (attempt < 3) { progress("$label: problem (${e.message}), retrying..."); Thread.sleep(3000L * attempt) }
            }
        }
        throw IllegalStateException("$label failed: ${last?.message}", last)
    }

    /** Downloads a .tar.bz2 and keeps the top-level files (no sub-folders) that pass [keep]. */
    private fun untarBz2(url: String, dest: File, label: String, progress: (String) -> Unit, keep: (String) -> Boolean) {
        val tmp = File(dest.path + ".tmp").apply { deleteRecursively(); mkdirs() }
        val (raw, total) = open(url)
        var kept = 0
        TarArchiveInputStream(BZip2CompressorInputStream(counted(raw, total, label, progress))).use { tar ->
            var e = tar.nextEntry
            while (e != null) {
                val rel = e.name.substringAfter('/', "")
                if (!e.isDirectory && rel.isNotEmpty() && !rel.contains("..") && !rel.contains('/') && keep(rel)) {
                    File(tmp, rel).outputStream().use { tar.copyTo(it) }
                    kept++
                }
                e = tar.nextEntry
            }
        }
        if (kept == 0) throw IllegalStateException("$label: the download had no usable files")
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw IllegalStateException("$label: couldn't save the files")
    }

    fun downloadAll(ctx: Context, progress: (String) -> Unit) {
        if (!Store.wakeModelComplete(ctx)) {          // missing or incomplete: download it again
            withRetries("Wake-word model", progress) {
                untarBz2(KWS_URL, Store.wakeModel(ctx), "Wake-word model (1/3)", progress) { true }
            }
        }
        if (Store.speakerModel(ctx).length() < 1_000_000) {
            var last: Throwable? = null
            for (name in SPEAKER_FILES) {
                try {
                    withRetries("Voice-check model", progress) {
                        val tmp = File(Store.speakerModel(ctx).path + ".tmp")
                        val (input, total) = open(SPEAKER_BASE + name)
                        counted(input, total, "Voice-check model (2/3)", progress).use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 16) } }
                        if (tmp.length() < 1_000_000 || !tmp.renameTo(Store.speakerModel(ctx)))
                            throw IllegalStateException("voice-check file incomplete (${tmp.length()} bytes)")
                    }
                    last = null
                    break
                } catch (e: Exception) { last = e }
            }
            last?.let { throw it }
        }
        if (Store.sttModel(ctx).listFiles()?.any { it.name.contains("encoder") } != true) {
            withRetries("Speech-to-text model", progress) {
                untarBz2(STT_URL, Store.sttModel(ctx), "Speech-to-text model (3/3, the big one)", progress) { name ->
                    name.contains(".int8.") || name.endsWith("tokens.txt")
                }
            }
        }
        if (!File(Store.bnModel(ctx), "tokens.txt").exists()) {
            withRetries("Bengali speech model", progress) {
                untarBz2(BN_URL, Store.bnModel(ctx), "Bengali speech model (4/4)", progress) { name ->
                    name.endsWith(".onnx") || name == "tokens.txt"
                }
            }
        }
        progress("Voice models ready.")
    }

    /** Bengali speech-to-text (Vosk zipformer, run by sherpa-onnx), or null if not downloaded. */
    fun bengaliRecognizer(ctx: Context): OnlineRecognizer? {
        val dir = Store.bnModel(ctx)
        if (!File(dir, "tokens.txt").exists()) return null
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = pick(dir, "encoder"), decoder = pick(dir, "decoder"), joiner = pick(dir, "joiner"),
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 2,
            ),
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        )
        return OnlineRecognizer(null, config)
    }

    // ---------- wake word ----------

    private fun pick(dir: File, part: String): String {
        val files = dir.listFiles()?.filter { it.name.startsWith(part) && it.name.endsWith(".onnx") } ?: emptyList()
        // Prefer the smaller int8 version (faster, less battery)
        val f = files.firstOrNull { it.name.contains("int8") } ?: files.firstOrNull()
            ?: throw IllegalStateException("Wake-word model file missing: $part")
        return f.absolutePath
    }

    /**
     * Turns spellings like "ALFRED" into the model's sub-word tokens (greedy longest match
     * on tokens.txt), one keyword line per spelling. All lines share the label @ALFRED.
     */
    /** Exact sub-word spellings from the wake model's bpe.model (the greedy guess below can differ). */
    private val EXACT = mapOf(
        "ALFRED" to "▁A L F RE D", "HEY ALFRED" to "▁HE Y ▁A L F RE D", "OK ALFRED" to "▁O K ▁A L F RE D",
        "AL FRED" to "▁A L ▁F RE D", "ELFRED" to "▁E L F RE D", "TOP SHAY" to "▁TO P ▁SHA Y", "TOP SHE" to "▁TO P ▁SHE",
        "TOPSHE" to "▁TO P S HE")

    fun keywordLines(ctx: Context, spellings: String): List<String> {
        val vocab = File(Store.wakeModel(ctx), "tokens.txt").readLines()
            .mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull() }.toHashSet()
        return spellings.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.mapNotNull { spelling ->
            // The model's own spelling (from its bpe.model) for the usual phrases, with a sensitivity boost
            EXACT[spelling]?.let { return@mapNotNull "$it :2.0 @ALFRED" }
            val out = mutableListOf<String>()
            for (word in spelling.split(Regex("\\s+"))) {
                val s = "▁" + word.filter { it.isLetter() || it == '\'' }
                var i = 0
                while (i < s.length) {
                    var j = s.length
                    while (j > i && s.substring(i, j) !in vocab) j--
                    if (j == i) return@mapNotNull null   // can't spell it with this model
                    out += s.substring(i, j)
                    i = j
                }
            }
            out.joinToString(" ") + " @ALFRED"
        }.distinct()
    }

    fun keywordSpotter(ctx: Context, prefs: Prefs): KeywordSpotter {
        val dir = Store.wakeModel(ctx)
        val lines = keywordLines(ctx, prefs.wakeSpellings)
        require(lines.isNotEmpty()) { "Couldn't spell the wake word with this model" }
        val kwFile = File(ctx.filesDir, "topshe_keywords.txt").apply { writeText(lines.joinToString("\n") + "\n") }
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = pick(dir, "encoder"),
                    decoder = pick(dir, "decoder"),
                    joiner = pick(dir, "joiner"),
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 1,
                modelType = "zipformer2",
            ),
            keywordsFile = kwFile.absolutePath,
            keywordsScore = 2.0f,
            keywordsThreshold = prefs.wakeStrictness,
        )
        return KeywordSpotter(null, config)
    }

    // ---------- speech-to-text ----------

    fun speechRecognizer(ctx: Context, language: String): OfflineRecognizer {
        val dir = Store.sttModel(ctx)
        val files = dir.listFiles()?.toList() ?: emptyList()
        fun find(part: String) = (files.firstOrNull { it.name.contains(part) && it.name.contains("int8") }
            ?: files.firstOrNull { it.name.contains(part) && it.name.endsWith(".onnx") })?.absolutePath
            ?: throw IllegalStateException("Speech model file missing: $part")
        val tokens = files.firstOrNull { it.name.endsWith("tokens.txt") }?.absolutePath
            ?: throw IllegalStateException("Speech model tokens missing")
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = find("encoder"), decoder = find("decoder"),
                    language = language, task = "transcribe"),
                tokens = tokens, numThreads = 4, modelType = "whisper",
            ),
        )
        return OfflineRecognizer(null, config)
    }

    // ---------- voice check ----------

    fun speakerExtractor(ctx: Context) = SpeakerEmbeddingExtractor(null,
        SpeakerEmbeddingExtractorConfig(model = Store.speakerModel(ctx).absolutePath, numThreads = 2))

    fun toFloats(pcm: ShortArray) = FloatArray(pcm.size) { pcm[it] / 32768f }

    /** Voice fingerprint of a clip, normalised, or null if the clip is too short. */
    fun embed(ex: SpeakerEmbeddingExtractor, pcm: ShortArray): FloatArray? {
        if (pcm.size < SAMPLE_RATE / 2) return null
        val s = ex.createStream()
        return try {
            s.acceptWaveform(toFloats(pcm), SAMPLE_RATE)
            s.inputFinished()
            if (ex.isReady(s)) normalise(ex.compute(s)) else null
        } finally {
            s.release()
        }
    }

    fun normalise(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val d = (sqrt(n) + 1e-9).toFloat()
        return FloatArray(v.size) { v[it] / d }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
        return s
    }

    fun saveProfile(ctx: Context, v: FloatArray) =
        DataOutputStream(Store.voiceProfile(ctx).outputStream()).use { o -> o.writeInt(v.size); v.forEach { o.writeFloat(it) } }

    fun loadProfile(ctx: Context): FloatArray =
        DataInputStream(Store.voiceProfile(ctx).inputStream()).use { i -> FloatArray(i.readInt()) { i.readFloat() } }
}
