package com.zigorminsk.backupcontrol;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** GitHub releases are checked periodically; the installer still requires user consent. */
final class UpdateManager {
    static final long INTERVAL_MS = 12L * 60 * 60 * 1000;
    private static final String API = "https://api.github.com/repos/zigorminsk-debug/Backup-Control/releases/latest";
    private static final String ASSET = "Backup-Control.apk";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Callback { void finished(String message); }
    interface Completion { void finished(); }
    private UpdateManager() { }

    static void checkAsync(Context context, boolean force, Callback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            String result;
            try {
                recoverDownload(app);
                long last = prefs(app).getLong("last_attempt", 0);
                long now = System.currentTimeMillis();
                if (!force && last <= now && now - last < INTERVAL_MS) {
                    result = status(app);
                } else {
                    prefs(app).edit().putLong("last_attempt", now).apply();
                    result = check(app);
                }
            } catch (Exception e) {
                result = "Не удалось проверить обновления. Проверьте интернет и попробуйте ещё раз.";
            }
            final String message = result;
            if (callback != null) MAIN.post(() -> callback.finished(message));
        });
    }

    static void downloadFinishedAsync(Context context, long id, Completion completion) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                if (id == prefs(app).getLong("download_id", -1)) recoverDownload(app);
            } catch (Exception ignored) {
                // A later check retries verification or download.
            } finally {
                completion.finished();
            }
        });
    }

    static String status(Context context) {
        SharedPreferences p = prefs(context);
        if (readyFile(context) != null) return "Версия " + p.getString("version_name", "") + " готова к установке";
        if (p.getLong("download_id", -1) != -1) return "Обновление загружается автоматически";
        return "Автопроверка каждые 12 часов";
    }

    static File readyFile(Context context) {
        SharedPreferences p = prefs(context);
        int code = p.getInt("ready_code", 0);
        if (code <= BuildConfig.VERSION_CODE) return null;
        File file = new File(new File(context.getFilesDir(), "updates"), "backup-control-" + code + ".apk");
        return file.isFile() ? file : null;
    }

    static int readyCode(Context context) { return prefs(context).getInt("ready_code", 0); }

    private static String check(Context context) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Backup-Control-Android");
        try {
            int response = connection.getResponseCode();
            if (response == 404) return "Опубликованных обновлений пока нет.";
            if (response != 200) throw new IllegalStateException("GitHub returned " + response);
            byte[] body;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (output.size() + length > 1024 * 1024) throw new IllegalStateException("Release metadata is too large");
                    output.write(buffer, 0, length);
                }
                body = output.toByteArray();
            }
            JSONObject release = new JSONObject(new String(body, StandardCharsets.UTF_8));
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return "Опубликованных обновлений пока нет.";
            String tag = release.optString("tag_name", "");
            int code = ReleaseRules.versionCode(tag);
            if (code < 0) return "Формат версии опубликованного обновления не поддерживается.";
            if (code <= BuildConfig.VERSION_CODE) {
                clearObsolete(context);
                return "У вас установлена последняя версия.";
            }
            String url = null;
            long assetSize = -1;
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset != null && ASSET.equals(asset.optString("name"))) {
                        url = asset.optString("browser_download_url", "");
                        assetSize = asset.optLong("size", -1);
                        break;
                    }
                }
            }
            if (!ReleaseRules.trustedAssetUrl(url) || !url.endsWith("/" + tag + "/" + ASSET)
                    || assetSize <= 0 || assetSize > 200L * 1024 * 1024)
                return "В выпуске не найден подходящий APK обновления.";
            SharedPreferences p = prefs(context);
            if (p.getInt("ready_code", 0) != 0 && p.getInt("ready_code", 0) != code) {
                // Never label an older ready APK as the newly found release.
                discardReady(context);
            }
            p.edit().putString("version_name", tag.substring(1)).putInt("latest_code", code)
                    .putString("asset_url", url).commit();
            if (p.getInt("ready_code", 0) == code && readyFile(context) != null) {
                return "Обновление готово к установке.";
            }
            enqueueIfNeeded(context, code, url);
            return "Найдена версия " + tag.substring(1) + ". Загрузка запущена.";
        } finally {
            connection.disconnect();
        }
    }

    private static void enqueueIfNeeded(Context context, int code, String url) throws Exception {
        SharedPreferences p = prefs(context);
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long oldId = p.getLong("download_id", -1);
        if (oldId != -1 && p.getInt("download_code", 0) == code && isActive(manager, oldId)) return;
        if (oldId != -1) manager.remove(oldId);
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) throw new IllegalStateException("Download storage unavailable");
        File dir = new File(base, "updates");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create download directory");
        String filename = "backup-control-" + code + ".apk";
        File existing = new File(dir, filename);
        if (existing.exists() && !existing.delete()) throw new IllegalStateException("Cannot replace previous download");
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setTitle("Backup Control")
                .setDescription("Загрузка обновления приложения")
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/" + filename);
        long id = manager.enqueue(request);
        p.edit().putLong("download_id", id).putInt("download_code", code).commit();
    }

    private static boolean isActive(DownloadManager manager, long id) {
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor != null && cursor.moveToFirst()) {
                int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                return state == DownloadManager.STATUS_PENDING || state == DownloadManager.STATUS_RUNNING
                        || state == DownloadManager.STATUS_PAUSED || state == DownloadManager.STATUS_SUCCESSFUL;
            }
            return false;
        }
    }

    private static void recoverDownload(Context context) throws Exception {
        SharedPreferences p = prefs(context);
        long id = p.getLong("download_id", -1);
        if (id == -1) return;
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        int state;
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                p.edit().remove("download_id").remove("download_code").commit();
                return;
            }
            state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        }
        if (state == DownloadManager.STATUS_FAILED) {
            manager.remove(id);
            p.edit().remove("download_id").remove("download_code").commit();
            return;
        }
        if (state != DownloadManager.STATUS_SUCCESSFUL) return;
        int code = p.getInt("download_code", 0);
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null || code <= BuildConfig.VERSION_CODE || code != p.getInt("latest_code", 0)) {
            manager.remove(id);
            p.edit().remove("download_id").remove("download_code").commit();
            return;
        }
        File downloaded = new File(new File(base, "updates"), "backup-control-" + code + ".apk");
        File privateDir = new File(context.getFilesDir(), "updates");
        if (!privateDir.exists() && !privateDir.mkdirs()) throw new IllegalStateException("Cannot create private update directory");
        File temp = new File(privateDir, "download.partial.apk");
        File target = new File(privateDir, "backup-control-" + code + ".apk");
        try {
            try (InputStream in = new FileInputStream(downloaded); FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.getFD().sync();
            }
            if (!validApk(context, temp, code)) throw new SecurityException("Update signature or version does not match");
            if (target.exists() && !target.delete()) throw new IllegalStateException("Cannot replace previous update");
            if (!temp.renameTo(target)) throw new IllegalStateException("Cannot save verified update");
            p.edit().putInt("ready_code", code).remove("download_id").remove("download_code").commit();
            manager.remove(id);
            Notifications.update(context, p.getString("version_name", ""));
            AppEvents.changed(context);
            // Keep storage bounded after a newer release has been verified.
            File[] files = privateDir.listFiles();
            if (files != null) for (File file : files) if (!file.equals(target)) file.delete();
        } catch (Exception e) {
            temp.delete();
            manager.remove(id);
            p.edit().remove("download_id").remove("download_code").commit();
            throw e;
        }
    }

    @SuppressWarnings("deprecation")
    static boolean validApk(Context context, File apk, int expectedCode) {
        try {
            PackageManager pm = context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo current = pm.getPackageInfo(context.getPackageName(), flags);
            PackageInfo update = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (update == null || !context.getPackageName().equals(update.packageName)) return false;
            long version = Build.VERSION.SDK_INT >= 28 ? update.getLongVersionCode() : update.versionCode;
            if (version != expectedCode || version <= BuildConfig.VERSION_CODE) return false;
            Signature[] currentSignatures = Build.VERSION.SDK_INT >= 28
                    ? current.signingInfo.getApkContentsSigners() : current.signatures;
            Signature[] updateSignatures = Build.VERSION.SDK_INT >= 28
                    ? update.signingInfo.getApkContentsSigners() : update.signatures;
            if (currentSignatures == null || updateSignatures == null || currentSignatures.length == 0) return false;
            Set<Signature> a = new HashSet<>(Arrays.asList(currentSignatures));
            Set<Signature> b = new HashSet<>(Arrays.asList(updateSignatures));
            return a.equals(b);
        } catch (Exception e) {
            return false;
        }
    }

    private static void discardReady(Context context) {
        SharedPreferences p = prefs(context);
        int ready = p.getInt("ready_code", 0);
        if (ready > 0) {
            new File(new File(context.getFilesDir(), "updates"), "backup-control-" + ready + ".apk").delete();
        }
        p.edit().remove("ready_code").commit();
        Notifications.cancelUpdate(context);
    }

    private static void clearObsolete(Context context) {
        SharedPreferences p = prefs(context);
        long id = p.getLong("download_id", -1);
        if (id != -1) {
            ((DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE)).remove(id);
        }
        File dir = new File(context.getFilesDir(), "updates");
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) file.delete();
        p.edit().remove("ready_code").remove("latest_code").remove("version_name")
                .remove("asset_url").remove("download_id").remove("download_code").commit();
        Notifications.cancelUpdate(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("app_updates", Context.MODE_PRIVATE);
    }
}
