package org.annay.topshe

import org.json.JSONObject

/**
 * Instant answers for the everyday commands, so they never wait for the brain:
 * music controls, "play X", timers, alarms with a clear time, opening apps, calls, and "stop listening".
 * Works on English words and on the Bengali hearing (Bengali script).
 *
 * Returns the same JSON the brain would give, so the action, the log and the weekly learning are
 * identical. Anything that isn't a clear match goes to the brain as before.
 */
object QuickRules {

    private fun clean(s: String) = s.lowercase().trim()
        .replace(Regex("^(hey |ok |okay )?topshe[,!.]?\\s*"), "")
        .replace(Regex("[?!।,]+$"), "").removeSuffix(".").replace(Regex("\\s+"), " ").trim()

    private fun media(op: String) = JSONObject().put("action", "media").put("op", op)
    private fun play(q: String, source: String = "spotify") = media("play").put("query", q).put("source", source)

    /** English words or Bengali script -> an action, or null if it isn't a clear everyday command. */
    fun match(raw: String): JSONObject? {
        val t = clean(raw)
        if (t.isEmpty()) return null
        return if (Hearing.hasBengali(t)) bengali(t) else english(t)
    }

    /** Small talk that doesn't need the brain. */
    fun reply(raw: String): String? {
        val t = clean(raw).replace(Regex("[^a-z ]"), "").trim()
        return when {
            t.matches(Regex("^(hi+|hello+|hey+|hii+|yo|hola|namaskar|nomoshkar)( there)?$")) -> "Hey Annay! What should I do?"
            t.matches(Regex("^good (morning|afternoon|evening)$")) -> "Good ${t.removePrefix("good ")}, Annay!"
            t.matches(Regex("^(thanks|thank you|thank you so much|thanks a lot|ty|great|nice|cool|awesome)$")) -> "Anytime!"
            t.matches(Regex("^(what can you do|help|what do you do)$")) ->
                "I can play music on Spotify or YouTube, pause or skip songs, set alarms, timers and reminders, " +
                "open apps, call or message people, remember things about you, and chat. In English or Bengali."
            else -> null
        }
    }

    // ---------------- English ----------------

    private val generic = Regex("^(play|resume|start|continue|put on)( the| some| my)?( music| songs?| spotify| playlist| a song)?( please)?$")

    private fun english(t: String): JSONObject? {
        when {
            t.matches(generic) || t in setOf("music", "play music on spotify", "resume music", "play something") -> return play("")
            t.matches(Regex("^(pause|stop)( the)?( music| song| it| playing| spotify| video| this)?( please)?$")) && t != "stop" -> return media("pause")
            t.matches(Regex("^(next|skip)( song| track| one| this)?( please)?$")) || t == "play next song" || t == "play the next song" -> return media("next")
            t.matches(Regex("^(previous|last|go back)( song| track| one)?( please)?$")) || t == "play the previous song" -> return media("previous")
            t.matches(Regex("^(volume up|louder|increase (the )?volume|turn (it )?up)( please)?$")) -> return media("volume_up")
            t.matches(Regex("^(volume down|quieter|softer|decrease (the )?volume|lower (the )?volume|turn (it )?down)( please)?$")) -> return media("volume_down")
            t.matches(Regex("^(mute|mute (the )?(volume|music))$")) -> return media("mute")
            t.matches(Regex("^(stop listening|go to sleep|sleep|that's all|thats all|bye|goodbye|good night topshe)$")) -> return JSONObject().put("action", "stop")
        }
        // play X [on youtube | on spotify]
        Regex("^play (.+?)$").find(t)?.let { m ->
            var q = m.groupValues[1].trim()
            val yt = Regex("\\s+(on|in|from) (youtube|you tube|brave)$")
            if (yt.containsMatchIn(q)) return play(yt.replace(q, "").trim(), "youtube")
            q = Regex("\\s+(on|in|from) spotify$").replace(q, "").trim()
            if (q.isNotEmpty() && !q.contains(" and ")) return play(q)
        }
        timer(t)?.let { return it }
        // A plain question goes straight to the chat brain (skips the command brain: answers start sooner)
        if (t.matches(Regex("^(what|what's|whats|who|who's|why|how|when|where|which|explain|define|tell me about|tell me a|is|are|does|do|should|could|can you explain)\\b.*")) &&
            !Regex("\\b(remind|reminder|alarm|timer|call|message|text|whatsapp|play|playing|song|music|open|volume|set|wake)\\b").containsMatchIn(t))
            return JSONObject().put("action", "chat").put("query", raw.trim())
        alarm(t)?.let { return it }
        // open / launch X (a short app name)
        Regex("^(open|launch|start) (the )?([a-z0-9 .+&'-]{2,30}?)( app)?( please)?$").find(t)?.let { m ->
            val app = m.groupValues[3].trim()
            if (app.split(" ").size <= 3 && !app.contains(" and ") && app !in setOf("music", "the music", "a timer", "timer")) return JSONObject().put("action", "open_app").put("app", app)
        }
        // call X
        Regex("^(call|phone|ring|dial) ([a-z .'-]{2,30}?)( please| now)?$").find(t)?.let { m ->
            val who = m.groupValues[2].trim()
            if (who.split(" ").size <= 3 && !who.startsWith("me ") && who !in setOf("me", "it", "him", "her", "them")) return JSONObject().put("action", "call").put("contact", who)
        }
        return null
    }

