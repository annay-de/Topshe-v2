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
 *     No backup copy is kept on the phone (saves 2.7 GB): every brain stays in the repo's
 *     Releases, so "go back one brain" simply re-downloads the previous one.
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

    /** All published brains (tag model-N) as version -> release. */
    private fun brainReleases(prefs: Prefs): Map<Int, JSONObject> {
        val rels = getJson(prefs, "/repos/${prefs.githubRepo}/releases?per_page=100") as? JSONArray ?: return emptyMap()
        val out = sortedMapOf<Int, JSONObject>()
        for (i in 0 until rels.length()) {
            val r = rels.getJSONObject(i)
            val tag = r.optString("tag_name")
            if (!tag.startsWith("model-")) continue
            tag.removePrefix("model-").toIntOrNull()?.let { out[it] = r }
        }
        return out
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 20).use { s ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = s.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Downloads + verifies + installs a brain: the newest one, or [target] (used for going back). */
    fun updateBrain(ctx: Context, target: Int? = null, progress: (String) -> Unit = {}): String {
        val prefs = Prefs(ctx)
        val all = brainReleases(prefs)
        if (all.isEmpty()) return "No trained brain published yet."
        val version = target ?: all.keys.filter { it > prefs.skipVersion }.maxOrNull()
            ?: return "Staying on brain v${prefs.brainVersion} (you went back; waiting for next week's brain)."
        val rel = all[version] ?: return "Brain v$version isn't available."
        if (version == prefs.brainVersion && Store.brain(ctx).exists()) return "Brain is up to date (v$version)."
        val assets = rel.getJSONArray("assets")
        fun asset(name: String): JSONObject? = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == name }
        val manifest = JSONObject(openAsset(prefs, asset("manifest.json")!!.getString("url")).bufferedReader().use { it.readText() })

        // Old builds kept a backup copy; it's no longer needed
        File(ctx.filesDir, "topshe_brain_prev.gguf").delete()
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
        // Swap in only after the new one is verified. The running brain keeps its open copy until the
        // listener reloads (deleting an open file is safe on Android), so nothing breaks mid-sentence.
        Store.brain(ctx).delete()
        tmp.renameTo(Store.brain(ctx))
        Store.promptTemplate(ctx).writeText(manifest.getJSONObject("prompt").toString())
        prefs.brainVersion = version
        reloadBrain(ctx)
        val acc = manifest.optJSONObject("eval")?.optJSONObject("synthetic")?.optDouble("exact")
        val msg = "Brain v$version installed" + (acc?.let { " (test accuracy %.0f%%)".format(it * 100) } ?: "")
        ActivityLog.add(ctx, msg)
        notify(ctx, msg)
        return msg
    }

    /** Goes back one brain by re-downloading the previous version from GitHub (Wi-Fi recommended). */
    fun rollback(ctx: Context, progress: (String) -> Unit = {}): String {
        val prefs = Prefs(ctx)
        val older = brainReleases(prefs).keys.filter { it < prefs.brainVersion }.maxOrNull()
            ?: return "There's no older brain to go back to."
        val from = prefs.brainVersion
        val msg = updateBrain(ctx, older, progress)
        if (prefs.brainVersion == older) prefs.skipVersion = from   // don't auto-reinstall the one you left
        return msg
    }

    /** Adds facts from data/seed_facts.json (e.g. from your Claude export) once each. */
    fun applySeedFacts(ctx: Context): Int {
        val prefs = Prefs(ctx)
        val c = conn(prefs, "/repos/${prefs.githubRepo}/contents/data/seed_facts.json?ref=data")
        c.setRequestProperty("Accept", "application/vnd.github.raw")
        if (c.responseCode != 200) return 0
        val arr = JSONObject(c.body()).optJSONArray("facts") ?: return 0
        val applied = prefs.appliedSeeds.toMutableSet()
        val mem = Memory(ctx)
        var added = 0
        for (i in 0 until arr.length()) {
            val f = arr.getString(i)
            val key = Integer.toHexString(f.trim().lowercase().hashCode())
            if (key in applied) continue
            if (mem.remember(f)) added++
            applied += key
        }
        prefs.appliedSeeds = applied
        if (added > 0) ActivityLog.add(ctx, "Added $added facts about you from your import")
        return added
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
        runCatching { applySeedFacts(ctx) }
        runCatching { upload(ctx) }.onFailure { out.append("Upload failed: ${it.message}\n") }.onSuccess { out.append("Uploaded your data.\n") }
        val metered = ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        if (metered) out.append("On mobile data: brain download waits for Wi-Fi.")
        else runCatching { updateBrain(ctx, null, progress) }.onSuccess { out.append(it) }.onFailure { out.append("Brain check failed: ${it.message}") }
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
