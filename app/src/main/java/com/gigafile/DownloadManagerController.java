package com.gigafile;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.URLUtil;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class DownloadManagerController {
    public static final int STATUS_QUEUED = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_COMPLETE = 2;
    public static final int STATUS_FAILED = 3;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicBoolean REFRESH_RUNNING = new AtomicBoolean();
    private static final Object REFRESH_LOCK = new Object();
    private static final ArrayList<Runnable> REFRESH_CALLBACKS = new ArrayList<>();
    private static final ExecutorService REFRESH_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "GigaFile-DownloadHistory");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final String DOWNLOAD_RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/GigaFile/";

    private DownloadManagerController() {
    }

    public static long nextId() {
        return System.currentTimeMillis() * 1000L + (SEQUENCE.incrementAndGet() % 1000);
    }

    public static DownloadRequest startDownload(Context context, long id, String url, String name,
                                                String contentDisposition, String userAgent,
                                                String referer, String mimeType) {
        if (context == null || id <= 0L || !GigaFilePolicy.isAllowedFileTransfer(url)) {
            throw new IllegalArgumentException("Invalid download request");
        }
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("Download manager unavailable");
        }
        String fileName = uniqueFileName(context, id,
                resolveFileName(url, name, contentDisposition, mimeType));
        String cleanMime = mimeType == null || mimeType.trim().isEmpty()
                ? "" : mimeType.split(";", 2)[0].trim();
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle(fileName);
        request.setDescription("GigaFileからダウンロード");
        if (!cleanMime.isEmpty()) {
            request.setMimeType(cleanMime);
        }
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "GigaFile/" + fileName);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            request.allowScanningByMediaScanner();
        }
        if (userAgent != null && !userAgent.isEmpty()) {
            request.addRequestHeader("User-Agent", userAgent);
        }
        if (referer != null && GigaFilePolicy.isAllowedNavigation(referer)) {
            request.addRequestHeader("Referer", referer);
        }
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                request.addRequestHeader("Cookie", cookie);
            }
        } catch (Exception ignored) {
        }
        long downloadManagerId = manager.enqueue(request);
        if (downloadManagerId <= 0L) {
            throw new IllegalStateException("Download could not be queued");
        }
        return new DownloadRequest(downloadManagerId, fileName);
    }

    public static void refreshHistoryAsync(Context context, Runnable completion) {
        if (context == null) {
            return;
        }
        boolean start;
        synchronized (REFRESH_LOCK) {
            if (completion != null) {
                REFRESH_CALLBACKS.add(completion);
            }
            start = !REFRESH_RUNNING.getAndSet(true);
        }
        if (!start) {
            return;
        }
        Context appContext = context.getApplicationContext();
        REFRESH_EXECUTOR.execute(() -> {
            try {
                refreshHistoryInternal(appContext);
            } finally {
                ArrayList<Runnable> callbacks;
                synchronized (REFRESH_LOCK) {
                    REFRESH_RUNNING.set(false);
                    callbacks = new ArrayList<>(REFRESH_CALLBACKS);
                    REFRESH_CALLBACKS.clear();
                }
                for (Runnable callback : callbacks) {
                    MAIN_HANDLER.post(callback);
                }
            }
        });
    }

    private static void refreshHistoryInternal(Context context) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            return;
        }
        ArrayList<JSONObject> entries = HistoryStore.getDownloads(context);
        Map<Long, Long> appIds = new HashMap<>();
        ArrayList<Long> managerIds = new ArrayList<>();
        for (JSONObject entry : entries) {
            long managerId = entry.optLong("downloadManagerId", -1L);
            long appId = entry.optLong("id", -1L);
            if (managerId > 0L && appId > 0L) {
                appIds.put(managerId, appId);
                managerIds.add(managerId);
            }
        }
        if (managerIds.isEmpty()) {
            return;
        }
        long[] filters = new long[managerIds.size()];
        for (int i = 0; i < managerIds.size(); i++) {
            filters[i] = managerIds.get(i);
        }
        Cursor cursor = null;
        Map<Long, HistoryStore.DownloadUpdate> updates = new HashMap<>();
        try {
            cursor = manager.query(new DownloadManager.Query().setFilterById(filters));
            if (cursor == null) {
                return;
            }
            int idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
            int statusColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS);
            int bytesColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
            int totalColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
            int localUriColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI);
            int reasonColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON);
            while (cursor.moveToNext()) {
                long managerId = cursor.getLong(idColumn);
                Long appId = appIds.get(managerId);
                if (appId == null) {
                    continue;
                }
                int managerStatus = cursor.getInt(statusColumn);
                int status;
                int reason = cursor.getInt(reasonColumn);
                String message = "";
                if (managerStatus == DownloadManager.STATUS_PENDING) {
                    status = STATUS_QUEUED;
                } else if (managerStatus == DownloadManager.STATUS_RUNNING) {
                    status = STATUS_RUNNING;
                } else if (managerStatus == DownloadManager.STATUS_PAUSED) {
                    status = STATUS_QUEUED;
                    message = pausedMessage(reason);
                } else if (managerStatus == DownloadManager.STATUS_SUCCESSFUL) {
                    status = STATUS_COMPLETE;
                } else if (managerStatus == DownloadManager.STATUS_FAILED) {
                    status = STATUS_FAILED;
                    message = errorMessage(reason);
                } else {
                    continue;
                }
                long bytes = Math.max(0L, cursor.getLong(bytesColumn));
                long total = cursor.getLong(totalColumn);
                if (status == STATUS_COMPLETE && total <= 0L) {
                    total = bytes;
                }
                String localUri = cursor.isNull(localUriColumn) ? null : cursor.getString(localUriColumn);
                updates.put(appId, new HistoryStore.DownloadUpdate(status, bytes, total,
                        localUri, null, message));
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        HistoryStore.updateDownloads(context, updates);
    }

    private static String resolveFileName(String url, String suggestedName,
                                          String contentDisposition, String mimeType) {
        String guessed = "";
        try {
            guessed = URLUtil.guessFileName(url, contentDisposition, mimeType);
        } catch (Exception ignored) {
        }
        if (isUsableName(guessed)) {
            return safeFileName(guessed);
        }
        if (isUsableName(suggestedName)) {
            return safeFileName(suggestedName);
        }
        String fileId = null;
        try {
            fileId = Uri.parse(url).getQueryParameter("file");
        } catch (Exception ignored) {
        }
        if (fileId != null && fileId.matches("[0-9]{4}-[a-fA-F0-9]{16,64}")) {
            String path = Uri.parse(url).getPath();
            return "GigaFile-" + fileId + ("/dl_zip.php".equalsIgnoreCase(path) ? ".zip" : "");
        }
        return "GigaFile-ダウンロード";
    }

    private static String uniqueFileName(Context context, long currentId, String requestedName) {
        String base = requestedName;
        String extension = "";
        int dot = requestedName.lastIndexOf('.');
        if (dot > 0) {
            base = requestedName.substring(0, dot);
            extension = requestedName.substring(dot);
        }
        String candidate = requestedName;
        for (int index = 0; index < 10000; index++) {
            if (!isFileNameUsed(context, currentId, candidate)) {
                return candidate;
            }
            candidate = base + " (" + (index + 1) + ")" + extension;
        }
        return base + " (" + System.currentTimeMillis() + ")" + extension;
    }

    private static boolean isFileNameUsed(Context context, long currentId, String name) {
        for (JSONObject entry : HistoryStore.getDownloads(context)) {
            if (entry.optLong("id", -1L) != currentId
                    && name.equalsIgnoreCase(entry.optString("name", ""))
                    && entry.optInt("status", STATUS_QUEUED) != STATUS_FAILED) {
                return true;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Cursor cursor = null;
            try {
                String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                        + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
                cursor = context.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.MediaColumns._ID}, selection,
                        new String[]{name, DOWNLOAD_RELATIVE_PATH}, null);
                return cursor != null && cursor.moveToFirst();
            } catch (Exception ignored) {
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        } else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "GigaFile");
            return new File(directory, name).exists();
        }
        return false;
    }

    private static boolean isUsableName(String value) {
        if (value == null) {
            return false;
        }
        String name = value.trim();
        if (name.isEmpty() || name.length() > 240 || name.contains("ファイル名表示中")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return !"download".equals(lower)
                && !"download.php".equals(lower)
                && !"download.zip".equals(lower)
                && !"dl_zip.php".equals(lower)
                && !"file".equals(lower)
                && !"undefined".equals(lower)
                && !"null".equals(lower)
                && !"ファイル".equals(name);
    }

    private static String safeFileName(String value) {
        String name = value == null ? "download" : value.trim();
        name = name.replace('\\', '_').replace('/', '_').replace(':', '_')
                .replace('?', '_').replace('*', '_').replace('"', '_')
                .replace('<', '_').replace('>', '_').replace('|', '_').replace((char) 0, '_');
        StringBuilder clean = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            clean.append(c < 32 || c == 127 ? '_' : c);
        }
        name = clean.toString().trim();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.isEmpty()) {
            name = "download";
        }
        int trailingDotEnd = name.length();
        while (trailingDotEnd > 0 && name.charAt(trailingDotEnd - 1) == '.') {
            trailingDotEnd--;
        }
        if (trailingDotEnd != name.length()) {
            name = name.substring(0, trailingDotEnd);
        }
        if (name.length() > 180) {
            int end = name.offsetByCodePoints(0, Math.min(180, name.codePointCount(0, name.length())));
            name = name.substring(0, end);
        }
        return name;
    }

    private static String pausedMessage(int reason) {
        if (reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK) {
            return "ネットワーク待機中";
        }
        if (reason == DownloadManager.PAUSED_WAITING_TO_RETRY) {
            return "再試行待ち";
        }
        if (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) {
            return "Wi-Fi待機中";
        }
        return "一時停止中";
    }

    private static String errorMessage(int reason) {
        if (reason == DownloadManager.ERROR_CANNOT_RESUME) {
            return "ダウンロードを再開できませんでした";
        }
        if (reason == DownloadManager.ERROR_DEVICE_NOT_FOUND) {
            return "保存先が利用できません";
        }
        if (reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS) {
            return "同名ファイルが既に存在します";
        }
        if (reason == DownloadManager.ERROR_FILE_ERROR) {
            return "ファイルの保存に失敗しました";
        }
        if (reason == DownloadManager.ERROR_HTTP_DATA_ERROR) {
            return "通信に失敗しました";
        }
        if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) {
            return "保存容量が不足しています";
        }
        if (reason == DownloadManager.ERROR_TOO_MANY_REDIRECTS) {
            return "転送先の案内が多すぎます";
        }
        if (reason == DownloadManager.ERROR_UNHANDLED_HTTP_CODE) {
            return "サーバーがダウンロードを拒否しました";
        }
        return "ダウンロードに失敗しました";
    }

    public static final class DownloadRequest {
        public final long downloadManagerId;
        public final String fileName;

        DownloadRequest(long downloadManagerId, String fileName) {
            this.downloadManagerId = downloadManagerId;
            this.fileName = fileName;
        }
    }
}