    private val enNum = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15,
        "twenty" to 20, "twenty five" to 25, "thirty" to 30, "forty" to 40, "forty five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90)

    private fun enNumber(s: String): Int? = s.trim().toIntOrNull() ?: enNum[s.trim()]

    private fun timer(t: String): JSONObject? {
        val unit = "(seconds?|secs?|minutes?|mins?|hours?|hrs?)"
        val num = "(\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty five|twenty|thirty|forty five|forty|fifty|sixty|ninety)"
        fun secs(n: Int, u: String) = n * when { u.startsWith("h") -> 3600; u.startsWith("m") -> 60; else -> 1 }
        fun out(s: Int) = if (s in 1..86_400) JSONObject().put("action", "set_timer").put("seconds", s) else null
        Regex("^(set |start |put )?(a |an )?timer (for |of )?$num $unit( please)?$").find(t)?.let {
            val n = enNumber(it.groupValues[4]) ?: return null
            return out(secs(n, it.groupValues[5]))
        }
        Regex("^(set |start |put )?(a |an )?$num[ -]$unit timer( please)?$").find(t)?.let {
            val n = enNumber(it.groupValues[3]) ?: return null
            return out(secs(n, it.groupValues[4]))
        }
        Regex("^(set |start )?(a )?timer (for )?half an hour$|^half an hour timer$").find(t)?.let { return out(1800) }
        return null
    }

    /** Only alarms with a clear time (7 am, 6:30 pm, 18:30, 7 in the morning) - anything else goes to the brain. */
    private fun alarm(t: String): JSONObject? {
        val m = Regex("^(set (an |the |my )?alarm (for|at)|alarm (for|at)|wake me up at|wake me at|wake me up by) " +
                "(\\d{1,2})(?:[:.](\\d{2}))? ?(am|a\\.m\\.?|a m|pm|p\\.m\\.?|p m|in the morning|in the evening|at night|tonight)?( tomorrow)?( please)?$").find(t)
            ?: return null
        var h = m.groupValues[5].toInt()
        val min = m.groupValues[6].ifEmpty { "0" }.toInt()
        val part = m.groupValues[7]
        if (min !in 0..59) return null
        when {
            part.startsWith("a") || part == "in the morning" -> { if (h !in 1..12) return null; if (h == 12) h = 0 }
            part.startsWith("p") || part.contains("evening") || part.contains("night") -> { if (h !in 1..12) return null; if (h != 12) h += 12 }
            h in 13..23 || (h == 0 && m.groupValues[6].isNotEmpty()) -> {}     // 24-hour time is clear
            else -> return null                                                   // "alarm at 7": am or pm? ask the brain
        }
        return JSONObject().put("action", "set_alarm").put("hour", h).put("minute", min)
    }

    // ---------------- Bengali ----------------

    private val bnNum = mapOf("এক" to 1, "একটা" to 1, "দুই" to 2, "দু" to 2, "তিন" to 3, "চার" to 4, "পাঁচ" to 5, "ছয়" to 6, "ছয়" to 6, "ছ" to 6,
        "সাত" to 7, "আট" to 8, "নয়" to 9, "নয়" to 9, "দশ" to 10, "এগারো" to 11, "বারো" to 12, "তেরো" to 13, "চোদ্দ" to 14,
        "পনেরো" to 15, "ষোলো" to 16, "সতেরো" to 17, "আঠারো" to 18, "উনিশ" to 19, "কুড়ি" to 20, "বিশ" to 20, "পঁচিশ" to 25,
        "তিরিশ" to 30, "ত্রিশ" to 30, "পঁয়ত্রিশ" to 35, "চল্লিশ" to 40, "পঁয়তাল্লিশ" to 45, "পঞ্চাশ" to 50, "পঞ্চান্ন" to 55,
        "ষাট" to 60, "নব্বই" to 90)

