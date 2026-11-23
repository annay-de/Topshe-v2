package org.annay.topshe

import android.content.Context
import org.json.JSONObject
import java.io.File

/** The conversation history shown on the Home screen: voice and typed, newest last. */
object ChatLog {
    data class Msg(val fromYou: Boolean, val text: String, val via: String, val at: Long)

    private const val KEEP = 400
    private fun file(ctx: Context) = File(Store.memoryDir(ctx), "chat.jsonl")

    @Synchronized
    fun add(ctx: Context, fromYou: Boolean, text: String, via: String) {
        if (text.isBlank()) return
        val f = file(ctx)
        f.appendText(JSONObject().put("you", fromYou).put("text", text).put("via", via)
            .put("at", System.currentTimeMillis()).toString() + "\n")
        if (f.length() > 400_000) {                       // keep the file small
            val lines = f.readLines().filter { it.isNotBlank() }
            f.writeText(lines.takeLast(KEEP).joinToString("\n") + "\n")
        }
    }

    @Synchronized
    fun recent(ctx: Context, n: Int = 150): List<Msg> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.takeLast(n).mapNotNull { l ->
            runCatching {
                val o = JSONObject(l)
                Msg(o.getBoolean("you"), o.getString("text"), o.optString("via"), o.optLong("at"))
            }.getOrNull()
        }
    }

    /** Changes when a message is added (cheap check for the screen's refresh). */
    fun stamp(ctx: Context): Long = file(ctx).let { if (it.exists()) it.length() else 0L }

    @Synchronized
    fun clear(ctx: Context) { file(ctx).delete() }
}

/**
 * Typed requests on the Home screen go through the same steps as voice: your corrections first,
 * then the brain, then the same actions (calls and messages still ask yes/no - as buttons here).
 * They're logged for the weekly learning like voice requests.
 */
class ChatEngine(private val ctx: Context, private val speaker: Speaker) {
    private val memory = Memory(ctx)
    private val music = Music(ctx, speaker)
    private val actions = Actions(ctx, speaker, memory, music)

    /** Returns false if the request was "stop listening". */
    suspend fun handle(text: String, asker: Actions.Asker): Boolean {
        if (!Brain.load(ctx)) {
            speaker.say("I don't have a brain on the phone yet. Finish setup in Settings, step 5.")
            return true
        }
        // A typed request is exact: English as one line; Bengali as the Bengali "hearing"
        val input = if (Brain.dualHearing && Hearing.hasBengali(text)) Hearing("", text).forBrain(true) else text
        val learned = memory.correctionFor(input)
        val cmd = learned ?: playRule(text) ?: Brain.command(input, memory.facts())
        if (cmd == null) {
            speaker.say("Sorry, I didn't understand that.")
            memory.log(input, null, "failed")
            return true
        }
        val r = actions.run(cmd, text, asker)
        memory.log(input, cmd, r.outcome)
        return r.keepListening
    }

    /** "play X" / "play X on youtube" straight to music (same rule as voice). */
    private fun playRule(t: String): JSONObject? {
        val s = t.lowercase().trim().removeSuffix(".")
        return when {
            s.startsWith("play ") && s.endsWith(" on youtube") -> JSONObject().put("action", "media").put("op", "play")
                .put("query", s.removePrefix("play ").removeSuffix(" on youtube")).put("source", "youtube")
            s.startsWith("play ") && s !in setOf("play music", "play some music", "play next song") -> JSONObject().put("action", "media")
                .put("op", "play").put("query", s.removePrefix("play ").removeSuffix(" on spotify")).put("source", "spotify")
            else -> null
        }
    }
}
