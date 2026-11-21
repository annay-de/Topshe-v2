package org.annay.topshe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Base64
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Keeps Topshe learning, with no effort from you:
 *  1. Uploads what you said + what Topshe did (and your saved facts) to the private `data` branch.
 *  2. Every Sunday the repo retrains Topshe on Kaggle (see training/pipeline.py).
 *  3. Downloads the new brain on Wi-Fi once it has passed its tests, and switches to it.
 *     The previous brain is kept, so you can go back with one tap.
 */
object Sync {
    private const val API = "https://api.github.com"

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("topshe-sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf("manual" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("topshe-sync-now", ExistingWorkPolicy.REPLACE, req)
    }

    // ---------------- GitHub API ----------------

    private fun conn(prefs: Prefs, path: String, method: String = "GET"): HttpURLConnection {
        val c = URL(if (path.startsWith("http")) path else "$API$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer ${prefs.githubToken}")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.connectTimeout = 20_000; c.readTimeout = 60_000
        return c
    }

    private fun HttpURLConnection.body(): String =
        (if (responseCode < 400) inputStream else errorStream)?.bufferedReader()?.use { it.readText() } ?: ""

    private fun getJson(prefs: Prefs, path: String): Any? {
        val c = conn(prefs, path)
        val code = c.responseCode
        if (code == 404) return null
        val b = c.body()
        check(code < 300) { "GitHub $code: ${b.take(200)}" }
        return if (b.trimStart().startsWith("[")) JSONArray(b) else JSONObject(b)
    }

    private fun putFile(prefs: Prefs, path: String, bytes: ByteArray, message: String) {
        val existing = getJson(prefs, "/repos/${prefs.githubRepo}/contents/$path?ref=data") as? JSONObject
        val body = JSONObject()
            .put("message", message)
            .put("branch", "data")
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        existing?.optString("sha")?.takeIf { it.isNotEmpty() }?.let { body.put("sha", it) }
        val c = conn(prefs, "/repos/${prefs.githubRepo}/contents/$path", "PUT")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        check(code in 200..299) { "Upload failed ($code): ${c.body().take(200)}" }
    }

    /** Release assets of a private repo: ask GitHub, then follow its redirect WITHOUT our token. */
    private fun openAsset(prefs: Prefs, assetApiUrl: String): InputStream {
        val c = conn(prefs, assetApiUrl)
        c.setRequestProperty("Accept", "application/octet-stream")
        c.instanceFollowRedirects = false
        return when (c.responseCode) {
            in 300..399 -> (URL(c.getHeaderField("Location")).openConnection() as HttpURLConnection)
                .apply { connectTimeout = 20_000; readTimeout = 120_000 }.inputStream
            in 200..299 -> c.inputStream
            else -> throw IllegalStateException("Download failed (${c.responseCode})")
        }
    }

    // ---------------- 1. upload ----------------

    fun upload(ctx: Context) {
        val prefs = Prefs(ctx)
        val mem = Memory(ctx)
        val lines = if (mem.interactionsFile.exists()) mem.interactionsFile.readLines().filter { it.isNotBlank() } else emptyList()
        val done = prefs.uploadedLines.coerceAtMost(lines.size)
        if (lines.size > done) {
            val chunk = lines.subList(done, lines.size).joinToString("\n") + "\n"
            putFile(prefs, "data/interactions/phone-${System.currentTimeMillis()}.jsonl", chunk.toByteArray(),
                "phone upload: ${lines.size - done} interactions")
            prefs.uploadedLines = lines.size
            ActivityLog.add(ctx, "Uploaded ${lines.size - done} new interactions for learning")
        }
        putFile(prefs, "data/facts.json", JSONObject().put("facts", JSONArray(mem.facts())).toString(1).toByteArray(),
            "phone upload: facts")
    }

    // ---------------- 2. new brain ----------------