    private fun bnNumber(s: String): Int? {
        val digits = s.map { c -> if (c in '০'..'৯') '0' + (c - '০') else c }.joinToString("")
        return digits.toIntOrNull() ?: bnNum.entries.firstOrNull { nk(it.key) == s }?.value
    }

    /** Same letters, one spelling: য়/ড়/ঢ় can be one character or two. */
    private fun nk(s: String) = s.replace("\u09AF\u09BC", "\u09DF").replace("\u09A1\u09BC", "\u09DC").replace("\u09A2\u09BC", "\u09DD")

    private fun anyOf(t: String, vararg p: String) = p.map { nk(it) }.any { t == it || t == "$it প্লিজ" || t == "একটু $it" }

    private fun bengali(t0: String): JSONObject? {
        val t = nk(t0).replace("টপসে", "").trim().replace(Regex("\\s+"), " ")
        when {
            anyOf(t, "গান চালা", "গান চালাও", "গান চালান", "গান লাগা", "গান লাগাও", "গান বাজা", "গান বাজাও", "কিছু একটা গান চালা",
                "কিছু একটা গান চালাও", "একটা গান চালা", "একটা গান চালাও", "মিউজিক চালা", "মিউজিক চালাও", "মিউজিক চালু কর",
                "মিউজিক চালু করো", "আবার গান চালাও", "আবার গান চালা", "গান শোনা", "গান শোনাও", "গান চালু কর", "গান চালু করো") -> return play("")
            anyOf(t, "গানটা বন্ধ কর", "গানটা বন্ধ করো", "গান বন্ধ কর", "গান বন্ধ করো", "গান থামা", "গান থামাও", "গানটা থামা",
                "গানটা থামাও", "পজ কর", "পজ করো", "গানটা পজ কর", "গানটা পজ করো", "মিউজিক বন্ধ কর", "মিউজিক বন্ধ করো",
                "মিউজিক বন্ধ করুন", "গান বন্ধ করুন") -> return media("pause")
            anyOf(t, "পরের গান", "পরের গানটা", "পরেরটা চালা", "পরেরটা চালাও", "পরের গানটা চালা", "পরের গানটা চালাও", "স্কিপ কর",
                "স্কিপ করো", "এটা বাদ দে", "এটা বাদ দাও", "নেক্সট", "নেক্সট গান") -> return media("next")
            anyOf(t, "আগের গান", "আগের গানটা", "আগের গানটা চালা", "আগের গানটা চালাও", "আগেরটা আবার চালা", "আগেরটা চালাও",
                "আগের গানে ফিরে যা") -> return media("previous")
            anyOf(t, "আওয়াজ বাড়া", "আওয়াজ বাড়াও", "আওয়াজ বাড়া", "আওয়াজ বাড়াও", "ভলিউম বাড়া", "ভলিউম বাড়াও", "জোরে কর", "জোরে করো") -> return media("volume_up")
            anyOf(t, "আওয়াজ কমা", "আওয়াজ কমাও", "আওয়াজ কমা", "আওয়াজ কমাও", "ভলিউম কমা", "ভলিউম কমাও", "আস্তে কর", "আস্তে করো") -> return media("volume_down")
            anyOf(t, "মিউট কর", "মিউট করো", "মিউট করে দাও", "মিউট করে দে") -> return media("mute")
            anyOf(t, "চুপ কর", "চুপ করো", "এখন চুপ কর", "এবার ঘুমো", "ঘুমিয়ে পড়", "টপসে অফ", "শোনা বন্ধ কর", "শোনা বন্ধ করো",
                "আর শুনতে হবে না") -> return JSONObject().put("action", "stop")
        }
        // "দশ মিনিটের টাইমার দে", "৫ মিনিট গোন", "আধ ঘণ্টার টাইমার"
        if (t.contains("টাইমার") || t.contains("timer") || t.endsWith("গোন")) {
            if (t.startsWith("আধ ঘণ্টা") || t.startsWith("আধ ঘন্টা")) return JSONObject().put("action", "set_timer").put("seconds", 1800)
            if (t.startsWith(nk("দেড় ঘণ্টা")) || t.startsWith(nk("দেড় ঘন্টা"))) return JSONObject().put("action", "set_timer").put("seconds", 5400)
            Regex("^(\\S+) (মিনিট|সেকেন্ড|ঘণ্টা|ঘন্টা)").find(t)?.let { m ->
                val n = bnNumber(m.groupValues[1]) ?: return null
                val mult = when (m.groupValues[2]) { "মিনিট" -> 60; "সেকেন্ড" -> 1; else -> 3600 }
                if (n * mult in 1..86_400) return JSONObject().put("action", "set_timer").put("seconds", n * mult)
            }
        }
        return null
    }
}
