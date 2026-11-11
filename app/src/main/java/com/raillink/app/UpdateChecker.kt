package com.raillink.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Checks GitHub for a newer release when the app opens. If there is one,
 * offers to download and install it. The new version installs over the old one
 * because every build is signed with the same Rail Link key.
 */
class UpdateChecker(private val activity: Activity) {

    private val apiUrl = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"
    private val apkUrl = "https://github.com/${BuildConfig.GITHUB_REPO}/releases/latest/download/RailLink.apk"
    private val apkName = "RailLink-update.apk"

    fun check() {
        Executors.newSingleThreadExecutor().execute {
            try {
                val conn = URL(apiUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "RailLinkApp")
                if (conn.responseCode != 200) return@execute
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val json = JSONObject(body)
                val tag = json.optString("tag_name") // e.g. "v1.0.12"
                val latest = tag.substringAfterLast('.').toIntOrNull() ?: return@execute
                if (latest > BuildConfig.VERSION_CODE) {
                    val notes = json.optString("body").take(400)
                    activity.runOnUiThread { offerUpdate(tag.removePrefix("v"), notes) }
                }
            } catch (_: Exception) {
                // No connection or GitHub unavailable: try again next time.
            }
        }
    }

    private fun offerUpdate(version: String, notes: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val message = buildString {
            append("Version $version is available. You have ${BuildConfig.VERSION_NAME}.")
            if (notes.isNotBlank()) append("\n\n").append(notes)
        }
        AlertDialog.Builder(activity, R.style.Theme_RailLink_Dialog)
            .setTitle("Update available")
            .setMessage(message)
            .setPositiveButton("Update") { _, _ -> startDownload() }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startDownload() {
        // Android needs permission to install apps from Rail Link.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                activity,
                "Allow Rail Link to install updates, then tap Update again.",
                Toast.LENGTH_LONG
            ).show()
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            } catch (_: Exception) {
                // Some TVs (e.g. Fire TV) don't have this screen; the system asks during install instead.
                Toast.makeText(
                    activity,
                    "Allow Rail Link under Settings > My Fire TV > Developer options > Install unknown apps.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val file = File(dir, apkName)
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Rail Link update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, apkName)

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)
        Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                try { activity.applicationContext.unregisterReceiver(this) } catch (_: Exception) {}
                install(file)
            }
        }
        ContextCompat.registerReceiver(
            activity.applicationContext,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun install(file: File) {
        if (!file.exists()) {
            Toast.makeText(activity, "Update download failed. Please try again.", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(activity, "Couldn't open the installer. Please install the update with the Downloader app.", Toast.LENGTH_LONG).show()
        }
    }
}
