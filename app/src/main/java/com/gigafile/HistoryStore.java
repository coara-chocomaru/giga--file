package com.gigafile;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HistoryStore {
    private static final String PREFS = "gigafile_history_v1";
    private static final String UPLOADS = "uploads";
    private static final String DOWNLOADS = "downloads";
    private static final String QR_DIRECTORY = "upload_qr";
    private static final int MAX_ITEMS = 300;
    private static final int MAX_QR_BYTES = 262144;
    private static final int MAX_QR_DATA_URL_CHARS = 360000;
    private static final Object LOCK = new Object();
    private static final ExecutorService UPLOAD_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "GigaFile-UploadHistory");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private HistoryStore() {
    }

    public static void addUploadAsync(Context context, String fileName, String url, long sizeBytes,
                                      String qrDataUrl) {
        addUploadAsync(context, fileName, url, sizeBytes, qrDataUrl, null, null);
    }

    public static void addUploadAsync(Context context, String fileName, String url, long sizeBytes,
                                      String qrDataUrl, String deleteKey, String downloadKey) {
        if (context == null || !GigaFilePolicy.isAllowedDownload(url)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        String safeQrDataUrl = qrDataUrl != null && qrDataUrl.length() <= MAX_QR_DATA_URL_CHARS
                ? qrDataUrl : null;
        String safeDeleteKey = cleanDeleteKey(deleteKey);
        String safeDownloadKey = cleanDownloadKey(downloadKey);
        UPLOAD_EXECUTOR.execute(() -> addUpload(appContext, fileName, url, sizeBytes,
                safeQrDataUrl, safeDeleteKey, safeDownloadKey));
    }

    public static void updateUploadKeysAsync(Context context, String url, String deleteKey,
                                             String downloadKey) {
        if (context == null || !GigaFilePolicy.isAllowedDownload(url)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        String safeDeleteKey = cleanDeleteKey(deleteKey);
        String safeDownloadKey = cleanDownloadKey(downloadKey);
        if (safeDeleteKey == null && safeDownloadKey == null) {
            return;
        }
        UPLOAD_EXECUTOR.execute(() -> updateUploadKeys(appContext, url, safeDeleteKey, safeDownloadKey));
    }

    public static void removeUploadAsync(Context context, String url) {
        if (context == null || !GigaFilePolicy.isAllowedDownload(url)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        UPLOAD_EXECUTOR.execute(() -> remove(appContext, UPLOADS, "url", url));
    }

    public static void addUpload(Context context, String fileName, String url, long sizeBytes) {
        addUpload(context, fileName, url, sizeBytes, null, null, null);
    }

    public static void addUpload(Context context, String fileName, String url, long sizeBytes,
                                 String qrDataUrl) {
        addUpload(context, fileName, url, sizeBytes, qrDataUrl, null, null);
    }

    public static void addUpload(Context context, String fileName, String url, long sizeBytes,
                                 String qrDataUrl, String deleteKey, String downloadKey) {
        if (context == null || !GigaFilePolicy.isAllowedDownload(url)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            JSONArray old = read(appContext, UPLOADS);
            JSONArray next = new JSONArray();
            JSONObject previous = null;
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && url.equals(item.optString("url", ""))) {
                    previous = item;
                    break;
                }
            }
            String qrFile = saveQrImage(appContext, url, qrDataUrl);
            if (qrFile == null && previous != null) {
                qrFile = validQrName(previous.optString("qr", ""));
            }
            String safeDeleteKey = cleanDeleteKey(deleteKey);
            String safeDownloadKey = cleanDownloadKey(downloadKey);
            long currentTime = System.currentTimeMillis();
            long uploadTime = previous == null ? currentTime : previous.optLong("time", currentTime);
            if (uploadTime <= 0L) {
                uploadTime = currentTime;
            }
            String expiryDate = previous == null ? ""
                    : previous.optString("expiryDate", "");
            if (GigaFileExpiry.fromStoredOrResolve(url, uploadTime, expiryDate) == null) {
                expiryDate = GigaFileExpiry.toIsoDate(GigaFileExpiry.resolve(url, uploadTime));
            }
            String shortUrl = previous == null ? null
                    : getValidShortUrl(previous.optString("shortUrl", ""));
            if (previous != null) {
                if (safeDeleteKey == null) {
                    safeDeleteKey = cleanDeleteKey(previous.optString("delkey", ""));
                }
                if (safeDownloadKey == null) {
                    safeDownloadKey = cleanDownloadKey(previous.optString("dlkey", ""));
                }
            }
            JSONObject entry = new JSONObject();
            try {
                entry.put("name", cleanName(fileName));
                entry.put("url", url);
                entry.put("time", uploadTime);
                if (!expiryDate.isEmpty()) {
                    entry.put("expiryDate", expiryDate);
                }
                entry.put("size", Math.max(0L, sizeBytes));
                if (qrFile != null) {
                    entry.put("qr", qrFile);
                }
                if (safeDeleteKey != null) {
                    entry.put("delkey", safeDeleteKey);
                }
                if (safeDownloadKey != null) {
                    entry.put("dlkey", safeDownloadKey);
                }
                if (shortUrl != null) {
                    entry.put("shortUrl", shortUrl);
                }
                next.put(entry);
                Set<String> retained = new HashSet<>();
                if (qrFile != null) {
                    retained.add(qrFile);
                }
                for (int i = 0; i < old.length() && next.length() < MAX_ITEMS; i++) {
                    JSONObject item = old.optJSONObject(i);
                    if (item == null || url.equals(item.optString("url", ""))) {
                        continue;
                    }
                    next.put(item);
                    String oldQr = validQrName(item.optString("qr", ""));
                    if (oldQr != null) {
                        retained.add(oldQr);
                    }
                }
                write(appContext, UPLOADS, next);
                cleanupQrFiles(appContext, retained);
            } catch (Exception ignored) {
            }
        }
    }

    public static void updateUploadKeys(Context context, String url, String deleteKey, String downloadKey) {
        if (context == null || !GigaFilePolicy.isAllowedDownload(url)) {
            return;
        }
        String safeDeleteKey = cleanDeleteKey(deleteKey);
        String safeDownloadKey = cleanDownloadKey(downloadKey);
        if (safeDeleteKey == null && safeDownloadKey == null) {
            return;
        }
        synchronized (LOCK) {
            JSONArray array = read(context, UPLOADS);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null || !url.equals(item.optString("url", ""))) {
                    continue;
                }
                try {
                    boolean changed = false;
                    if (safeDeleteKey != null && !safeDeleteKey.equals(item.optString("delkey", ""))) {
                        item.put("delkey", safeDeleteKey);
                        changed = true;
                    }
                    if (safeDownloadKey != null && !safeDownloadKey.equals(item.optString("dlkey", ""))) {
                        item.put("dlkey", safeDownloadKey);
                        changed = true;
                    }
                    if (changed) {
                        write(context, UPLOADS, array);
                    }
                } catch (Exception ignored) {
                }
                return;
            }
        }
    }

    public static String getValidShortUrl(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        return GigaFilePolicy.isValidXgfShortUrl(result) ? result : null;
    }

    public static String getUploadShortUrl(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            JSONArray array = read(context, UPLOADS);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null && url.equals(item.optString("url", ""))) {
                    return getValidShortUrl(item.optString("shortUrl", ""));
                }
            }
        }
        return null;
    }

    public static boolean saveUploadShortUrl(Context context, String url, String shortUrl) {
        if (context == null || url == null || url.isEmpty()
                || !GigaFilePolicy.isAllowedDownload(url)
                || !GigaFilePolicy.isNumericUploadServerHost(GigaFilePolicy.hostOf(url))) {
            return false;
        }
        String safeShortUrl = getValidShortUrl(shortUrl);
        if (safeShortUrl == null) {
            return false;
        }
        Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            JSONArray array = read(appContext, UPLOADS);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null || !url.equals(item.optString("url", ""))) {
                    continue;
                }
                String previous = getValidShortUrl(item.optString("shortUrl", ""));
                if (safeShortUrl.equals(previous)) {
                    return true;
                }
                try {
                    item.put("shortUrl", safeShortUrl);
                    write(appContext, UPLOADS, array);
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    public static void addDownload(Context context, long id, String fileName, String url,
                                   String mimeType, long sizeBytes) {
        if (context == null || id <= 0L || !GigaFilePolicy.isAllowedDownload(url)) {
            return;
        }
        synchronized (LOCK) {
            JSONArray old = read(context, DOWNLOADS);
            JSONArray next = new JSONArray();
            JSONObject entry = new JSONObject();
            try {
                entry.put("id", id);
                entry.put("name", cleanName(fileName));
                entry.put("url", url);
                entry.put("mime", mimeType == null ? "application/octet-stream" : mimeType);
                entry.put("time", System.currentTimeMillis());
                entry.put("size", Math.max(0L, sizeBytes));
                entry.put("bytes", 0L);
                entry.put("status", DownloadManagerController.STATUS_QUEUED);
                entry.put("localUri", "");
                entry.put("downloadManagerId", -1L);
                next.put(entry);
                for (int i = 0; i < old.length() && next.length() < MAX_ITEMS; i++) {
                    JSONObject item = old.optJSONObject(i);
                    if (item == null || item.optLong("id", -1L) == id) {
                        continue;
                    }
                    next.put(item);
                }
                write(context, DOWNLOADS, next);
            } catch (Exception ignored) {
            }
        }
    }

    public static void setDownloadManagerId(Context context, long id, long downloadManagerId) {
        if (context == null || id <= 0L || downloadManagerId <= 0L) {
            return;
        }
        synchronized (LOCK) {
            JSONArray array = read(context, DOWNLOADS);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null || item.optLong("id", -1L) != id) {
                    continue;
                }
                try {
                    item.put("downloadManagerId", downloadManagerId);
                    write(context, DOWNLOADS, array);
                } catch (Exception ignored) {
                }
                return;
            }
        }
    }

    public static void updateDownload(Context context, long id, int status, long bytes,
                                      long total, String localUri, String finalName, String message) {
        if (context == null || id <= 0L) {
            return;
        }
        synchronized (LOCK) {
            JSONArray array = read(context, DOWNLOADS);
            boolean changed = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null || item.optLong("id", -1L) != id) {
                    continue;
                }
                try {
                    item.put("status", status);
                    item.put("bytes", Math.max(0L, bytes));
                    item.put("size", Math.max(0L, total));
                    if (localUri != null && !localUri.isEmpty()) {
                        item.put("localUri", localUri);
                    }
                    if (finalName != null && !finalName.isEmpty()) {
                        item.put("name", cleanName(finalName));
                    }
                    if (message != null) {
                        if (message.isEmpty()) {
                            item.remove("message");
                        } else {
                            item.put("message", message);
                        }
                    }
                    changed = true;
                } catch (Exception ignored) {
                }
                break;
            }
            if (changed) {
                write(context, DOWNLOADS, array);
            }
        }
    }

    public static boolean markDownloadCompletionToastShown(Context context, long downloadManagerId,
                                                            long bytes, long total, String localUri) {
        if (context == null || downloadManagerId <= 0L) {
            return false;
        }
        synchronized (LOCK) {
            JSONArray array = read(context, DOWNLOADS);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null || item.optLong("downloadManagerId", -1L) != downloadManagerId) {
                    continue;
                }
                if (item.optBoolean("completionToastShown", false)) {
                    return false;
                }
                try {
                    long completedBytes = Math.max(0L, bytes);
                    long completedTotal = total > 0L ? total : completedBytes;
                    item.put("status", DownloadManagerController.STATUS_COMPLETE);
                    item.put("bytes", completedBytes);
                    item.put("size", completedTotal);
                    item.put("completionToastShown", true);
                    if (localUri != null && !localUri.isEmpty()) {
                        item.put("localUri", localUri);
                    }
                    item.remove("message");
                    write(context, DOWNLOADS, array);
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    public static void updateDownloads(Context context, Map<Long, DownloadUpdate> updates) {
        if (context == null || updates == null || updates.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            JSONArray array = read(context, DOWNLOADS);
            boolean changed = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                DownloadUpdate update = updates.get(item.optLong("id", -1L));
                if (update == null) {
                    continue;
                }
                try {
                    item.put("status", update.status);
                    item.put("bytes", Math.max(0L, update.bytes));
                    item.put("size", Math.max(0L, update.total));
                    if (update.localUri != null && !update.localUri.isEmpty()) {
                        item.put("localUri", update.localUri);
                    }
                    if (update.finalName != null && !update.finalName.isEmpty()) {
                        item.put("name", cleanName(update.finalName));
                    }
                    if (update.message == null || update.message.isEmpty()) {
                        item.remove("message");
                    } else {
                        item.put("message", update.message);
                    }
                    changed = true;
                } catch (Exception ignored) {
                }
            }
            if (changed) {
                write(context, DOWNLOADS, array);
            }
        }
    }

    public static final class DownloadUpdate {
        public final int status;
        public final long bytes;
        public final long total;
        public final String localUri;
        public final String finalName;
        public final String message;

        public DownloadUpdate(int status, long bytes, long total, String localUri,
                              String finalName, String message) {
            this.status = status;
            this.bytes = bytes;
            this.total = total;
            this.localUri = localUri;
            this.finalName = finalName;
            this.message = message;
        }
    }

    public static ArrayList<JSONObject> getUploads(Context context) {
        return toList(read(context, UPLOADS));
    }

    public static ArrayList<JSONObject> getDownloads(Context context) {
        return toList(read(context, DOWNLOADS));
    }

    public static File getQrFile(Context context, String fileName) {
        String safeName = validQrName(fileName);
        if (context == null || safeName == null) {
            return null;
        }
        File file = new File(qrDirectory(context), safeName);
        return file.isFile() && file.length() > 0L ? file : null;
    }

    public static void removeUpload(Context context, String url) {
        remove(context, UPLOADS, "url", url);
    }

    public static void removeDownload(Context context, long id) {
        remove(context, DOWNLOADS, "id", id);
    }

    public static void clearDownloads(Context context) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            write(context, DOWNLOADS, new JSONArray());
        }
    }

    public static void clearUploads(Context context) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            write(context, UPLOADS, new JSONArray());
            cleanupQrFiles(context, new HashSet<>());
        }
    }

    private static void remove(Context context, String key, String field, Object value) {
        if (context == null || value == null) {
            return;
        }
        synchronized (LOCK) {
            JSONArray old = read(context, key);
            JSONArray next = new JSONArray();
            boolean changed = false;
            Set<String> retained = new HashSet<>();
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                boolean match;
                if (value instanceof Long) {
                    match = item.optLong(field, Long.MIN_VALUE) == (Long) value;
                } else {
                    match = ((String) value).equals(item.optString(field, ""));
                }
                if (match) {
                    changed = true;
                } else {
                    next.put(item);
                    if (UPLOADS.equals(key)) {
                        String qr = validQrName(item.optString("qr", ""));
                        if (qr != null) {
                            retained.add(qr);
                        }
                    }
                }
            }
            if (changed) {
                write(context, key, next);
                if (UPLOADS.equals(key)) {
                    cleanupQrFiles(context, retained);
                }
            }
        }
    }

    private static String saveQrImage(Context context, String url, String dataUrl) {
        if (dataUrl == null || dataUrl.length() < 32 || dataUrl.length() > MAX_QR_BYTES * 2) {
            return null;
        }
        String prefix = "data:image/png;base64,";
        if (!dataUrl.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.decode(dataUrl.substring(prefix.length()), Base64.NO_WRAP);
        } catch (IllegalArgumentException error) {
            return null;
        }
        if (bytes.length < 24 || bytes.length > MAX_QR_BYTES
                || bytes[0] != (byte) 0x89 || bytes[1] != 0x50 || bytes[2] != 0x4e
                || bytes[3] != 0x47 || bytes[4] != 0x0d || bytes[5] != 0x0a
                || bytes[6] != 0x1a || bytes[7] != 0x0a) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth < 24 || options.outHeight < 24
                || options.outWidth > 1024 || options.outHeight > 1024
                || Math.abs(options.outWidth - options.outHeight) > 8) {
            return null;
        }
        String fileName = qrFileName(url);
        if (fileName == null) {
            return null;
        }
        File directory = qrDirectory(context);
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }
        File destination = new File(directory, fileName);
        File temporary = new File(directory, fileName + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        } catch (Exception error) {
            temporary.delete();
            return null;
        }
        try {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) {
            temporary.delete();
            return null;
        }
        return fileName;
    }

    private static String qrFileName(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(67);
            result.append("qr_");
            for (byte value : digest) {
                result.append(Character.forDigit((value >> 4) & 0xf, 16));
                result.append(Character.forDigit(value & 0xf, 16));
            }
            result.append(".png");
            return result.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String validQrName(String value) {
        if (value == null || value.length() != 71 || !value.startsWith("qr_") || !value.endsWith(".png")) {
            return null;
        }
        for (int i = 3; i < 67; i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return null;
            }
        }
        return value;
    }

    private static File qrDirectory(Context context) {
        return new File(context.getFilesDir(), QR_DIRECTORY);
    }

    private static void cleanupQrFiles(Context context, Set<String> retained) {
        File directory = qrDirectory(context);
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".tmp") || (validQrName(name) != null && !retained.contains(name))) {
                file.delete();
            }
        }
    }

    private static JSONArray read(Context context, String key) {
        if (context == null) {
            return new JSONArray();
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new JSONArray(prefs.getString(key, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static void write(Context context, String key, JSONArray value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(key, value.toString())
                .apply();
    }

    private static ArrayList<JSONObject> toList(JSONArray array) {
        ArrayList<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                list.add(item);
            }
        }
        return list;
    }


    private static String cleanDeleteKey(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        if (result.isEmpty() || result.length() > 128) {
            return null;
        }
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (c <= 32 || c >= 127) {
                return null;
            }
        }
        return result;
    }

    public static String getValidDownloadKey(String value) {
        return cleanDownloadKey(value);
    }

    private static String cleanDownloadKey(String value) {
        if (value == null || !value.matches("[0-9A-Za-z]{1,4}")) {
            return null;
        }
        return value;
    }

    private static String cleanName(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "ファイル";
        }
        String result = value.replace('\\', '_').replace('/', '_').replace((char) 0, '_').trim();
        StringBuilder clean = new StringBuilder(result.length());
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            clean.append(c < 32 || c == 127 ? '_' : c);
        }
        result = clean.toString().trim();
        if (result.length() > 240) {
            result = result.substring(0, 240);
        }
        return result.isEmpty() ? "ファイル" : result;
    }
}
