package org.annay.topshe

import org.json.JSONObject

/** The C++ llama.cpp bridge (src/main/cpp/topshe_llama.cpp). */
object NativeLlama {
    init { System.loadLibrary("topshe_llama") }
    external fun load(path: String): Long
    external fun unload(handle: Long)
    external fun generate(handle: Long, prompt: String, maxTokens: Int, temp: Float, threads: Int): ByteArray
}

/**
 * Topshe's two models:
 *  - command model (1.5B, fast): turns your words into an action
 *  - chat model (3B, smarter): answers questions and talks. Optional; if it isn't
 *    installed, the command model answers too.
 */
object Brain {
    // Must match the training notebook exactly (training/make_dataset_v2.py)
    const val CMD_SYSTEM =
        "You are Topshe, a phone assistant. Turn the user's request into ONE JSON object. " +
        "Actions: set_alarm, set_timer, set_reminder, open_app, call, message, tasker, stop, chat. " +
        "Use chat for questions or small talk. Reply with JSON only."

    // Training used the first sentence pair; the last sentence sets the plain, no-lecture tone.
    const val CHAT_SYSTEM =
        "You are Topshe, a friendly assistant. Answer in 1-3 short, simple sentences. " +
        "Talk to Annay plainly, like an adult. No lectures or disclaimers."

    private const val THREADS = 4

    @Volatile private var cmdModel = 0L
    @Volatile private var chatModel = 0L

    val loaded get() = cmdModel != 0L

    /** Loads the command model, and the chat model if it's on the phone. */
    fun load(cmdPath: String, chatPath: String?): Boolean {
        if (cmdModel == 0L) cmdModel = NativeLlama.load(cmdPath)
        if (chatModel == 0L && chatPath != null) chatModel = NativeLlama.load(chatPath)
        return cmdModel != 0L
    }

    fun unload() {
        if (cmdModel != 0L) NativeLlama.unload(cmdModel)
        if (chatModel != 0L) NativeLlama.unload(chatModel)
        cmdModel = 0L; chatModel = 0L
    }

    private fun chatml(system: String, user: String) =
        "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

    private fun run(model: Long, system: String, user: String, maxTokens: Int, temp: Float) =
        String(NativeLlama.generate(model, chatml(system, user), maxTokens, temp, THREADS), Charsets.UTF_8).trim()

    /** Returns the action JSON, or null if the model produced something unreadable. */
    fun command(text: String): JSONObject? = parseJson(run(cmdModel, CMD_SYSTEM, text, 96, 0f))

    fun chat(question: String): String =
        run(if (chatModel != 0L) chatModel else cmdModel, CHAT_SYSTEM, question, 200, 0.6f)
        .ifBlank { "Sorry, I don't have an answer for that." }

    /** Lenient: finds the first balanced {...} in the output (idea kept from Topshe v1). */
    fun parseJson(raw: String): JSONObject? {
        val text = raw.replace(Regex("```(?:json)?"), "")
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return runCatching { JSONObject(text.substring(start, i + 1)) }.getOrNull()
            }
        }
        return null
    }
}
