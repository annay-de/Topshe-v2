package org.annay.topshe

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
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
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

/**
 * Open-source voice models (sherpa-onnx, Apache-2.0), downloaded once into the app:
 *  - wake word: a small keyword spotter that listens for any word you type ("TOPSHE")
 *  - voice check: a speaker-embedding model (WeSpeaker ResNet34, trained on VoxCeleb)
 *  - speech-to-text: Vosk Indian-English small model
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
    private const val STT_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"

    // ---------- download ----------

    private fun open(url: String): InputStream {
        var u = URL(url)
        repeat(5) {   // follow redirects (GitHub -> its file host)
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            when (c.responseCode) {
                in 200..299 -> return c.inputStream.buffered(1 shl 16)
                in 300..399 -> u = URL(u, c.getHeaderField("Location"))
                else -> throw IllegalStateException("HTTP ${c.responseCode} for ${u.path.substringAfterLast('/')}")
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    fun downloadAll(ctx: Context, progress: (String) -> Unit) {
        if (!File(Store.wakeModel(ctx), "tokens.txt").exists()) {
            progress("Downloading wake-word model (~15 MB)...")
            val dest = Store.wakeModel(ctx).apply { deleteRecursively(); mkdirs() }
            TarArchiveInputStream(BZip2CompressorInputStream(open(KWS_URL))).use { tar ->
                var e = tar.nextEntry
                while (e != null) {
                    val rel = e.name.substringAfter('/', "")
                    if (!e.isDirectory && rel.isNotEmpty() && !rel.contains("..") && !rel.contains('/')) {
                        File(dest, rel).outputStream().use { tar.copyTo(it) }
                    }
                    e = tar.nextEntry
                }
            }
        }
        if (Store.speakerModel(ctx).length() < 1_000_000) {
            progress("Downloading voice-check model (~25 MB)...")
            var last: Exception? = null
            for (name in SPEAKER_FILES) {
                try {
                    val tmp = File(Store.speakerModel(ctx).path + ".tmp")
                    open(SPEAKER_BASE + name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    tmp.renameTo(Store.speakerModel(ctx))
                    last = null
                    break
                } catch (e: Exception) { last = e }
            }
            last?.let { throw it }
        }
        if (!File(Store.speechModel(ctx), "conf").exists() && !File(Store.speechModel(ctx), "am").exists()) {
            progress("Downloading speech-to-text model (~36 MB)...")
            val dest = Store.speechModel(ctx).apply { deleteRecursively(); mkdirs() }
            ZipInputStream(open(STT_URL)).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isNotEmpty() && !rel.contains("..")) {
                        val f = File(dest, rel)
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                    }
                    e = zip.nextEntry
                }
            }
        }
        progress("Voice models ready.")
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
     * Turns spellings like "TOPSHE" into the model's sub-word tokens (greedy longest match
     * on tokens.txt), one keyword line per spelling. All lines share the label @TOPSHE.
     */
    fun keywordLines(ctx: Context, spellings: String): List<String> {
        val vocab = File(Store.wakeModel(ctx), "tokens.txt").readLines()
            .mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull() }.toHashSet()
        return spellings.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.mapNotNull { spelling ->
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
            out.joinToString(" ") + " @TOPSHE"
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
