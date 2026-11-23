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

    fun getJson(prefs: Prefs, path: String): Any? {
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
    fun openAsset(prefs: Prefs, assetApiUrl: String): InputStream {
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

    /** Like openAsset, but continues from byte [from] when the server allows it. Returns (stream, resumed). */
    private fun openAssetFrom(prefs: Prefs, assetApiUrl: String, from: Long): Pair<InputStream, Boolean> {
        val c = conn(prefs, assetApiUrl)
        c.setRequestProperty("Accept", "application/octet-stream")
        c.instanceFollowRedirects = false
        val target = when (c.responseCode) {
            in 300..399 -> URL(c.getHeaderField("Location"))
            in 200..299 -> return c.inputStream to false
            else -> throw IllegalStateException("Download failed (${c.responseCode})")
        }
        val d = (target.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 120_000
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
        }
        return when (d.responseCode) {
            206 -> d.inputStream to true
            200 -> d.inputStream to false
            else -> throw IllegalStateException("Download failed (${d.responseCode})")
        }
    }

    private fun sha256(f: File, offset: Long, length: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(1 shl 20)
            var left = length
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                md.update(buf, 0, n); left -= n
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
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

    /** The newest brain version that should be installed, or null if the phone already has it. */
    fun newerBrain(ctx: Context): Int? {
        val prefs = Prefs(ctx)
        val v = brainReleases(prefs).keys.filter { it > prefs.skipVersion }.maxOrNull() ?: return null
        return v.takeIf { it != prefs.brainVersion || !Store.brain(ctx).exists() }
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
        File(ctx.filesDir, "topshe_brain.download").delete()
        // One partial file per version: an interrupted download continues from where it stopped
        val tmp = File(ctx.filesDir, "topshe_brain_v$version.download")
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("topshe_brain_v") && it.name.endsWith(".download") && it != tmp }
            ?.forEach { it.delete() }
        val parts = manifest.getJSONArray("parts")
        val total = (0 until parts.length()).sumOf { parts.getJSONObject(it).getLong("bytes") }
        val have0 = if (tmp.exists()) tmp.length().coerceAtMost(total) else 0L
        val free = ctx.filesDir.usableSpace
        if (free < total - have0 + 300L * 1024 * 1024)
            return "Not enough space: the brain needs %.1f GB free (%.1f GB free now).".format((total - have0) / 1e9 + 0.3, free / 1e9)
        java.io.RandomAccessFile(tmp, "rw").use { raf ->
            var offset = 0L
            var shown = -1L
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val size = p.getLong("bytes")
                val have = (raf.length() - offset).coerceIn(0, size)
                if (have < size) {
                    val (input, resumed) = openAssetFrom(prefs, asset(p.getString("name"))!!.getString("url"), have)
                    var pos = offset + if (resumed) have else 0L
                    raf.seek(pos)
                    input.use { s ->
                        val buf = ByteArray(1 shl 20)
                        while (pos < offset + size) {
                            val n = s.read(buf, 0, minOf(buf.size.toLong(), offset + size - pos).toInt())
                            if (n <= 0) break
                            raf.write(buf, 0, n)
                            pos += n
                            val mb = pos shr 20
                            if (mb / 8 != shown) {
                                shown = mb / 8
                                progress("Downloading brain v$version: ${pos shr 20} / ${total shr 20} MB")
                            }
                        }
                    }
                    if (pos < offset + size) throw java.io.IOException("connection dropped at ${pos shr 20} MB")
                }
                offset += size
            }
            raf.setLength(total)
        }
        // Check each part; a bad part is cut off and downloaded again on the next try
        var off = 0L
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            progress("Checking the download (part ${i + 1} of ${parts.length()})...")
            if (sha256(tmp, off, p.getLong("bytes")) != p.getString("sha256")) {
                java.io.RandomAccessFile(tmp, "rw").use { it.setLength(off) }
                throw java.io.IOException("part ${i + 1} was damaged; downloading it again")
            }
            off += p.getLong("bytes")
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

    private fun notify(ctx: Context, text: String, id: Int = 42) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("topshe_updates", "Topshe updates", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(id, android.app.Notification.Builder(ctx, "topshe_updates")
            .setSmallIcon(R.drawable.ic_stat_topshe)
            .setContentTitle("Topshe").setContentText(text).setAutoCancel(true).build())
    }

    /** Full sync. Brain downloads only happen on unmetered (Wi-Fi) networks. */
    fun runSync(ctx: Context, progress: (String) -> Unit = {}): String {
        val prefs = Prefs(ctx)
        if (prefs.githubToken.isBlank()) return "Add your GitHub token first."
        val out = StringBuilder()
        runCatching { applySeedFacts(ctx) }
        runCatching { upload(ctx) }.onFailure { out.append("Upload failed: ${it.message}\n") }.onSuccess { out.append("Uploaded your data.\n") }
        runCatching { newerBrain(ctx) }.onSuccess { v ->
            if (v == null) out.append("Brain is up to date (v${prefs.brainVersion}).")
            else {
                DownloadWorker.brain(ctx)   // foreground download: survives screen-off, resumes if interrupted
                val metered = ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
                out.append("Downloading brain v$v in the background" + if (metered) " (starts when you're on Wi-Fi)." else " - see the notification.")
            }
        }.onFailure { out.append("Brain check failed: ${it.message}") }
        runCatching { Updater.available(ctx) }.getOrNull()?.let { n ->
            out.append("\nApp build $n is available: open Topshe and tap Update the app.")
            notify(ctx, "App build $n is ready. Open Topshe and tap Update the app.", 43)
        }
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
