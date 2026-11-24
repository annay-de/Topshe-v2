package org.annay.topshe

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONArray

/**
 * App updates without the browser: Alfred fetches the newest build-N APK from your repo's Releases
 * (with the same GitHub token) and hands it to Android's own installer, which asks you to confirm.
 * Installs coming from an app rather than a browser or file manager aren't hit by Play Protect's
 * "sideloaded from the internet" block, so there's no need to switch Play Protect off each time.
 */
object Updater {
    fun installedBuild(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    data class AppBuild(val number: Int, val url: String, val bytes: Long)

    /** The newest published app build (number, APK asset API url, size), or null. */
    fun latest(ctx: Context): AppBuild? {
        val prefs = Prefs(ctx)
        val rels = Sync.getJson(prefs, "/repos/${prefs.githubRepo}/releases?per_page=100") as? JSONArray ?: return null
        var best: AppBuild? = null
        for (i in 0 until rels.length()) {
            val r = rels.getJSONObject(i)
            val n = r.optString("tag_name").removePrefix("build-").toIntOrNull() ?: continue
            if (!r.optString("tag_name").startsWith("build-") || r.optBoolean("draft")) continue
            val assets = r.optJSONArray("assets") ?: continue
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
            if (best == null || n > best.number) best = AppBuild(n, apk.getString("url"), apk.optLong("size"))
        }
        return best
    }

    /** "Build 31 is ready" or null if this is the newest. */
    fun available(ctx: Context): Int? {
        if (Prefs(ctx).githubToken.isBlank()) return null
        val n = latest(ctx)?.number ?: return null
        return n.takeIf { it > installedBuild(ctx) }
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Downloads the newest APK into an install session; Android then shows its "Update?" screen. */
    fun downloadAndInstall(ctx: Context, progress: (String) -> Unit): String {
        if (Prefs(ctx).githubToken.isBlank()) return "Add your GitHub token first (step 5)."
        val b = latest(ctx) ?: return "No app builds found."
        val n = b.number
        val url = b.url
        val totalMb = b.bytes shr 20
        if (n <= installedBuild(ctx)) return "The app is up to date (build ${installedBuild(ctx)})."
        if (!canInstall(ctx)) return "Allow Alfred to install updates first, then tap again."
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(ctx.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("topshe-build-$n.apk", 0, -1).use { out ->
                Sync.openAsset(Prefs(ctx), url).use { input ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    var shown = -1L
                    while (true) {
                        val k = input.read(buf)
                        if (k <= 0) break
                        out.write(buf, 0, k)
                        total += k
                        if (total shr 21 != shown) { shown = total shr 21; progress("Downloading build $n: ${total shr 20} / $totalMb MB") }
                    }
                }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), flags)
            session.commit(pi.intentSender)
        }
        ActivityLog.add(ctx, "App update: build $n downloaded, waiting for you to confirm")
        return "Build $n downloaded. Confirm the update on the next screen."
    }
}

/** Gets the installer's answer: shows its confirm screen, or logs the result. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                              else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> ActivityLog.add(ctx, "App updated")
            else -> ActivityLog.add(ctx, "App update didn't finish ($status): " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled"))
        }
    }
}
