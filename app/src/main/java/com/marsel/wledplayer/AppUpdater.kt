package com.marsel.wledplayer

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AppUpdater(private val context: Context) {

    // TODO: Замени на прямую ссылку к твоему update.json
    private val updateUrl = "http://217.144.189.172:45678/a8f3b9k2q7x1/update.json"

    fun check() {
        Log.d("AppUpdater", "--- Запуск проверки обновлений ---")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("AppUpdater", "Попытка подключения к: $updateUrl")
                val connection = URL(updateUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)

                    val serverVersion = json.getInt("versionCode")

                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    @Suppress("DEPRECATION")
                    val currentVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pInfo.longVersionCode.toInt()
                    } else {
                        pInfo.versionCode
                    }

                    if (serverVersion > currentVersion) {
                        val apkUrl = json.getString("apkUrl")
                        val whatsNew = json.optString("whatsNew", "Доступна новая версия приложения.")

                        withContext(Dispatchers.Main) {
                            showUpdateDialog(whatsNew, apkUrl)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AppUpdater", "Критическая ошибка проверки обновлений", e)
            }
        }
    }

    private fun showUpdateDialog(whatsNew: String, apkUrl: String) {
        AlertDialog.Builder(context)
            .setTitle("Доступно обновление!")
            .setMessage(whatsNew)
            .setCancelable(false)
            .setPositiveButton("Обновить") { _, _ -> startDownload(apkUrl) }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun startDownload(apkUrl: String) {
        val fileName = "wled_player_update.apk"
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Обновление WLED Player")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)

        val downloadId = downloadManager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    context.unregisterReceiver(this)
                    installApk(file)
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("AppUpdater", "Ошибка запуска установки APK", e)
        }
    }
}