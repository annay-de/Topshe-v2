package org.annay.topshe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What Alfred learns from you, instantly, on the phone (no retraining needed):
 *  - facts:        "remember that my shuttle leaves at 8:10" -> added to every prompt
 *  - corrections:  "no, I meant ..." -> next time the same words do the corrected thing
 *  - contacts:     which "Rahul" you picked -> offered first next time
 *  - interactions: everything you said and what happened -> uploaded weekly for retraining
 */
class Memory(private val ctx: Context) {
    private val dir = Store.memoryDir(ctx)
    private val factsFile = File(dir, "facts.json")
    private val correctionsFile = File(dir, "corrections.json")
    private val contactsFile = File(dir, "contacts.json")
    val interactionsFile = File(dir, "interactions.jsonl")

    // ---------- facts ----------

    @Synchronized
    fun facts(): List<String> = runCatching {
        val a = JSONObject(factsFile.readText()).getJSONArray("facts")
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun saveFacts(list: List<String>) {
        factsFile.writeText(JSONObject().put("facts", JSONArray(list.takeLast(MAX_FACTS))).toString(1))
    }

    fun remember(fact: String): Boolean {
        val f = fact.trim().removeSuffix(".")
        if (f.length < 3) return false
        val list = facts().filter { similarity(it, f) < 0.8 }   // replaces an older version of the same fact
        saveFacts(list + f)
        return true
    }

    /** Removes exactly this fact (the delete button on the Memory screen). */
    fun removeFact(fact: String) = saveFacts(facts().filter { it != fact })

    /** Removes facts that match what you said; returns how many were removed. */
    fun forget(what: String): Int {
        val list = facts()
        val keep = list.filter { similarity(it, what) < 0.45 && !it.lowercase().contains(what.lowercase().trim()) }
        saveFacts(keep)
        return list.size - keep.size
    }

    // ---------- corrections ----------

    @Synchronized
    private fun corrections(): JSONObject = runCatching { JSONObject(correctionsFile.readText()) }.getOrDefault(JSONObject())

    @Synchronized
    fun addCorrection(heard: String, action: JSONObject) {
        val c = corrections()
        c.put(norm(heard), action)
        correctionsFile.writeText(c.toString())
    }

    /** If you corrected something very similar before, returns the corrected action. */
    fun correctionFor(heard: String): JSONObject? {
        val c = corrections()
        val n = norm(heard)
        c.optJSONObject(n)?.let { return it }
        var best: JSONObject? = null
        var bestScore = 0.0
        for (k in c.keys()) {
            val s = similarity(k, n)
            if (s > bestScore) { bestScore = s; best = c.optJSONObject(k) }
        }
        return if (bestScore >= 0.88) best else null
    }

    // ---------- contacts ----------

    @Synchronized
    fun contactChoice(spoken: String): String? =
        runCatching { JSONObject(contactsFile.readText()).optString(norm(spoken)).ifBlank { null } }.getOrNull()

    @Synchronized
    fun saveContactChoice(spoken: String, chosen: String) {
        val o = runCatching { JSONObject(contactsFile.readText()) }.getOrDefault(JSONObject())
        o.put(norm(spoken), chosen)
        contactsFile.writeText(o.toString())
    }

    // ---------- interaction log (training data) ----------

    @Synchronized
    fun log(heard: String, action: JSONObject?, outcome: String, correctedTo: JSONObject? = null) {
        val o = JSONObject()
            .put("ts", System.currentTimeMillis() / 1000)
            .put("heard", heard)
            .put("outcome", outcome)   // ok | confirmed | cancelled | failed | corrected | chat
        action?.let { o.put("action", it) }
        correctedTo?.let { o.put("corrected_to", it) }
        interactionsFile.appendText(o.toString() + "\n")
    }

    fun interactionCount() = if (interactionsFile.exists()) interactionsFile.readLines().count { it.isNotBlank() } else 0

    companion object {
        const val MAX_FACTS = 60

        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

        /** Character-trigram similarity, 0..1 (robust to small speech-to-text differences). */
        fun similarity(a: String, b: String): Double {
            fun grams(s: String): Set<String> {
                val t = "  " + norm(s) + " "
                return (0 until maxOf(t.length - 2, 0)).map { t.substring(it, it + 3) }.toSet()
            }
            val x = grams(a); val y = grams(b)
            if (x.isEmpty() || y.isEmpty()) return 0.0
            return x.intersect(y).size.toDouble() / x.union(y).size
        }
    }
}
