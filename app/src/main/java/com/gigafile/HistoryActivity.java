package com.gigafile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HistoryActivity extends Activity {
    public static final String EXTRA_TYPE = "history_type";
    public static final int TYPE_UPLOAD = 1;
    public static final int TYPE_DOWNLOAD = 2;
    private static final int REQUEST_QR_PERMISSION = 7301;
    private static final ExecutorService QR_RENDER_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "GigaFile-QRPreview");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private int type;
    private LinearLayout list;
    private TextView title;
    private File pendingQrFile;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        type = getIntent().getIntExtra(EXTRA_TYPE, TYPE_UPLOAD);
        buildLayout();
    }

    @Override
    protected void onDestroy() {
        ShortUrlService.cancel(this);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        if (type == TYPE_DOWNLOAD) {
            DownloadManagerController.refreshHistoryAsync(this, () -> {
                if (!isFinishing() && !isDestroyed() && type == TYPE_DOWNLOAD) {
                    render();
                }
            });
        }
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 247, 250));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), 0, dp(10), 0);
        header.setBackgroundColor(Color.WHITE);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(32);
        back.setTextColor(Color.rgb(35, 79, 145));
        back.setGravity(Gravity.CENTER);
        header.addView(back, new LinearLayout.LayoutParams(dp(42), -1));
        back.setOnClickListener(v -> finish());

        title = new TextView(this);
        title.setText(type == TYPE_UPLOAD ? "アップロード履歴" : "ダウンロード履歴");
        title.setTextColor(Color.rgb(28, 35, 48));
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));

        TextView clear = new TextView(this);
        clear.setText("全件削除");
        clear.setTextSize(13);
        clear.setTextColor(Color.rgb(175, 55, 55));
        clear.setGravity(Gravity.CENTER);
        header.addView(clear, new LinearLayout.LayoutParams(dp(66), -1));
        clear.setOnClickListener(v -> confirmClear());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(12), dp(12), dp(24));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void render() {
        if (list == null) {
            return;
        }
        list.removeAllViews();
        ArrayList<JSONObject> entries = type == TYPE_UPLOAD
                ? HistoryStore.getUploads(this)
                : HistoryStore.getDownloads(this);
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("履歴はありません");
            empty.setTextColor(Color.rgb(111, 119, 132));
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(72), 0, dp(72));
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (JSONObject entry : entries) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(10);
            list.addView(createCard(entry), params);
        }
    }

    private View createCard(JSONObject entry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(12));
        card.setBackground(background(Color.WHITE, dp(12), Color.rgb(230, 233, 239)));
        card.setElevation(dp(1));

        String name = entry.optString("name", "ファイル");
        String url = entry.optString("url", "");
        long time = entry.optLong("time", 0L);
        long size = entry.optLong("size", 0L);
        long bytes = entry.optLong("bytes", 0L);

        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextColor(Color.rgb(28, 35, 48));
        nameView.setTextSize(16);
        nameView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        nameView.setMaxLines(3);
        card.addView(nameView, new LinearLayout.LayoutParams(-1, -2));

        if (type == TYPE_UPLOAD) {
            addDetail(card, "共有URL", url, true);
            addUploadExpiry(card, url, time, entry.optString("expiryDate", ""));
            addShortUrlCopyAction(card, entry, name, url);
            addKeyRow(card, "削除KEY", entry.optString("delkey", ""));
            addKeyRow(card, "ダウンロードKEY", HistoryStore.getValidDownloadKey(entry.optString("dlkey", "")));
            addQrBlock(card, entry);
        } else {
            addDetail(card, "ダウンロード元", url, true);
            int status = entry.optInt("status", DownloadManagerController.STATUS_QUEUED);
            String statusText;
            if (status == DownloadManagerController.STATUS_COMPLETE) {
                statusText = "完了";
            } else if (status == DownloadManagerController.STATUS_RUNNING) {
                statusText = "ダウンロード中";
            } else if (status == DownloadManagerController.STATUS_FAILED) {
                statusText = entry.optString("message", "失敗");
            } else {
                statusText = entry.optString("message", "待機中");
            }
            addDetail(card, "状態", statusText, false);
            if (status == DownloadManagerController.STATUS_RUNNING && size > 0L) {
                addDetail(card, "進捗", formatBytes(bytes) + " / " + formatBytes(size), false);
            }
        }

        if (size > 0L) {
            addDetail(card, "サイズ", formatBytes(size), false);
        }
        addDetail(card, "日時", formatDate(time), false);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, dp(42));
        actionParams.topMargin = dp(4);
        card.addView(actions, actionParams);

        TextView copy = actionText(type == TYPE_UPLOAD ? "URLをコピー" : "元URLをコピー", Color.rgb(35, 79, 145));
        actions.addView(copy, new LinearLayout.LayoutParams(-2, -1));
        copy.setOnClickListener(v -> copyUrl(url));

        if (type == TYPE_UPLOAD) {
            TextView open = actionText("ページを開く", Color.rgb(36, 126, 84));
            LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(-2, -1);
            openParams.leftMargin = dp(8);
            actions.addView(open, openParams);
            open.setOnClickListener(v -> openUploadPage(url));
        }

        TextView delete = actionText("削除", Color.rgb(175, 55, 55));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(-2, -1);
        deleteParams.leftMargin = dp(18);
        actions.addView(delete, deleteParams);
        delete.setOnClickListener(v -> confirmDelete(entry));
        return card;
    }


    private void addUploadExpiry(LinearLayout card, String url, long uploadTime, String storedDate) {
        java.time.LocalDate expiry = GigaFileExpiry.fromStoredOrResolve(url, uploadTime, storedDate);
        String value = expiry == null ? "確認できません" : GigaFileExpiry.format(expiry);
        boolean expired = GigaFileExpiry.isExpired(expiry);
        TextView view = new TextView(this);
        view.setText("ダウンロード期限  " + value + (expired ? "（期限切れ）" : ""));
        view.setTextColor(expired ? Color.rgb(160, 91, 91) : Color.rgb(125, 132, 144));
        view.setTextSize(11);
        view.setPadding(0, dp(2), 0, 0);
        view.setMaxLines(1);
        view.setContentDescription("ダウンロード有効期限 " + value);
        card.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addShortUrlCopyAction(LinearLayout card, JSONObject entry, String name, String url) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(36));
        rowParams.topMargin = dp(3);
        card.addView(row, rowParams);

        TextView button = actionText("短縮URLコピー", Color.rgb(35, 79, 145));
        button.setTextSize(12);
        button.setPadding(dp(11), 0, dp(11), 0);
        button.setBackground(background(Color.rgb(239, 245, 253), dp(7), Color.rgb(218, 229, 245)));
        button.setContentDescription("このアップロードの短縮URLを生成してコピー");
        String cachedShortUrl = HistoryStore.getValidShortUrl(entry.optString("shortUrl", ""));
        button.setTag(cachedShortUrl);
        row.addView(button, new LinearLayout.LayoutParams(-2, dp(32)));
        button.setOnClickListener(v -> {
            Object cachedValue = button.getTag();
            String cached = cachedValue instanceof String ? (String) cachedValue : null;
            if (cached != null) {
                copyText("短縮URL", cached);
                return;
            }
            button.setEnabled(false);
            button.setText("生成中…");
            ShortUrlService.generate(this, url, name, new ShortUrlService.Callback() {
                @Override
                public void onSuccess(String shortUrl, boolean saved) {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    button.setEnabled(true);
                    button.setText("短縮URLコピー");
                    button.setTag(shortUrl);
                    copyText("短縮URL", shortUrl);
                    if (!saved) {
                        Toast.makeText(HistoryActivity.this,
                                "短縮URLは生成しましたが履歴へ保存できませんでした",
                                Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onFailure(String reason) {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    button.setEnabled(true);
                    button.setText("短縮URLコピー");
                    String message = reason == null || reason.trim().isEmpty()
                            ? "短縮URLを生成できませんでした"
                            : "短縮URLを生成できませんでした: " + reason;
                    Toast.makeText(HistoryActivity.this, message, Toast.LENGTH_LONG).show();
                }

                @Override
                public void onAlreadyRunning() {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    button.setEnabled(true);
                    button.setText("短縮URLコピー");
                    Toast.makeText(HistoryActivity.this,
                            "この短縮URLは生成中です", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void addQrBlock(LinearLayout card, JSONObject entry) {
        File qrFile = HistoryStore.getQrFile(this, entry.optString("qr", ""));
        if (qrFile == null) {
            return;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dp(10);
        rowParams.bottomMargin = dp(2);
        card.addView(row, rowParams);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(4), dp(4), dp(4), dp(4));
        image.setBackground(background(Color.WHITE, dp(8), Color.rgb(225, 230, 238)));
        image.setContentDescription("共有QRコード。タップで拡大、長押しで保存");
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(88), dp(88));
        row.addView(image, imageParams);
        image.setOnClickListener(v -> showQrPreview(qrFile));
        image.setOnLongClickListener(v -> {
            saveQrImage(qrFile);
            return true;
        });
        String qrKey = qrFile.getAbsolutePath();
        image.setTag(qrKey);
        int thumbnailSize = Math.min(dp(88), 100);
        QR_RENDER_EXECUTOR.execute(() -> {
            Bitmap thumbnail = decodeQr(qrFile, thumbnailSize);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !qrKey.equals(image.getTag())) {
                    if (thumbnail != null && !thumbnail.isRecycled()) {
                        thumbnail.recycle();
                    }
                    return;
                }
                if (thumbnail == null) {
                    row.setVisibility(View.GONE);
                } else {
                    image.setImageBitmap(thumbnail);
                }
            });
        });

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, -2, 1f);
        infoParams.leftMargin = dp(12);
        row.addView(info, infoParams);

        TextView label = new TextView(this);
        label.setText("共有QRコード");
        label.setTextColor(Color.rgb(45, 54, 68));
        label.setTextSize(13);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        info.addView(label, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText("タップで拡大表示できます");
        hint.setTextColor(Color.rgb(116, 124, 137));
        hint.setTextSize(11);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(3);
        info.addView(hint, hintParams);

        TextView save = actionText("QR画像を保存", Color.rgb(35, 79, 145));
        save.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-2, dp(38));
        saveParams.topMargin = dp(3);
        info.addView(save, saveParams);
        save.setOnClickListener(v -> saveQrImage(qrFile));
    }

    private void showQrPreview(File file) {
        Bitmap bitmap = decodeQr(file, dp(280));
        if (bitmap == null) {
            Toast.makeText(this, "QR画像を読み込めませんでした", Toast.LENGTH_SHORT).show();
            return;
        }
        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(true);
        image.setPadding(dp(8), dp(8), dp(8), dp(8));
        image.setLayoutParams(new LinearLayout.LayoutParams(dp(280), dp(280)));
        new AlertDialog.Builder(this)
                .setTitle("共有QRコード")
                .setView(image)
                .setPositiveButton("閉じる", null)
                .setNeutralButton("画像を保存", (dialog, which) -> saveQrImage(file))
                .show();
    }

    private Bitmap decodeQr(File file, int targetSize) {
        if (file == null || !file.isFile()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || bounds.outWidth > 1024 || bounds.outHeight > 1024) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        options.inSampleSize = 1;
        int required = Math.max(1, targetSize);
        while (bounds.outWidth / (options.inSampleSize * 2) >= required
                && bounds.outHeight / (options.inSampleSize * 2) >= required) {
            options.inSampleSize *= 2;
        }
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError | RuntimeException error) {
            return null;
        }
    }

    private void saveQrImage(File source) {
        if (source == null || !source.isFile()) {
            Toast.makeText(this, "QR画像が見つかりません", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingQrFile = source;
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_QR_PERMISSION);
            return;
        }
        exportQrImage(source);
    }

    private void exportQrImage(File source) {
        String fileName = "GigaFile_QR_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.JAPAN)
                .format(new Date()) + ".png";
        Uri saved = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/GigaFile");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri destination = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (destination == null) {
                    throw new java.io.IOException("image destination unavailable");
                }
                try (InputStream input = new FileInputStream(source);
                     OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                    if (output == null) {
                        throw new java.io.IOException("image stream unavailable");
                    }
                    copy(input, output);
                } catch (Exception error) {
                    getContentResolver().delete(destination, null, null);
                    throw error;
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (getContentResolver().update(destination, done, null, null) <= 0) {
                    getContentResolver().delete(destination, null, null);
                    throw new java.io.IOException("image publication failed");
                }
                saved = destination;
            } else {
                File directory = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "GigaFile");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new java.io.IOException("image directory unavailable");
                }
                File destination = new File(directory, fileName);
                try (InputStream input = new FileInputStream(source);
                     OutputStream output = new FileOutputStream(destination)) {
                    copy(input, output);
                } catch (Exception error) {
                    destination.delete();
                    throw error;
                }
                saved = Uri.fromFile(destination);
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, saved));
            }
        } catch (Exception error) {
            Toast.makeText(this, "QR画像を保存できませんでした", Toast.LENGTH_SHORT).show();
            return;
        }
        if (saved != null) {
            Toast.makeText(this, "QR画像をPictures/GigaFileに保存しました", Toast.LENGTH_SHORT).show();
        }
    }

    private void copy(InputStream input, OutputStream output) throws java.io.IOException {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        output.flush();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_QR_PERMISSION) {
            File file = pendingQrFile;
            pendingQrFile = null;
            if (grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                exportQrImage(file);
            } else {
                Toast.makeText(this, "保存先へのアクセスが許可されませんでした", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void addKeyRow(LinearLayout card, String label, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(3), dp(5), dp(3));
        row.setBackground(background(Color.rgb(248, 250, 253), dp(7), Color.rgb(233, 237, 244)));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dp(6);
        card.addView(row, rowParams);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(Color.rgb(95, 104, 119));
        labelView.setTextSize(12);
        labelView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labelView.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(labelView, new LinearLayout.LayoutParams(dp(94), -2));

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(Color.rgb(38, 48, 64));
        valueView.setTextSize(13);
        valueView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        valueView.setMaxLines(2);
        valueView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        valueView.setGravity(Gravity.CENTER_VERTICAL);
        valueView.setTextIsSelectable(true);
        row.addView(valueView, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView copy = actionText("コピー", Color.rgb(35, 79, 145));
        copy.setPadding(dp(8), 0, dp(8), 0);
        copy.setMinWidth(dp(52));
        copy.setBackground(background(Color.rgb(235, 242, 252), dp(6), Color.rgb(218, 229, 245)));
        copy.setContentDescription(label + "をコピー");
        row.addView(copy, new LinearLayout.LayoutParams(-2, dp(40)));
        copy.setOnClickListener(v -> copyText(label, value));
    }

    private void addDetail(LinearLayout card, String label, String value, boolean selectable) {
        if (value == null || value.isEmpty()) {
            return;
        }
        TextView view = new TextView(this);
        view.setText(label + "  " + value);
        view.setTextColor(Color.rgb(95, 104, 119));
        view.setTextSize(12);
        view.setPadding(0, dp(7), 0, 0);
        view.setMaxLines(selectable ? 4 : 2);
        view.setTextIsSelectable(selectable);
        card.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView actionText(String value, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), 0, dp(8), 0);
        return view;
    }

    private void copyUrl(String value) {
        copyText("URL", value);
    }

    private void copyText(String label, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "コピーできませんでした", Toast.LENGTH_SHORT).show();
            return;
        }
        manager.setPrimaryClip(ClipData.newPlainText("GigaFile " + label, value));
        Toast.makeText(this, label + "をコピーしました", Toast.LENGTH_SHORT).show();
    }

    private void openUploadPage(String value) {
        if (!GigaFilePolicy.isAllowedExternalOpen(value)) {
            Toast.makeText(this, "このURLは開けません", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(value));
            intent.putExtra(MainActivity.EXTRA_RESET_BUNDLE_AFTER_URL, value);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        } catch (Exception error) {
            Toast.makeText(this, "ページを開けませんでした", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(JSONObject entry) {
        new AlertDialog.Builder(this)
                .setMessage("この履歴を削除しますか？")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("削除", (dialog, which) -> {
                    if (type == TYPE_UPLOAD) {
                        HistoryStore.removeUpload(this, entry.optString("url", ""));
                    } else {
                        long id = entry.optLong("id", -1L);
                        HistoryStore.removeDownload(this, id);
                    }
                    render();
                })
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage("履歴をすべて削除しますか？")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("全件削除", (dialog, which) -> {
                    if (type == TYPE_UPLOAD) {
                        HistoryStore.clearUploads(this);
                    } else {
                        HistoryStore.clearDownloads(this);
                    }
                    render();
                })
                .show();
    }

    private String formatDate(long value) {
        if (value <= 0L) {
            return "日時不明";
        }
        return new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(new Date(value));
    }

    private String formatBytes(long value) {
        if (value < 1024L) {
            return value + " B";
        }
        double number = value;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int index = 0;
        while (number >= 1024.0 && index < units.length - 1) {
            number /= 1024.0;
            index++;
        }
        return String.format(Locale.JAPAN, "%.2f %s", number, units[index]);
    }

    private GradientDrawable background(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int dp(float value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }
}
