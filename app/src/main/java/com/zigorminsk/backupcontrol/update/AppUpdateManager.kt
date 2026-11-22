package com.zigorminsk.backupcontrol.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.zigorminsk.backupcontrol.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateUiState(
    val isChecking: Boolean = false,
    val statusMessage: String = "Автопроверка обновлений GitHub Releases",
    val readyVersionName: String? = null,
    val isDownloading: Boolean = false
)

object AppUpdateManager {
    private const val INTERVAL_MS = 12L * 60 * 60 * 1000
    private const val RETRY_MS = 15L * 60 * 1000
    private const val API_URL = "https://api.github.com/repos/zigorminsk-debug/Backup-Control/releases/latest"
    private const val ASSET_NAME = "Backup-Control.apk"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    fun refreshState(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        val ready = readyFile(app)
        val downloading = p.getLong("download_id", -1L) != -1L
        val vName = p.getString("version_name", null)
        _state.value = AppUpdateUiState(
            isChecking = false,
            statusMessage = when {
                ready != null && !vName.isNullOrBlank() -> "Версия $vName готова к установке"
                downloading -> "Обновление загружается в фоне…"
                else -> "Установлена версия ${BuildConfig.VERSION_NAME} • Автопроверка GitHub Releases"
            },
            readyVersionName = if (ready != null) vName else null,
            isDownloading = downloading
        )
    }

    fun checkAsync(context: Context, force: Boolean, onMessage: ((String) -> Unit)? = null) {
        val app = context.applicationContext
        _state.value = _state.value.copy(isChecking = true, statusMessage = "Проверка обновлений на GitHub…")
        scope.launch {
            val resultMessage = try {
                recoverDownload(app)
                val p = prefs(app)
                val next = p.getLong("next_attempt", 0L)
                val now = System.currentTimeMillis()
                if (!force && next > now) {
                    computeStatusText(app)
                } else {
                    try {
                        val msg = performCheck(app)
                        p.edit().putLong("next_attempt", now + INTERVAL_MS).apply()
                        msg
                    } catch (e: Exception) {
                        p.edit().putLong("next_attempt", now + RETRY_MS).apply()
                        throw e
                    }
                }
            } catch (_: Exception) {
                "Не удалось проверить обновления. Проверьте подключение к интернету."
            }

            val ready = readyFile(app)
            val p = prefs(app)
            _state.value = AppUpdateUiState(
                isChecking = false,
                statusMessage = resultMessage,
                readyVersionName = if (ready != null) p.getString("version_name", null) else null,
                isDownloading = p.getLong("download_id", -1L) != -1L
            )
            if (onMessage != null) {
                CoroutineScope(Dispatchers.Main).launch { onMessage(resultMessage) }
            }
        }
    }

    fun onDownloadCompleted(context: Context, downloadId: Long, onDone: () -> Unit) {
        val app = context.applicationContext
        scope.launch {
            try {
                if (downloadId == prefs(app).getLong("download_id", -1L)) {
                    recoverDownload(app)
                }
                refreshState(app)
            } catch (_: Exception) {
            } finally {
                onDone()
            }
        }
    }

