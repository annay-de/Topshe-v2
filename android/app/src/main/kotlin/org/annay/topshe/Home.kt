package org.annay.topshe

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home: the conversation with Topshe (voice and typed), a box to type to it, shortcuts,
 * and an on/off pill. Typed requests run exactly like spoken ones; questions it asks back
 * (which contact? call? send?) come with tap buttons.
 */
class HomeScreen(private val act: Activity, private val k: Kit,
                 private val onSettings: () -> Unit, private val onToggle: () -> Unit) {
    private val prefs = Prefs(act)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val chatList = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, k.dp(6), 0, k.dp(6)) }
    private val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(chatList) }
    private val input = EditText(act)
    // Big listening status: a glowing dot + what it's doing + what just happened
    private val orb = View(act).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Look.OFF) }
    }
    private val statusTitle = k.text("Not listening", 16f, Look.TEXT, Look.semibold)
    private val statusDetail = k.text("Tap to start", 12.5f, Look.MUTED)
    private val pulse = android.animation.ObjectAnimator.ofFloat(orb, "alpha", 1f, 0.25f).apply {
        repeatMode = android.animation.ValueAnimator.REVERSE
        repeatCount = android.animation.ValueAnimator.INFINITE
    }
    private var shownState: ListenerService.State? = null
    private val brainBanner = k.card(null)
    private val brainBannerText = k.text("", 14f, Look.TEXT)
    private lateinit var micButton: View
    private val banner = k.card(null)
    private val bannerText = k.text("", 14f, Look.TEXT)
    private val options = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private val thinking = k.text("Topshe is thinking...", 13f, Look.MUTED).apply { visibility = View.GONE; setPadding(k.dp(4), k.dp(2), 0, k.dp(4)) }
    private var stamp = -1L
    private var live: TextView? = null          // the answer while it's being written
    private var busy = false
    private var busySince = 0L
    private var answered = false               // Topshe already replied (it may still be finishing up)
    private var warmed = false
    private var pending: CompletableDeferred<String?>? = null
    private var speaker: Speaker? = null
    private var engine: ChatEngine? = null

    val view: View = build()

    private fun build(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(k.dp(16), k.dp(20), k.dp(16), 0) }

        // Header: wordmark + "Hey Annay" on the left, listening pill on the right
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(k.wordmark("TOPSHE").apply { textSize = 38f })
        titles.addView(k.text("Hey Annay", 15f, Look.MUTED, Look.semibold))
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(head)

        // Listening status (tap: on / off)
        val status = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(12))
            background = k.rounded(0xD9141418.toInt(), 20, 1, Look.EDGE)
            addView(orb, LinearLayout.LayoutParams(k.dp(18), k.dp(18)).apply { rightMargin = k.dp(14) })
            val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; addView(statusTitle); addView(statusDetail) }
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { onToggle() }
        }
        col.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(12) })

        // Still on the old, slow brain: offer the fast ones right now (any network)
        brainBanner.addView(brainBannerText)
        brainBanner.setOnClickListener {
            DownloadWorker.brain(act, anyNetwork = true)
            brainBannerText.text = "Downloading the fast brains... (progress in the notification)"
        }
        brainBanner.visibility = View.GONE
        col.addView(brainBanner)

        // "Finish setup" banner (hidden once everything is ready)
        banner.addView(bannerText)
        banner.setOnClickListener { onSettings() }
        col.addView(banner)

        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = k.dp(10) })
        col.addView(thinking)
        thinking.setOnClickListener {                     // tap to stop a slow answer
            if (busy) { Brain.stopNow(); thinking.text = "Stopping..." }
        }
        col.addView(options)

        // Shortcuts
        val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, k.dp(6), 0, k.dp(8)) }
        chips.addView(k.chip(if (prefs.speakTyped) "🔊 Speaking" else "🔇 Silent") { })
        (chips.getChildAt(0) as TextView).setOnClickListener { v ->
            prefs.speakTyped = !prefs.speakTyped
            (v as TextView).text = if (prefs.speakTyped) "🔊 Speaking" else "🔇 Silent"
            speaker?.muted = !prefs.speakTyped
        }
        listOf("▶ Play music" to "play music", "⏸ Pause" to "pause the music", "⏭ Next" to "next song",
            "⏱ Timer 10 min" to "set a timer for 10 minutes", "What can you do?" to "what can you do?")
            .forEach { (label, say) -> chips.addView(k.chip(label) { send(say) }) }
        col.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        // Type box + send + mic
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, k.dp(10)) }
        input.apply {
            hint = "Message Topshe..."
            textSize = 15f; setTextColor(Look.TEXT); setHintTextColor(0x80FFFFFF.toInt())
            background = k.rounded(0xD9141418.toInt(), 22, 1, Look.EDGE)
            setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(text.toString()); true } else false }
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        micButton = k.round("🎙", false) { talkNow() }      // tap: speak now, no wake word needed
        row.addView(micButton)
        row.addView(k.round("↑", true) { send(input.text.toString()) })
        col.addView(row)
        return col
    }

    /** 🎙: listen to one request right now (starts Topshe if it's off). */
    private fun talkNow() {
        if (Store.missing(act).isNotEmpty() ||
            act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onToggle(); return                          // shows what's missing / asks for the mic
        }
        ListenerService.send(act, ListenerService.ACTION_TALK)
        statusTitle.text = "Starting the mic..."
    }

    /** Listening status + setup banners (called every second). */
    fun refresh(missing: List<String>, s: ListenerService.State) {
        val (color, title, hint) = when {
            missing.isNotEmpty() -> Triple(Look.OFF, "Not ready yet", "Finish setup in Settings")
            s == ListenerService.State.OFF -> Triple(Look.OFF, "Not listening", "Tap here to start, or tap 🎙 to talk once")
            s == ListenerService.State.STARTING -> Triple(Look.PAUSED, "Starting...", "Getting the mic and brain ready")
            s == ListenerService.State.PAUSED -> Triple(Look.PAUSED, "Paused - mic is off", "Tap here to resume")
            s == ListenerService.State.HEARING -> Triple(0xFF60A5FA.toInt(), "I'm listening - speak now", "Stop talking and I'll answer")
            s == ListenerService.State.BUSY -> Triple(0xFFC084FC.toInt(), "Working on it...", "")
            else -> Triple(Look.ON, "Listening for \"Topshe\"", "Say \"Topshe\", then your request. Or tap 🎙")
        }
        (orb.background as GradientDrawable).setColor(color)
        statusTitle.text = title
        val ev = ListenerService.lastEvent
        val fresh = ev.isNotBlank() && System.currentTimeMillis() - ListenerService.lastEventAt < 60_000
        statusDetail.text = if (fresh && s != ListenerService.State.HEARING) ev else hint
        statusDetail.visibility = if (statusDetail.text.isBlank()) View.GONE else View.VISIBLE
        if (s != shownState) {
            shownState = s
            pulse.cancel(); orb.alpha = 1f
            if (s == ListenerService.State.LISTENING || s == ListenerService.State.HEARING || s == ListenerService.State.BUSY) {
                pulse.duration = if (s == ListenerService.State.LISTENING) 1100 else 380
                pulse.start()
            }
            micButton.alpha = if (s == ListenerService.State.HEARING) 0.5f else 1f
        }
        // Old 4B brain (too slow on this phone) still installed?
        val p = prefs
        val old = p.brainVersion in 1..2 && Store.brain(act).exists()
        brainBanner.visibility = if (old) View.VISIBLE else View.GONE
        if (old) brainBannerText.text = p.brainDl.takeIf { it.contains("MB") || it.contains("queued") }
            ?: "You're still on the old, slow brain. Tap to download the fast ones now (about 1.8 GB)."
        banner.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        bannerText.text = "Finish setup: ${missing.joinToString()}. Tap to open Settings."
        if (missing.isEmpty() && ChatLog.stamp(act) == 0L && chatList.childCount == 0)
            chatList.addView(k.bubble(false, "Hey Annay! Say \"Topshe\" or type here. Try \"play Kesariya\", \"wake me up at 7\", or ask me anything.", false))
    }

    /** Called every second: shows new messages (also ones from voice). */
    fun tick() {
        val s = ChatLog.stamp(act)
        if (s != stamp) { stamp = s; render() }
        if (busy && pending == null && live == null && !answered && thinking.text != "Stopping...") {
            val secs = (System.currentTimeMillis() - busySince) / 1000
            if (secs >= 2) thinking.text = "Topshe is thinking... ${secs}s  ·  tap to stop"
        }
    }

    /** Opening the chat gets the brain ready in the background (quick commands never wait for it). */
    fun warmUp() {
        if (warmed || busy || !Store.brain(act).exists()) return
        warmed = true
        val eng = engine()
        scope.launch(Dispatchers.Default) { runCatching { eng.warmUp() } }
    }

    private fun render() {
        chatList.removeAllViews()
        live = null
        ChatLog.recent(act).forEach { m -> chatList.addView(k.bubble(m.fromYou, m.text, m.via == "voice")) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun engine(): ChatEngine {
        engine?.let { return it }
        val sp = Speaker(act).apply {
            muted = !prefs.speakTyped
            echo = { text ->
                ChatLog.add(act, false, text, "typed")
                act.runOnUiThread { answered = true; thinking.visibility = View.GONE; tick() }
            }
            partial = { text -> act.runOnUiThread { showLive(text) } }
        }
        speaker = sp
        return ChatEngine(act, sp).also { engine = it }
    }

    private fun send(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        input.setText("")
        pending?.let { if (it.isActive) { answer(t, t); return } }
        if (busy) { thinking.text = "One moment, still on the last one..."; return }
        busy = true
        busySince = System.currentTimeMillis()
        answered = false
        ChatLog.add(act, true, t, "typed"); tick()
        thinking.text = "Topshe is thinking..."; thinking.visibility = View.VISIBLE
        val eng = engine()                       // created on the main thread (it starts text-to-speech)
        scope.launch {
            val keep = withContext(Dispatchers.Default) {
                runCatching { eng.handle(t, asker) }.getOrElse { e -> speaker?.say("Something went wrong: ${e.message}"); true }
            }
            busy = false
            // How long it took, and which brain (so slow answers are easy to spot)
            val secs = (System.currentTimeMillis() - busySince) / 1000.0
            thinking.text = "Took %.1fs · brain %s".format(secs, Brain.loadedName.ifBlank { "-" })
            thinking.visibility = View.VISIBLE
            thinking.postDelayed({ if (!busy) thinking.visibility = View.GONE }, 15_000)
            if (!keep && ListenerService.state != ListenerService.State.OFF) ListenerService.send(act, ListenerService.ACTION_PAUSE)
            tick()
        }
    }

    /** Shows the answer word by word while the brain writes it. */
    private fun showLive(text: String) {
        if (text.isEmpty()) { live?.let { (it.parent as? View)?.let { p -> chatList.removeView(p) } }; live = null; return }
        val t = live ?: run {
            val b = k.bubble(false, "", false)
            chatList.addView(b)
            ((b as LinearLayout).getChildAt(0) as TextView).also { live = it }
        }
        t.text = text
        thinking.visibility = View.GONE
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Your reply to a question Topshe asked (typed or a tapped button). */
    private fun answer(shown: String, value: String) {
        ChatLog.add(act, true, shown, "typed"); tick()
        options.removeAllViews()
        pending?.complete(value)
    }

    /** Questions back (which contact / call? / send?) as chat bubbles with buttons. */
    private val asker = object : Actions.Asker {
        override suspend fun ask(question: String): String? {
            val d = CompletableDeferred<String?>()
            withContext(Dispatchers.Main) { pending = d; thinking.visibility = View.GONE }
            speaker?.say(question)   // shows it (and speaks it if sound is on)
            withContext(Dispatchers.Main) { showOptions(question) }
            val r = withTimeoutOrNull(120_000) { d.await() }
            withContext(Dispatchers.Main) {
                pending = null; options.removeAllViews(); answered = false; busySince = System.currentTimeMillis()
                thinking.text = "Topshe is thinking..."; thinking.visibility = View.VISIBLE
            }
            return r
        }
    }

    private fun showOptions(question: String) {
        options.removeAllViews()
        val people = Regex("(\\d+), ([^.]+)\\.").findAll(question).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
        val choices = when {
            question.contains("Yes or no", ignoreCase = true) -> listOf("Yes" to "yes", "No" to "no")
            people.size >= 2 -> people.map { (n, name) -> "$n. $name" to n } + ("Cancel" to "cancel")
            else -> emptyList()
        }
        choices.forEach { (label, value) -> options.addView(k.chip(label) { answer(label, value) }) }
    }

    fun destroy() {
        scope.cancel()
        speaker?.shutdown()
    }
}

/** Memory: what Topshe knows about you. Add or delete facts; it uses them in every answer. */
class MemoryScreen(private val act: Activity, private val k: Kit) {
    private val memory = Memory(act)
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val info = k.text("", 13f, Look.MUTED)
    val view: View

    init {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(k.dp(18), k.dp(24), k.dp(18), k.dp(24)) }
        col.addView(k.text("Memory", 30f, Look.TEXT, Look.black))
        col.addView(k.text("What Topshe knows about you. It uses these in every answer.", 14f))
        val add = k.card("Add something")
        val box = k.input("e.g. my gym is at 7 pm", "")
        add.addView(box)
        add.addView(k.button("Remember this") {
            if (memory.remember(box.text.toString())) { box.setText(""); render() }
        })
        col.addView(add)
        val c = k.card("Saved")
        c.addView(list)
        col.addView(c)
        val tips = k.card("By voice")
        tips.addView(k.text("\"Topshe, remember that ...\" adds, \"Topshe, forget ...\" removes. If it gets something wrong, say \"no, I meant ...\" and it won't repeat the mistake.", 13f))
        tips.addView(info.apply { setPadding(0, k.dp(10), 0, 0) })
        col.addView(tips)
        view = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(col) }
    }

    fun render() {
        list.removeAllViews()
        val facts = memory.facts()
        if (facts.isEmpty()) list.addView(k.text("Nothing yet.", 14f))
        facts.forEach { f ->
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, k.dp(6), 0, k.dp(6)) }
            row.addView(k.text(f, 15f, Look.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(act).apply {
                text = "✕"; textSize = 16f; setTextColor(Look.MUTED); setPadding(k.dp(14), k.dp(6), k.dp(6), k.dp(6))
                setOnClickListener { memory.removeFact(f); render() }
            })
            list.addView(row)
        }
        val p = Prefs(act)
        info.text = "Brain: " + (if (p.brainVersion > 0) "v${p.brainVersion}" else if (Store.model(act).exists()) "older v2" else "none yet") +
            (if (p.chatVersion > 0 && Store.chatBrain(act).exists()) " + chat v${p.chatVersion}" else "") +
            "  ·  learned from ${memory.interactionCount()} requests so far"
    }
}
