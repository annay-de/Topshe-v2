package org.annay.topshe

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.telephony.SmsManager
import org.json.JSONObject
import java.util.Calendar

/**
 * Runs the action the model picked. Only these actions exist - Topshe can't pay, delete,
 * install or change anything outside this list.
 *
 * Calls and messages always ask "yes or no" first. If more than one contact matches a
 * name, Topshe lists them and asks which one (so it never rings the wrong Rahul).
 */
class Actions(private val ctx: Context, private val speaker: Speaker,
              private val memory: Memory, private val music: Music) {

    /** Asks a question out loud and returns what you said (only if it's your voice), or null. */
    interface Asker { suspend fun ask(question: String): String? }

    /** keepListening=false after "stop". outcome is logged for learning: ok/confirmed/cancelled/failed/chat. */
    data class Result(val keepListening: Boolean, val outcome: String)

    private var outcome = "ok"

    suspend fun run(a: JSONObject, heard: String, asker: Asker): Result {
        val action = a.optString("action")
        outcome = "ok"
        ActivityLog.add(ctx, "Action: $a")
        when (action) {
            "set_alarm" -> setAlarm(a)
            "set_timer" -> setTimer(a)
            "set_reminder" -> setReminder(a)
            "open_app" -> openApp(a.optString("app"))
            "call" -> call(a.optString("contact"), asker)
            "message" -> message(a.optString("contact"), a.optString("text"), a.optString("app", "WhatsApp"), asker)
            "tasker" -> tasker(a.optString("task"))
            "media" -> if (!music.runAction(a)) outcome = "failed"
            "remember" -> {
                val f = a.optString("fact").ifBlank { heard }
                if (memory.remember(f)) speaker.say("Got it, I'll remember that.") else { outcome = "failed"; speaker.say("Sorry, what should I remember?") }
            }
            "forget" -> {
                val n = memory.forget(a.optString("fact").ifBlank { heard })
                speaker.say(if (n > 0) "Done, I've forgotten that." else "I didn't have anything like that saved.")
            }
            "stop" -> { speaker.say("Okay, going quiet."); return Result(false, "ok") }
            "chat" -> { outcome = "chat"; speaker.say(Brain.chat(a.optString("query").ifBlank { heard }, memory.facts())) }
            else -> { outcome = "failed"; speaker.say("Sorry, I didn't understand that.") }
        }
        return Result(true, outcome)
    }

    private fun launch(i: Intent): Boolean = runCatching {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrElse {
        ActivityLog.add(ctx, "Couldn't open screen: ${it.message}. Is 'Display over other apps' allowed?")
        false
    }

    private fun clock(h: Int, m: Int): String {
        val h12 = if (h % 12 == 0) 12 else h % 12
        val ampm = if (h < 12) "a.m." else "p.m."
        return if (m == 0) "$h12 $ampm" else "$h12:${"%02d".format(m)} $ampm"
    }

    private suspend fun setAlarm(a: JSONObject) {
        val h = a.optInt("hour", -1); val m = a.optInt("minute", 0)
        if (h !in 0..23) return speaker.say("What time should the alarm be?")
        val ok = launch(Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Topshe")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        speaker.say(if (ok) "Alarm set for ${clock(h, m)}." else "I couldn't set the alarm.")
    }

    private suspend fun setTimer(a: JSONObject) {
        val s = a.optInt("seconds", 0)
        if (s <= 0) return speaker.say("How long should the timer be?")
        val ok = launch(Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, s)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        val nice = if (s % 3600 == 0) "${s / 3600} hour" else if (s % 60 == 0) "${s / 60} minute" else "$s second"
        speaker.say(if (ok) "Timer set for $nice${if (nice.startsWith("1 ")) "" else "s"}." else "I couldn't set the timer.")
    }

    private suspend fun setReminder(a: JSONObject) {
        val text = a.optString("text").ifBlank { "Reminder" }
        val cal = Calendar.getInstance()
        if (a.has("in_minutes")) {
            cal.add(Calendar.MINUTE, a.optInt("in_minutes"))
        } else {
            cal.set(Calendar.HOUR_OF_DAY, a.optInt("hour", cal.get(Calendar.HOUR_OF_DAY)))
            cal.set(Calendar.MINUTE, a.optInt("minute", 0))
            cal.set(Calendar.SECOND, 0)
            if (a.optString("day") == "tomorrow") cal.add(Calendar.DAY_OF_YEAR, 1)
            if (cal.timeInMillis < System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = PendingIntent.getBroadcast(ctx, (cal.timeInMillis / 1000).toInt(),
            Intent(ctx, ReminderReceiver::class.java).putExtra("text", text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        val h = cal.get(Calendar.HOUR_OF_DAY); val m = cal.get(Calendar.MINUTE)
        speaker.say("Okay, I'll remind you to $text at ${clock(h, m)}.")
    }

    // ---------- apps ----------

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private suspend fun openApp(name: String) {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val q = norm(name)
        val hit = apps.firstOrNull { norm(it.first) == q }
            ?: apps.firstOrNull { norm(it.first).startsWith(q) }
            ?: apps.firstOrNull { norm(it.first).contains(q) || (q.length > 3 && q.contains(norm(it.first))) }
            ?: apps.minByOrNull { editDistance(norm(it.first), q) }?.takeIf { editDistance(norm(it.first), q) <= 2 }
        if (hit == null) return speaker.say("I couldn't find an app called $name.")
        val i = pm.getLaunchIntentForPackage(hit.second) ?: return speaker.say("I can't open ${hit.first}.")
        if (launch(i)) speaker.say("Opening ${hit.first}.") else speaker.say("I couldn't open ${hit.first}.")
    }

    // ---------- contacts, calls, messages ----------

    private data class Person(val name: String, val number: String)

    private fun findContacts(name: String): List<Person> {
        val q = name.lowercase().trim()
        if (q.isEmpty()) return emptyList()
        val found = LinkedHashMap<String, MutableList<Pair<String, Int>>>()
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE)
        runCatching {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    val words = n.lowercase().split(Regex("\\s+"))
                    val match = n.lowercase() == q || words.contains(q) || n.lowercase().startsWith(q) ||
                        (q.contains(" ") && n.lowercase().contains(q))
                    if (match) found.getOrPut(n) { mutableListOf() }.add(num to c.getInt(2))
                }
            }
        }
        // One entry per contact; prefer the mobile number
        return found.map { (n, nums) ->
            val mobile = nums.firstOrNull { it.second == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE } ?: nums.first()
            Person(n, mobile.first)
        }.sortedByDescending { it.name.lowercase() == q }
    }

    private val numberWords = mapOf("one" to 1, "first" to 1, "ek" to 1, "two" to 2, "second" to 2, "do" to 2,
        "three" to 3, "third" to 3, "teen" to 3, "four" to 4, "fourth" to 4, "char" to 4, "five" to 5, "fifth" to 5)

    /** Picks exactly one person, asking you if there's more than one match. */
    private suspend fun pickPerson(name: String, asker: Asker): Person? {
        val people = findContacts(name)
        if (people.isEmpty()) { speaker.say("I couldn't find $name in your contacts."); outcome = "failed"; return null }
        if (people.size == 1) return people[0]
        // You picked one of these before: offer that person first (you still confirm yes/no)
        memory.contactChoice(name)?.let { prev -> people.firstOrNull { it.name == prev }?.let { return it } }
        val list = people.take(5)
        val spoken = list.mapIndexed { i, p -> "${i + 1}, ${p.name}" }.joinToString(". ")
        val reply = asker.ask("I found ${people.size} people called $name. $spoken. Which one?")?.lowercase()
            ?: run { speaker.say("Cancelled."); outcome = "cancelled"; return null }
        val words = reply.split(Regex("\\s+"))
        val idx = words.firstNotNullOfOrNull { w -> w.toIntOrNull() ?: numberWords[w] }
        val chosen = idx?.let { list.getOrNull(it - 1) }
            ?: list.firstOrNull { p -> p.name.lowercase().split(" ").drop(1).any { it.isNotEmpty() && reply.contains(it) } }
        if (chosen == null) { speaker.say("I didn't get which one, so I've cancelled it."); outcome = "cancelled" }
        else memory.saveContactChoice(name, chosen.name)
        return chosen
    }

    private fun isYes(s: String?): Boolean {
        val w = s?.lowercase()?.split(Regex("\\s+")) ?: return false
        val yes = setOf("yes", "yeah", "yep", "yup", "haan", "han", "ha", "ok", "okay", "sure", "correct", "ji", "go", "do")
        val no = setOf("no", "nope", "nahi", "na", "cancel", "stop", "don't", "dont", "wait")
        return w.any { it in yes } && w.none { it in no }
    }

    private suspend fun call(name: String, asker: Asker) {
        val p = pickPerson(name, asker) ?: return
        if (!isYes(asker.ask("Call ${p.name}? Yes or no?"))) { outcome = "cancelled"; return speaker.say("Okay, not calling.") }
        val ok = launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(p.number))))
        outcome = if (ok) "confirmed" else "failed"
        if (!ok) speaker.say("I couldn't start the call.")
    }

    private suspend fun message(name: String, text: String, app: String, asker: Asker) {
        if (text.isBlank()) return speaker.say("What should the message say?")
        val p = pickPerson(name, asker) ?: return
        val viaSms = app.equals("SMS", ignoreCase = true)
        val how = if (viaSms) "by SMS" else "on WhatsApp"
        if (!isYes(asker.ask("Send ${p.name} $how: $text. Yes or no?"))) { outcome = "cancelled"; return speaker.say("Okay, not sent.") }
        outcome = "confirmed"
        if (viaSms) {
            runCatching {
                val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
                sms.sendTextMessage(p.number, null, text, null, null)
            }.onSuccess { speaker.say("Sent.") }.onFailure { speaker.say("The SMS failed.") }
        } else {
            var digits = p.number.filter { it.isDigit() }
            if (digits.length == 10) digits = "91$digits"
            if (digits.length == 11 && digits.startsWith("0")) digits = "91" + digits.drop(1)
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=" + Uri.encode(text))
            // WhatsApp opens with the message typed in; you tap send (WhatsApp doesn't allow auto-send)
            val ok = launch(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp"))
            speaker.say(if (ok) "It's ready in WhatsApp. Tap send." else "I couldn't open WhatsApp.")
        }
    }

    // ---------- Tasker ----------

    /** Runs the Tasker task with this exact name, e.g. "wifi off" (create it in Tasker first). */
    private suspend fun tasker(task: String) {
        if (task.isBlank()) return speaker.say("Which task?")
        val i = Intent("net.dinglisch.android.tasker.ACTION_TASK")
            .putExtra("version_number", "1.1")
            .putExtra("task_name", task)
            .putExtra("task_priority", 5)
        runCatching { ctx.sendBroadcast(i) }
        speaker.say("Done: $task.")
    }

    private fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }
}
