package org.annay.topshe

import android.app.KeyguardManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Music and media, handled directly by simple rules (fast and reliable, no model needed):
 *   "play music" / "resume"            -> resume Spotify (works with the phone locked)
 *   "play <song> [on spotify]"         -> Spotify searches and plays it
 *   "play <video> on youtube"          -> finds the top YouTube result, opens it in Brave
 *                                         (Brave keeps playing with the screen off)
 *   "pause" / "next" / "previous"      -> media buttons for whatever is playing
 *   "volume up" / "volume down" / "mute"
 */
class Music(private val ctx: Context, private val speaker: Speaker) {

    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val SPOTIFY = "com.spotify.music"
    private val BRAVE = "com.brave.browser"
    private val YOUTUBE = "com.google.android.youtube"

    /** Returns true if this was a media command (and it has been handled). */
    suspend fun handle(heard: String): Boolean {
        val t = heard.lowercase().trim().removeSuffix(".").replace(Regex("\\s+"), " ")
        val generic = Regex("^(play|resume|start|continue)( the| some| my)?( music| songs?| spotify| playlist)?( please)?$")
        when {
            t.matches(generic) || t in setOf("music", "play music on spotify", "resume music") -> resume()
            t.matches(Regex("^(pause|stop)( the)?( music| song| it| playing| spotify| video)?( please)?$")) && t != "stop" -> key(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused.")
            t.matches(Regex("^(next|skip)( song| track| one| this)?( please)?$")) || t == "play next song" -> key(KeyEvent.KEYCODE_MEDIA_NEXT, null)
            t.matches(Regex("^(previous|last|go back)( song| track| one)?( please)?$")) || t == "play the previous song" -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS, null)
            t.matches(Regex("^(volume up|louder|increase (the )?volume|turn (it )?up)( please)?$")) -> volume(AudioManager.ADJUST_RAISE)
            t.matches(Regex("^(volume down|quieter|softer|decrease (the )?volume|lower (the )?volume|turn (it )?down)( please)?$")) -> volume(AudioManager.ADJUST_LOWER)
            t.matches(Regex("^(mute|mute (the )?(volume|music))$")) -> volume(AudioManager.ADJUST_MUTE)
            t.startsWith("play ") -> {
                var q = t.removePrefix("play ").trim()
                val yt = Regex("\\s+(on|in|from) (youtube|you tube|brave)$")
                val sp = Regex("\\s+(on|in|from) spotify$")
                when {
                    yt.containsMatchIn(q) -> youtube(yt.replace(q, "").trim())
                    else -> { q = sp.replace(q, "").trim(); spotify(q) }
                }
            }
            else -> return false
        }
        return true
    }

    /** Runs a "media" action from the brain (when the quick rules didn't catch the words). */
    suspend fun runAction(a: org.json.JSONObject): Boolean {
        when (a.optString("op")) {
            "play" -> {
                val q = a.optString("query")
                if (q.isBlank()) resume()
                else if (a.optString("source") == "youtube") youtube(q) else spotify(q)
            }
            "pause" -> key(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused.")
            "next" -> key(KeyEvent.KEYCODE_MEDIA_NEXT, null)
            "previous" -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS, null)
            "volume_up" -> volume(AudioManager.ADJUST_RAISE)
            "volume_down" -> volume(AudioManager.ADJUST_LOWER)
            "mute" -> volume(AudioManager.ADJUST_MUTE)
            else -> return false
        }
        return true
    }

    private fun launch(i: Intent): Boolean = runCatching {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrElse { ActivityLog.add(ctx, "Media launch failed: ${it.message}"); false }

    private fun installed(pkg: String) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun locked() = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Presses a media button (same as a headphone button). Works with the screen locked. */
    private fun pressKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private suspend fun key(code: Int, say: String?) {
        pressKey(code)
        say?.let { speaker.say(it) }
    }

    private suspend fun waitForMusic(ms: Long): Boolean {
        var waited = 0L
        while (waited < ms) {
            if (audio.isMusicActive) return true
            delay(250); waited += 250
        }
        return false
    }

    private suspend fun resume() {
        speaker.say("Playing.")
        val c = MediaAccess.controller(ctx, SPOTIFY)
        if (c != null) runCatching { c.transportControls.play() } else pressKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        if (waitForMusic(2500)) return
        // Nothing resumed (e.g. Spotify was closed): ask Spotify to play something
        if (!installed(SPOTIFY)) return speaker.say("Spotify isn't installed.")
        launch(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(SPOTIFY)
            .putExtra(SearchManager.QUERY, ""))
        if (!waitForMusic(6000)) speaker.say(if (locked()) "Spotify didn't start. It may need the phone unlocked." else "Spotify didn't start.")
    }

    /** Spotify supports Android's "play from search" (the same thing Google Assistant uses). */
    private suspend fun spotify(query: String) {
        if (query.isBlank()) return resume()
        if (!installed(SPOTIFY)) return speaker.say("Spotify isn't installed.")
        speaker.say("Playing $query on Spotify.")
        // Best way: Spotify's own player plays the first result (needs notification access)
        if (MediaAccess.granted(ctx)) {
            var c = MediaAccess.controller(ctx, SPOTIFY)
            if (c == null) {                               // Spotify closed: open it, wait for its player
                ctx.packageManager.getLaunchIntentForPackage(SPOTIFY)?.let { launch(it) }
                var waited = 0L
                while (c == null && waited < 8000) { delay(400); waited += 400; c = MediaAccess.controller(ctx, SPOTIFY) }
            }
            if (c != null && MediaAccess.playFromSearch(c, query)) {
                if (waitForMusic(8000)) return
                ActivityLog.add(ctx, "Spotify player didn't start '$query'; trying the search intent")
            }
        } else ActivityLog.add(ctx, "No notification access: Spotify may only show search results")
        val ok = launch(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(SPOTIFY)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*"))
        if (!ok) {
            // Fallback: open Spotify's search for it
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))).setPackage(SPOTIFY))
            return speaker.say("I opened Spotify's search for it.")
        }
        if (!waitForMusic(8000)) {
            ActivityLog.add(ctx, "Spotify didn't start playing '$query' (locked=${locked()})")
            speaker.say(if (locked()) "Spotify didn't start. Unlock the phone and it should play." else "Spotify didn't start playing.")
        }
    }

    /** Finds the top YouTube result (no API key needed) and opens it in Brave. */
    private suspend fun youtube(query: String) {
        if (query.isBlank()) return speaker.say("What should I play on YouTube?")
        speaker.say("Finding $query on YouTube.")
        val id = withContext(Dispatchers.IO) { runCatching { firstVideoId(query) }.getOrNull() }
        val url = if (id != null) "https://m.youtube.com/watch?v=$id"
                  else "https://m.youtube.com/results?search_query=" + Uri.encode(query)
        val app = when {
            installed(BRAVE) -> BRAVE
            installed(YOUTUBE) -> YOUTUBE
            else -> null
        }
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).also { if (app != null) it.setPackage(app) }
        if (!launch(i)) return speaker.say("I couldn't open YouTube.")
        if (locked()) speaker.say("It's ready. Unlock your phone to start it, then you can turn the screen off.")
        else if (app == BRAVE) speaker.say("Playing in Brave. You can turn the screen off.")
    }

    private fun firstVideoId(query: String): String? {
        val c = URL("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))
            .openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
        c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
        c.connectTimeout = 8000; c.readTimeout = 8000
        val html = c.inputStream.bufferedReader().use { it.readText() }
        return Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
    }

    private suspend fun volume(dir: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }
}
