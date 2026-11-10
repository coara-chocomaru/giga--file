package com.gigafile;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.widget.Toast;

public final class DownloadCompletionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            return;
        }
        long downloadManagerId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
        if (downloadManagerId <= 0L) {
            return;
        }
        Context appContext = context.getApplicationContext();
        DownloadManager manager = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            return;
        }
        Cursor cursor = null;
        long bytes = 0L;
        long total = 0L;
        String localUri = null;
        boolean successful = false;
        try {
            cursor = manager.query(new DownloadManager.Query().setFilterById(downloadManagerId));
            if (cursor == null || !cursor.moveToFirst()) {
                return;
            }
            int statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int bytesColumn = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
            int totalColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
            int localUriColumn = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            if (statusColumn < 0) {
                return;
            }
            successful = cursor.getInt(statusColumn) == DownloadManager.STATUS_SUCCESSFUL;
            if (!successful) {
                return;
            }
            if (bytesColumn >= 0) {
                bytes = Math.max(0L, cursor.getLong(bytesColumn));
            }
            if (totalColumn >= 0) {
                total = cursor.getLong(totalColumn);
            }
            if (localUriColumn >= 0 && !cursor.isNull(localUriColumn)) {
                localUri = cursor.getString(localUriColumn);
            }
        } catch (Exception ignored) {
            return;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (successful && HistoryStore.markDownloadCompletionToastShown(
                appContext, downloadManagerId, bytes, total, localUri)) {
            Toast.makeText(appContext, "ダウンロードが完了しました", Toast.LENGTH_SHORT).show();
        }
    }
}
