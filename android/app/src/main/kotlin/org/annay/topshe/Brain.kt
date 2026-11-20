package org.annay.topshe

import org.json.JSONObject

/** The C++ llama.cpp bridge (src/main/cpp/topshe_llama.cpp). */
object NativeLlama {
    init { System.loadLibrary("topshe_llama") }
    external fun load(path: String): Boolean
    external fun unload()
    external fun generate(prompt: String, maxTokens: Int, temp: Float, threads: Int): ByteArray
}

/** Topshe's model: turns your words into an action, or answers a question. */
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

    @Volatile var loaded = false
        private set

    fun load(path: String): Boolean {
        loaded = NativeLlama.load(path)
        return loaded
    }

    fun unload() {
        NativeLlama.unload()
        loaded = false
    }

    private fun chatml(system: String, user: String) =
        "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

    private fun run(system: String, user: String, maxTokens: Int, temp: Float) =
        String(NativeLlama.generate(chatml(system, user), maxTokens, temp, THREADS), Charsets.UTF_8).trim()

    /** Returns the action JSON, or null if the model produced something unreadable. */
    fun command(text: String): JSONObject? = parseJson(run(CMD_SYSTEM, text, 96, 0f))

    fun chat(question: String): String = run(CHAT_SYSTEM, question, 160, 0.6f)
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
