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
    private val pillDot = k.dot(Look.OFF)
    private val pillText = k.text("Off", 13f, Look.TEXT, Look.semibold)
    private val banner = k.card(null)
    private val bannerText = k.text("", 14f, Look.TEXT)
    private val options = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private val thinking = k.text("Topshe is thinking...", 13f, Look.MUTED).apply { visibility = View.GONE; setPadding(k.dp(4), k.dp(2), 0, k.dp(4)) }
    private var stamp = -1L
    private var busy = false
    private var busySince = 0L
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
        val pill = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(k.dp(12), k.dp(8), k.dp(14), k.dp(8))
            background = k.rounded(0xD9141418.toInt(), 20, 1, Look.EDGE)
            addView(pillDot); addView(pillText)
            setOnClickListener { onToggle() }
        }
        head.addView(pill)
        col.addView(head)

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
        row.addView(k.round("🎙", false) { onToggle() })
        row.addView(k.round("↑", true) { send(input.text.toString()) })
        col.addView(row)
        return col
    }

    /** Listening pill + setup banner. */
    fun refresh(missing: List<String>, s: ListenerService.State) {
        val (color, label) = when {
            missing.isNotEmpty() -> Look.OFF to "Setup"
            s == ListenerService.State.OFF -> Look.OFF to "Off"
            s == ListenerService.State.PAUSED -> Look.PAUSED to "Paused"
            else -> Look.ON to "Listening"
        }
        (pillDot.background as GradientDrawable).setColor(color)
        pillText.text = label
        banner.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        bannerText.text = "Finish setup: ${missing.joinToString()}. Tap to open Settings."
        if (missing.isEmpty() && ChatLog.stamp(act) == 0L && chatList.childCount == 0)
            chatList.addView(k.bubble(false, "Hey Annay! Say \"Topshe\" or type here. Try \"play Kesariya\", \"wake me up at 7\", or ask me anything.", false))
    }

    /** Called every second: shows new messages (also ones from voice). */
    fun tick() {
        val s = ChatLog.stamp(act)
        if (s != stamp) { stamp = s; render() }
        if (busy && pending == null && thinking.text != "Stopping...") {
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
        ChatLog.recent(act).forEach { m -> chatList.addView(k.bubble(m.fromYou, m.text, m.via == "voice")) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun engine(): ChatEngine {
        engine?.let { return it }
        val sp = Speaker(act).apply {
            muted = !prefs.speakTyped
            echo = { text -> ChatLog.add(act, false, text, "typed"); act.runOnUiThread { tick() } }
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
        ChatLog.add(act, true, t, "typed"); tick()
        thinking.text = "Topshe is thinking..."; thinking.visibility = View.VISIBLE
        val eng = engine()                       // created on the main thread (it starts text-to-speech)
        scope.launch {
            val keep = withContext(Dispatchers.Default) {
                runCatching { eng.handle(t, asker) }.getOrElse { e -> speaker?.say("Something went wrong: ${e.message}"); true }
            }
            busy = false
            thinking.visibility = View.GONE
            if (!keep && ListenerService.state != ListenerService.State.OFF) ListenerService.send(act, ListenerService.ACTION_PAUSE)
            tick()
        }
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
                pending = null; options.removeAllViews()
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