    fun launchInstall(context: Context): Boolean {
        val ready = readyFile(context) ?: return false
        if (!validApk(context, ready, readyCode(context))) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(permIntent)
            return false
        }
        val uri = Uri.parse("content://${context.packageName}.updates/update.apk")
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }

    fun readyFile(context: Context): File? {
        val p = prefs(context)
        val code = p.getInt("ready_code", 0)
        if (code <= BuildConfig.VERSION_CODE) return null
        val file = File(File(context.filesDir, "updates"), "backup-control-$code.apk")
        return if (file.isFile) file else null
    }

    fun readyCode(context: Context): Int = prefs(context).getInt("ready_code", 0)

    private fun computeStatusText(context: Context): String {
        val p = prefs(context)
        if (readyFile(context) != null) {
            return "Версия ${p.getString("version_name", "")} готова к установке"
        }
        if (p.getLong("download_id", -1L) != -1L) {
            return "Обновление загружается автоматически"
        }
        return "У вас актуальная версия (${BuildConfig.VERSION_NAME})"
    }

    private fun performCheck(context: Context): String {
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Backup-Control-Android")
        }
        try {
            val response = conn.responseCode
            if (response == 404) return "Опубликованных обновлений пока нет."
            if (response != 200) throw IllegalStateException("HTTP $response")
            val bodyBytes = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    if (out.size() + n > 1024 * 1024) throw IllegalStateException("Response too large")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            val release = JSONObject(String(bodyBytes, Charsets.UTF_8))
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
                return "Опубликованных обновлений пока нет."
            }
            val tag = release.optString("tag_name", "")
            val code = ReleaseRules.versionCode(tag)
            if (code < 0) return "Формат версии релиза не поддерживается."
            if (code <= BuildConfig.VERSION_CODE) {
                clearObsolete(context)
                return "У вас установлена последняя версия (${BuildConfig.VERSION_NAME})."
            }

            var downloadUrl: String? = null
            var assetSize = -1L
            val assets = release.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i)
                    if (asset != null && ASSET_NAME == asset.optString("name")) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        assetSize = asset.optLong("size", -1L)
                        break
                    }
                }
            }
            if (!ReleaseRules.trustedAssetUrl(downloadUrl) ||
                downloadUrl?.endsWith("/$tag/$ASSET_NAME") != true ||
                assetSize <= 0L || assetSize > 200L * 1024 * 1024
            ) {
                return "В релизе не найден подходящий файл $ASSET_NAME."
            }

            val p = prefs(context)
            if (p.getInt("ready_code", 0) != 0 && p.getInt("ready_code", 0) != code) {
                discardReady(context)
            }
            val vName = tag.removePrefix("v")
            p.edit()
                .putString("version_name", vName)
                .putInt("latest_code", code)
                .putString("asset_url", downloadUrl)
                .commit()

            if (p.getInt("ready_code", 0) == code && readyFile(context) != null) {
                return "Версия $vName готова к установке."
            }
            enqueueDownloadIfNeeded(context, code, downloadUrl)
            return "Найдена версия $vName. Загрузка запущена."
        } finally {
            conn.disconnect()
        }
    }

    private fun enqueueDownloadIfNeeded(context: Context, code: Int, url: String) {
        val p = prefs(context)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val oldId = p.getLong("download_id", -1L)
        if (oldId != -1L && p.getInt("download_code", 0) == code && isDownloadActive(dm, oldId)) return
        if (oldId != -1L) dm.remove(oldId)

        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("Хранилище недоступно")
        val dir = File(base, "updates")
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Не удалось создать папку updates")
        val filename = "backup-control-$code.apk"
        val existing = File(dir, filename)
        if (existing.exists()) existing.delete()

        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Backup Control")
            .setDescription("Загрузка обновления приложения")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/$filename")
        val id = dm.enqueue(req)
        p.edit().putLong("download_id", id).putInt("download_code", code).commit()
    }

    private fun isDownloadActive(dm: DownloadManager, id: Long): Boolean {
        dm.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                return status == DownloadManager.STATUS_PENDING ||
                    status == DownloadManager.STATUS_RUNNING ||
                    status == DownloadManager.STATUS_PAUSED ||
                    status == DownloadManager.STATUS_SUCCESSFUL
            }
        }
        return false
    }

    private fun recoverDownload(context: Context) {
        val p = prefs(context)
        val id = p.getLong("download_id", -1L)
        if (id == -1L) return
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val state = dm.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) {
                p.edit().remove("download_id").remove("download_code").commit()
                return
            }
            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        } ?: return

        if (state == DownloadManager.STATUS_FAILED) {
            dm.remove(id)
            p.edit().remove("download_id").remove("download_code").commit()
            return
        }
        if (state != DownloadManager.STATUS_SUCCESSFUL) return

        val code = p.getInt("download_code", 0)
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (base == null || code <= BuildConfig.VERSION_CODE || code != p.getInt("latest_code", 0)) {
            dm.remove(id)
            p.edit().remove("download_id").remove("download_code").commit()
            return
        }
        val downloaded = File(File(base, "updates"), "backup-control-$code.apk")
        val privateDir = File(context.filesDir, "updates")
        if (!privateDir.exists()) privateDir.mkdirs()
        val temp = File(privateDir, "download.partial.apk")
        val target = File(privateDir, "backup-control-$code.apk")

        try {
            FileInputStream(downloaded).use { input ->
                FileOutputStream(temp).use { out ->
                    val buf = ByteArray(32_768)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                    }
                    out.fd.sync()
                }
            }
            if (!validApk(context, temp, code)) {
                throw SecurityException("Подпись или код версии APK не совпадают")
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                throw IllegalStateException("Не удалось сохранить проверенное обновление")
            }
            p.edit().putInt("ready_code", code).remove("download_id").remove("download_code").commit()
            dm.remove(id)
            privateDir.listFiles()?.forEach { f -> if (f != target) f.delete() }
        } catch (e: Exception) {
            temp.delete()
            dm.remove(id)
            p.edit().remove("download_id").remove("download_code").commit()
            throw e
        }
    }

    @Suppress("DEPRECATION")
    fun validApk(context: Context, apk: File, expectedCode: Int): Boolean {
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val current = pm.getPackageInfo(context.packageName, flags)
            val update = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
            if (context.packageName != update.packageName) return false
            val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                update.longVersionCode
            } else {
                update.versionCode.toLong()
            }
            if (version != expectedCode.toLong() || version <= BuildConfig.VERSION_CODE) return false

            val currentSigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                current.signingInfo?.apkContentsSigners
            } else {
                current.signatures
            }
            val updateSigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                update.signingInfo?.apkContentsSigners
            } else {
                update.signatures
            }
            if (currentSigs.isNullOrEmpty() || updateSigs.isNullOrEmpty()) return false
            currentSigs.toSet() == updateSigs.toSet()
        } catch (_: Exception) {
            false
        }
    }

    private fun discardReady(context: Context) {
        val p = prefs(context)
        val ready = p.getInt("ready_code", 0)
        if (ready > 0) {
            File(File(context.filesDir, "updates"), "backup-control-$ready.apk").delete()
        }
        p.edit().remove("ready_code").commit()
    }

    private fun clearObsolete(context: Context) {
        val p = prefs(context)
        val id = p.getLong("download_id", -1L)
        if (id != -1L) {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
        }
        File(context.filesDir, "updates").listFiles()?.forEach { it.delete() }
        p.edit()
            .remove("ready_code")
            .remove("latest_code")
            .remove("version_name")
            .remove("asset_url")
            .remove("download_id")
            .remove("download_code")
            .commit()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
}