    /** Returns the newest published brain release (tag model-N), or null. */
    private fun latestBrain(prefs: Prefs): Pair<Int, JSONObject>? {
        val rels = getJson(prefs, "/repos/${prefs.githubRepo}/releases?per_page=50") as? JSONArray ?: return null
        var best: Pair<Int, JSONObject>? = null
        for (i in 0 until rels.length()) {
            val r = rels.getJSONObject(i)
            val v = r.optString("tag_name").removePrefix("model-").toIntOrNull() ?: continue
            if (!r.optString("tag_name").startsWith("model-")) continue
            if (best == null || v > best.first) best = v to r
        }
        return best
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 20).use { s ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = s.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Downloads + verifies + installs a newer brain. Returns a short message. */
    fun updateBrain(ctx: Context, progress: (String) -> Unit = {}): String {
        val prefs = Prefs(ctx)
        val (version, rel) = latestBrain(prefs) ?: return "No trained brain published yet."
        if (version <= prefs.brainVersion && Store.brain(ctx).exists()) return "Brain is up to date (v${prefs.brainVersion})."
        val assets = rel.getJSONArray("assets")
        fun asset(name: String): JSONObject? = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == name }
        val manifest = JSONObject(openAsset(prefs, asset("manifest.json")!!.getString("url")).bufferedReader().use { it.readText() })

        // Make room: the brain before the current one isn't needed any more
        Store.previousBrain(ctx).delete()
        val tmp = File(ctx.filesDir, "topshe_brain.download")
        tmp.delete()
        val parts = manifest.getJSONArray("parts")
        tmp.outputStream().buffered(1 shl 20).use { out ->
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                progress("Downloading brain v$version: part ${i + 1} of ${parts.length()}...")
                openAsset(prefs, asset(p.getString("name"))!!.getString("url")).use { it.copyTo(out, 1 shl 20) }
            }
        }
        progress("Checking the download...")
        if (sha256(tmp) != manifest.getString("sha256")) {
            tmp.delete()
            return "Download was corrupted; will try again later."
        }
        // Install: current -> previous, new -> current
        if (Store.brain(ctx).exists()) {
            Store.brain(ctx).renameTo(Store.previousBrain(ctx))
            Store.promptTemplate(ctx).takeIf { it.exists() }?.renameTo(Store.previousPromptTemplate(ctx))
            prefs.previousBrainVersion = prefs.brainVersion
        }
        tmp.renameTo(Store.brain(ctx))
        Store.promptTemplate(ctx).writeText(manifest.getJSONObject("prompt").toString())
        prefs.brainVersion = version
        reloadBrain(ctx)
        val acc = manifest.optJSONObject("eval")?.optJSONObject("synthetic")?.optDouble("exact")
        val msg = "New brain v$version installed" + (acc?.let { " (test accuracy %.0f%%)".format(it * 100) } ?: "")
        ActivityLog.add(ctx, msg)
        notify(ctx, msg)
        return msg
    }

    /** One tap back to the previous brain (and forward again). */
    fun swapToPrevious(ctx: Context): String {
        val prefs = Prefs(ctx)
        val cur = Store.brain(ctx); val prev = Store.previousBrain(ctx)
        if (!prev.exists()) return "There's no previous brain on the phone."
        val tmp = File(ctx.filesDir, "swap.gguf")
        cur.renameTo(tmp); prev.renameTo(cur); tmp.renameTo(prev)
        val t = Store.promptTemplate(ctx); val pt = Store.previousPromptTemplate(ctx); val tt = File(ctx.filesDir, "swap.json")
        t.renameTo(tt); pt.renameTo(t); tt.renameTo(pt)
        val v = prefs.brainVersion; prefs.brainVersion = prefs.previousBrainVersion; prefs.previousBrainVersion = v
        reloadBrain(ctx)
        return "Switched to brain v${prefs.brainVersion}."
    }

    private fun reloadBrain(ctx: Context) {
        runCatching { ctx.startService(Intent(ctx, ListenerService::class.java).setAction(ListenerService.ACTION_RELOAD)) }
            .onFailure { Brain.unload() }
    }

    private fun notify(ctx: Context, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("topshe_updates", "Topshe updates", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(42, android.app.Notification.Builder(ctx, "topshe_updates")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Topshe learned").setContentText(text).setAutoCancel(true).build())
    }

    /** Full sync. Brain downloads only happen on unmetered (Wi-Fi) networks. */
    fun runSync(ctx: Context, progress: (String) -> Unit = {}): String {
        val prefs = Prefs(ctx)
        if (prefs.githubToken.isBlank()) return "Add your GitHub token first."
        val out = StringBuilder()
        runCatching { upload(ctx) }.onFailure { out.append("Upload failed: ${it.message}\n") }.onSuccess { out.append("Uploaded your data.\n") }
        val metered = ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        if (metered) out.append("On mobile data: brain download waits for Wi-Fi.")
        else runCatching { updateBrain(ctx, progress) }.onSuccess { out.append(it) }.onFailure { out.append("Brain check failed: ${it.message}") }
        return out.toString().trim()
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val msg = runCatching { Sync.runSync(applicationContext) }.getOrElse { "Sync error: ${it.message}" }
        ActivityLog.add(applicationContext, "Sync: ${msg.replace("\n", " | ")}")
        return Result.success()
    }
}
