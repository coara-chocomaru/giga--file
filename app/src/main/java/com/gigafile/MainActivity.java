package com.gigafile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;

import org.json.JSONTokener;

public final class MainActivity extends Activity {
    private static final int REQUEST_FILE_CHOOSER = 7201;
    private static final int REQUEST_DOWNLOAD_PERMISSION = 7202;
    private static final int REQUEST_SCREENSHOT_PERMISSION = 7203;
    private static final long EXIT_INTERVAL_MS = 2200L;
    private static final byte[] SHORTENER_TRANSPARENT_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0B, 0x49, 0x44, 0x41,
            0x54, 0x78, (byte) 0x9C, 0x63, 0x60, 0x00, 0x02, 0x00,
            0x00, 0x05, 0x00, 0x01, 0x7A, 0x5E, (byte) 0xAB, 0x3F,
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            (byte) 0xAE, 0x42, 0x60, (byte) 0x82
    };
    private static final String SESSION_PREFS = "gigafile_tab_session";
    private static final String SESSION_ACTIVE = "tab_active";
    public static final String EXTRA_RESET_BUNDLE_AFTER_URL = "com.gigafile.extra.RESET_BUNDLE_AFTER_URL";
    private static volatile boolean processSessionInitialized;
    private final String uploadBridgeNonce = UUID.randomUUID().toString();
    private FrameLayout webContainer;
    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> fileChooserCallback;
    private PendingDownload pendingDownload;
    private long lastBackPressed;
    private volatile boolean destroyed;
    private String resetBundleAfterUrl;
    private boolean resetBundleTargetSeen;
    private boolean shortenerPageVisited;
    private boolean resetBundleAfterShortener;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        resetBundleAfterUrl = historyUploadPageKey(getIntent() == null ? null
                : getIntent().getStringExtra(EXTRA_RESET_BUNDLE_AFTER_URL));
        resetBundleTargetSeen = false;
        boolean firstActivityInProcess = !processSessionInitialized;
        boolean previousTabWasActive = false;
        if (firstActivityInProcess) {
            android.content.SharedPreferences session = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE);
            previousTabWasActive = session.getBoolean(SESSION_ACTIVE, false);
            session.edit().putBoolean(SESSION_ACTIVE, true).commit();
            processSessionInitialized = true;
        }
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(248, 249, 252));
        window.setNavigationBarColor(Color.rgb(248, 249, 252));
        View decor = window.getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildLayout();
        webView = createWebView();
        if (firstActivityInProcess && previousTabWasActive) {
            webView.clearHistory();
            webView.clearCache(true);
        }
        webContainer.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String initial = externalUrl(getIntent());
        loadAllowedUrl(initial == null ? GigaFilePolicy.HOME_URL : initial, false);
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), 0, dp(8), 0);
        toolbar.setBackgroundColor(Color.rgb(255, 255, 255));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(54)));

        ImageView icon = new ImageView(this);
        icon.setImageResource(com.gigafile.R.drawable.ic_gigafile);
        toolbar.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));

        TextView heading = new TextView(this);
        heading.setText("GigaFile");
        heading.setTextColor(Color.rgb(35, 54, 82));
        heading.setTextSize(17);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(dp(10), 0, 0, 0);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, -1, 1f));

        TextView menu = new TextView(this);
        menu.setText("⋮");
        menu.setTextSize(28);
        menu.setTextColor(Color.rgb(50, 59, 73));
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("メニュー");
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(44), -1));
        menu.setOnClickListener(this::showMenu);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(35, 91, 165)));
        progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(232, 237, 245)));
        root.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(2)));

        webContainer = new FrameLayout(this);
        webContainer.setBackgroundColor(Color.WHITE);
        root.addView(webContainer, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private WebView createWebView() {
        WebView view = new WebView(this);
        view.setBackgroundColor(Color.WHITE);
        view.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        view.setVerticalScrollBarEnabled(true);
        view.setHorizontalScrollBarEnabled(false);
        view.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setGeolocationEnabled(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setTextZoom(100);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setNeedInitialFocus(false);
        settings.setSafeBrowsingEnabled(true);
        settings.setOffscreenPreRaster(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(view, false);
        UploadBridge uploadBridge = new UploadBridge(this, uploadBridgeNonce);
        boolean messageListenerInstalled = false;
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(view, "GigaFileNative",
                        Collections.singleton("https://gigafile.nu"),
                        (sourceView, message, sourceOrigin, isMainFrame, replyProxy) -> {
                            if (sourceOrigin != null) {
                                uploadBridge.recordMessage(message == null ? null : message.getData(),
                                        sourceOrigin.toString(), isMainFrame);
                            }
                        });
                messageListenerInstalled = true;
            }
        } catch (RuntimeException ignored) {
        }
        if (!messageListenerInstalled) {
            view.addJavascriptInterface(uploadBridge, "GigaFileNative");
        }

        view.setWebViewClient(new GigaWebViewClient());
        view.setWebChromeClient(new GigaChromeClient());
        view.setDownloadListener(new GigaDownloadListener());
        return view;
    }

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 4, 0, "ホームに戻る");
        popup.getMenu().add(0, 1, 1, "アップロード履歴");
        popup.getMenu().add(0, 2, 2, "ダウンロード履歴");
        popup.getMenu().add(0, 3, 3, "スクリーンショット");
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 4) {
                loadAllowedUrl(GigaFilePolicy.HOME_URL, false);
                return true;
            }
            if (id == 1) {
                openHistory(HistoryActivity.TYPE_UPLOAD);
                return true;
            }
            if (id == 2) {
                openHistory(HistoryActivity.TYPE_DOWNLOAD);
                return true;
            }
            if (id == 3) {
                requestScreenshot();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void openHistory(int type) {
        Intent intent = new Intent(this, HistoryActivity.class);
        intent.putExtra(HistoryActivity.EXTRA_TYPE, type);
        startActivity(intent);
    }

    private static String historyUploadPageKey(String value) {
        if (value == null || !GigaFilePolicy.isAllowedExternalOpen(value)) {
            return null;
        }
        Uri uri = Uri.parse(value);
        String host = uri.getHost();
        String path = uri.getEncodedPath();
        if (!GigaFilePolicy.isNumericUploadServerHost(host)
                || path == null || path.isEmpty() || "/".equals(path)) {
            return null;
        }
        String query = uri.getEncodedQuery();
        return host.toLowerCase(Locale.ROOT) + path + (query == null ? "" : "?" + query);
    }

    private String externalUrl(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return null;
        }
        Uri data = intent.getData();
        if (data == null) {
            return null;
        }
        String value = data.toString();
        return GigaFilePolicy.isAllowedExternalOpen(value) ? value : null;
    }

    private void loadAllowedUrl(String url, boolean showWarning) {
        if (webView == null || destroyed) {
            return;
        }
        if (!GigaFilePolicy.isAllowedNavigation(url)) {
            if (showWarning) {
                Toast.makeText(this, "許可されていないページへの移動を遮断しました", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        lastBackPressed = 0L;
        webView.loadUrl(url);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        resetBundleAfterUrl = historyUploadPageKey(intent == null ? null
                : intent.getStringExtra(EXTRA_RESET_BUNDLE_AFTER_URL));
        resetBundleTargetSeen = false;
        String url = externalUrl(intent);
        if (url != null) {
            loadAllowedUrl(url, true);
        } else if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Toast.makeText(this, "このURLはGigaFileアプリでは開けません", Toast.LENGTH_SHORT).show();
        }
    }

    private void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType) {
        if (!GigaFilePolicy.isAllowedFileTransfer(url)) {
            Toast.makeText(this, "許可されていないダウンロード先です", Toast.LENGTH_SHORT).show();
            return;
        }
        String referer = webView == null ? GigaFilePolicy.HOME_URL : webView.getUrl();
        if (webView != null && GigaFilePolicy.isTrustedGigaFilePage(referer)) {
            webView.evaluateJavascript(
                    "(function(){var e=document.getElementById('dl');if(!e)return '';return String(e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim();})()",
                    value -> {
                        String pageName = decodeJavascriptString(value);
                        if (!isUsableDownloadName(pageName)) {
                            pageName = "";
                        }
                        prepareDownload(new PendingDownload(url, pageName, userAgent, referer,
                                mimeType, contentDisposition));
                    });
        } else {
            prepareDownload(new PendingDownload(url, "", userAgent, referer, mimeType, contentDisposition));
        }
    }

    private static String decodeJavascriptString(String value) {
        if (value == null || value.isEmpty() || "null".equals(value)) {
            return "";
        }
        try {
            Object decoded = new JSONTokener(value).nextValue();
            return decoded instanceof String ? ((String) decoded).trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isUsableDownloadName(String value) {
        if (value == null) {
            return false;
        }
        String name = value.trim();
        if (name.isEmpty() || name.length() > 240) {
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
                && !"ファイル".equals(name)
                && !name.contains("ファイル名表示中");
    }

    private void prepareDownload(PendingDownload request) {
        if (destroyed || request == null) {
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = request;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_DOWNLOAD_PERMISSION);
            return;
        }
        enqueueDownload(request);
    }

    private void enqueueDownload(PendingDownload request) {
        long id = DownloadManagerController.nextId();
        String initialName = isUsableDownloadName(request.name) ? request.name : "ダウンロード";
        HistoryStore.addDownload(this, id, initialName, request.url, request.mimeType, -1L);
        try {
            DownloadManagerController.DownloadRequest queued = DownloadManagerController.startDownload(this, id,
                    request.url, request.name, request.contentDisposition, request.userAgent,
                    request.referer, request.mimeType);
            HistoryStore.setDownloadManagerId(this, id, queued.downloadManagerId);
            HistoryStore.updateDownload(this, id, DownloadManagerController.STATUS_QUEUED, 0L, 0L,
                    null, queued.fileName, "");
            Toast.makeText(this, "ダウンロードを開始しました", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            HistoryStore.updateDownload(this, id, DownloadManagerController.STATUS_FAILED, 0L, 0L,
                    null, isUsableDownloadName(request.name) ? request.name : "ダウンロード", "ダウンロードを登録できませんでした");
            Toast.makeText(this, "ダウンロードを開始出来ませんでした…", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestScreenshot() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_SCREENSHOT_PERMISSION);
            return;
        }
        captureScreenshot();
    }

    private void captureScreenshot() {
        WebView target = webView;
        if (target == null || target.getWidth() <= 0 || target.getHeight() <= 0) {
            Toast.makeText(this, "ページの表示が完了してから実行してください", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(target.getWidth(), target.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            target.draw(canvas);
            String fileName = "GigaFile_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(new Date()) + ".png";
            Uri saved = saveScreenshot(bitmap, fileName);
            if (saved != null) {
                Toast.makeText(this, "スクリーンショットを保存しました", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "保存できませんでした", Toast.LENGTH_SHORT).show();
            }
        } catch (OutOfMemoryError error) {
            Toast.makeText(this, "メモリ不足で保存できませんでした", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "保存できませんでした", Toast.LENGTH_SHORT).show();
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private Uri saveScreenshot(Bitmap bitmap, String fileName) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GigaFile");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) {
                    throw new java.io.IOException("image stream unavailable");
                }
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new java.io.IOException("image compression failed");
                }
                output.flush();
            } catch (Exception error) {
                getContentResolver().delete(uri, null, null);
                throw error;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (getContentResolver().update(uri, done, null, null) <= 0) {
                getContentResolver().delete(uri, null, null);
                return null;
            }
            return uri;
        }
        File directory;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GigaFile");
        } else {
            File base = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            directory = new File(base == null ? getFilesDir() : base, "GigaFile");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new java.io.IOException("image directory unavailable");
        }
        File file = new File(directory, fileName);
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new java.io.IOException("image compression failed");
            }
            output.flush();
        }
        try {
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)));
        } catch (Exception ignored) {
        }
        return Uri.fromFile(file);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE_CHOOSER) {
            ValueCallback<Uri[]> callback = fileChooserCallback;
            fileChooserCallback = null;
            if (callback == null) {
                return;
            }
            Uri[] result = null;
            if (resultCode == RESULT_OK) {
                try {
                    Uri[] selected = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                    if (selected != null && selected.length > 0) {
                        ArrayList<Uri> safe = new ArrayList<>(selected.length);
                        for (Uri uri : selected) {
                            if (uri != null && "content".equalsIgnoreCase(uri.getScheme())) {
                                safe.add(uri);
                            }
                        }
                        if (!safe.isEmpty()) {
                            result = safe.toArray(new Uri[0]);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            callback.onReceiveValue(result);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SCREENSHOT_PERMISSION) {
            if (grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                captureScreenshot();
            } else {
                Toast.makeText(this, "保存先へのアクセスが許可されませんでした", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_DOWNLOAD_PERMISSION && pendingDownload != null) {
            PendingDownload request = pendingDownload;
            pendingDownload = null;
            if (grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enqueueDownload(request);
            } else {
                Toast.makeText(this, "保存先へのアクセスが許可されませんでした", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            lastBackPressed = 0L;
            webView.goBack();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastBackPressed > 0L && now - lastBackPressed <= EXIT_INTERVAL_MS) {
            lastBackPressed = 0L;
            finish();
            return;
        }
        lastBackPressed = now;
        Toast.makeText(this, "もう一度押すと終了します", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (isFinishing()) {
            clearClosedTabSession(webView);
        }
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        destroyWebView(webView);
        webView = null;
        super.onDestroy();
    }

    private void clearClosedTabSession(WebView view) {
        if (view != null) {
            try {
                view.stopLoading();
                view.clearHistory();
                view.clearCache(true);
            } catch (Exception ignored) {
            }
        }
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(SESSION_ACTIVE, false)
                .commit();
        processSessionInitialized = false;
        lastBackPressed = 0L;
    }

    private void destroyWebView(WebView view) {
        if (view == null) {
            return;
        }
        try {
            view.stopLoading();
            view.removeJavascriptInterface("GigaFileNative");
            view.setDownloadListener(null);
            view.setWebChromeClient(null);
            view.setWebViewClient(new WebViewClient());
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) {
                parent.removeView(view);
            }
            view.destroy();
        } catch (Exception ignored) {
        }
    }

    private void recoverRenderer(WebView oldView) {
        if (destroyed || oldView == null || oldView != webView) {
            return;
        }
        String recoverUrl = oldView.getUrl();
        if (!GigaFilePolicy.isAllowedNavigation(recoverUrl)) {
            recoverUrl = GigaFilePolicy.HOME_URL;
        }
        destroyWebView(oldView);
        webView = createWebView();
        webContainer.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        loadAllowedUrl(recoverUrl, false);
    }

    private int dp(float value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }

    private final class GigaWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (request == null || request.getUrl() == null) {
                return true;
            }
            String url = request.getUrl().toString();
            if ("about:blank".equalsIgnoreCase(url)) {
                return false;
            }
            if (!GigaFilePolicy.isAllowedNavigation(url)) {
                if (request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this, "許可されていないページへの移動を遮断しました", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return false;
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url == null || "about:blank".equalsIgnoreCase(url)) {
                return false;
            }
            if (!GigaFilePolicy.isAllowedNavigation(url)) {
                Toast.makeText(MainActivity.this, "許可されていないページへの移動を遮断しました", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request == null || request.getUrl() == null) {
                return blockedResponse();
            }
            String url = request.getUrl().toString();
            WebResourceResponse placeholder = localShortenerPlaceholderResponse(url, request.getMethod());
            if (placeholder != null) {
                return placeholder;
            }
            WebResourceResponse local = localStaticResponse(url, request.getMethod());
            if (local != null) {
                return local;
            }
            if (GigaFilePolicy.isAllowedRequest(url, request.isForMainFrame(), request.getMethod())) {
                return null;
            }
            return blockedResponse();
        }

        @Override
        @SuppressWarnings("deprecation")
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            WebResourceResponse placeholder = localShortenerPlaceholderResponse(url, "GET");
            if (placeholder != null) {
                return placeholder;
            }
            WebResourceResponse local = localStaticResponse(url, "GET");
            if (local != null) {
                return local;
            }
            return GigaFilePolicy.isAllowedRequest(url, false) ? null : blockedResponse();
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (url != null && !"about:blank".equalsIgnoreCase(url)
                    && !GigaFilePolicy.isAllowedNavigation(url)) {
                view.stopLoading();
                Toast.makeText(MainActivity.this, "許可されていないページへの移動を遮断しました", Toast.LENGTH_SHORT).show();
                return;
            }
            if (view == webView) {
                if (GigaFilePolicy.isTrustedShortenerPage(url)) {
                    shortenerPageVisited = true;
                } else if (shortenerPageVisited && GigaFilePolicy.isTrustedUploadOrigin(url)) {
                    resetBundleAfterShortener = true;
                    shortenerPageVisited = false;
                }
                if (resetBundleAfterUrl != null
                        && resetBundleAfterUrl.equals(historyUploadPageKey(url))) {
                    resetBundleTargetSeen = true;
                }
            }
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(0);
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (view == webView && !destroyed
                    && GigaFilePolicy.isTrustedShortenerPage(url)
                    && GigaFilePolicy.isTrustedShortenerPage(view.getUrl())) {
                view.evaluateJavascript(ShortenerPageScript.get(), null);
            } else if (GigaFilePolicy.isTrustedGigaFilePage(url) && view == webView
                    && GigaFilePolicy.isTrustedGigaFilePage(view.getUrl())) {
                String nonce = org.json.JSONObject.quote(uploadBridgeNonce);
                boolean historyReset = resetBundleAfterUrl != null && resetBundleTargetSeen;
                boolean resetBundle = (historyReset || resetBundleAfterShortener)
                        && GigaFilePolicy.isTrustedUploadOrigin(view.getUrl());
                view.evaluateJavascript("window.__gigaNativeNonce=" + nonce + ";", value -> {
                    if (view != webView || destroyed
                            || !GigaFilePolicy.isTrustedGigaFilePage(view.getUrl())) {
                        return;
                    }
                    if (resetBundle && GigaFilePolicy.isTrustedUploadOrigin(view.getUrl())) {
                        view.evaluateJavascript(GigaFilePageScript.resetBundleInputs(), resetResult -> {
                            if (view == webView && !destroyed
                                    && GigaFilePolicy.isTrustedUploadOrigin(view.getUrl())
                                    && "true".equals(resetResult)) {
                                if (historyReset) {
                                    resetBundleAfterUrl = null;
                                    resetBundleTargetSeen = false;
                                }
                                if (resetBundleAfterShortener) {
                                    resetBundleAfterShortener = false;
                                }
                            }
                            if (view == webView && !destroyed
                                    && GigaFilePolicy.isTrustedGigaFilePage(view.getUrl())) {
                                view.evaluateJavascript(GigaFilePageScript.get(), null);
                            }
                        });
                    } else {
                        view.evaluateJavascript(GigaFilePageScript.get(), null);
                    }
                });
            }
            progressBar.setProgress(100);
            progressBar.setVisibility(View.GONE);
            view.requestLayout();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request != null && request.isForMainFrame()) {
                progressBar.setProgress(0);
                progressBar.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, "ページを読み込めませんでした", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
            handler.cancel();
        }

        @Override
        public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType, SafeBrowsingResponse response) {
            response.backToSafety(true);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
            recoverRenderer(view);
            return true;
        }
    }

    private WebResourceResponse localStaticResponse(String url, String method) {
        String assetPath = GigaFilePolicy.getLocalStaticAssetPath(url, method);
        if (assetPath == null) {
            return null;
        }
        InputStream input = null;
        try {
            input = getAssets().open(assetPath);
            String lower = assetPath.toLowerCase(Locale.ROOT);
            String mimeType;
            String encoding = null;
            if (lower.endsWith(".css")) {
                mimeType = "text/css";
                encoding = "UTF-8";
            } else if (lower.endsWith(".js")) {
                mimeType = "application/javascript";
                encoding = "UTF-8";
            } else if (lower.endsWith(".svg")) {
                mimeType = "image/svg+xml";
                encoding = "UTF-8";
            } else if (lower.endsWith(".png")) {
                mimeType = "image/png";
            } else if (lower.endsWith(".gif")) {
                mimeType = "image/gif";
            } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                mimeType = "image/jpeg";
            } else {
                input.close();
                return blockedResponse();
            }
            HashMap<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "public, max-age=31536000, immutable");
            headers.put("X-Content-Type-Options", "nosniff");
            headers.put("Access-Control-Allow-Origin", "*");
            return new WebResourceResponse(mimeType, encoding, 200, "OK", headers, input);
        } catch (IOException | RuntimeException error) {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
            return blockedResponse();
        }
    }

    private WebResourceResponse localShortenerPlaceholderResponse(String url, String method) {
        if (!GigaFilePolicy.isSuppressedShortenerImage(url, method)) {
            return null;
        }
        return new WebResourceResponse("image/png", null,
                new ByteArrayInputStream(SHORTENER_TRANSPARENT_PNG));
    }

    private WebResourceResponse blockedResponse() {
        java.util.HashMap<String, String> headers = new java.util.HashMap<>();
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked by GigaFile", headers,
                new java.io.ByteArrayInputStream(new byte[0]));
    }

    private final class GigaChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int progress) {
            progressBar.setProgress(progress);
            progressBar.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (callback == null) {
                return true;
            }
            if (params == null || !GigaFilePolicy.isTrustedUploadOrigin(webView.getUrl())) {
                callback.onReceiveValue(null);
                return true;
            }
            if (fileChooserCallback != null) {
                fileChooserCallback.onReceiveValue(null);
            }
            fileChooserCallback = callback;
            Intent chooser;
            try {
                chooser = params.createIntent();
            } catch (Exception error) {
                chooser = new Intent(Intent.ACTION_GET_CONTENT);
                chooser.setType("*/*");
                chooser.addCategory(Intent.CATEGORY_OPENABLE);
                chooser.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            }
            try {
                startActivityForResult(chooser, REQUEST_FILE_CHOOSER);
                return true;
            } catch (Exception error) {
                fileChooserCallback = null;
                callback.onReceiveValue(null);
                Toast.makeText(MainActivity.this, "ファイル選択を開けませんでした", Toast.LENGTH_SHORT).show();
                return true;
            }
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
            return false;
        }
    }

    private final class GigaDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            MainActivity.this.onDownloadStart(url, userAgent, contentDisposition, mimeType);
        }
    }

    public static final class UploadBridge {
        private final WeakReference<MainActivity> activityReference;
        private final String nonce;

        UploadBridge(MainActivity activity, String nonce) {
            activityReference = new WeakReference<>(activity);
            this.nonce = nonce;
        }

        public void recordMessage(String payload, String sourceOrigin, boolean isMainFrame) {
            if (!isMainFrame) {
                return;
            }
            runOnMainThread(() -> {
                try {
                    org.json.JSONObject message = new org.json.JSONObject(payload == null ? "{}" : payload);
                    String type = message.optString("type", "");
                    if ("recordUpload".equals(type)) {
                        saveUpload(message.optString("name", ""), message.optString("url", ""),
                                message.optLong("size", 0L), sourceOrigin, message.optString("qr", ""),
                                message.optString("delkey", ""), message.optString("dlkey", ""), true);
                    } else if ("updateUploadKeys".equals(type)) {
                        saveUploadKeys(message.optString("url", ""), message.optString("delkey", ""),
                                message.optString("dlkey", ""), sourceOrigin, true);
                    } else if ("removeUpload".equals(type)) {
                        saveUploadRemoval(message.optString("url", ""), sourceOrigin, true);
                    }
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void recordUpload(String fileName, String url, long sizeBytes, String origin,
                                 String qrDataUrl, String suppliedNonce) {
            boolean trusted = suppliedNonce != null && nonce.equals(suppliedNonce);
            runOnMainThread(() -> saveUpload(fileName, url, sizeBytes, origin, qrDataUrl, "", "", trusted));
        }

        @JavascriptInterface
        public void recordUploadWithKeys(String fileName, String url, long sizeBytes, String origin,
                                         String qrDataUrl, String deleteKey, String downloadKey,
                                         String suppliedNonce) {
            boolean trusted = suppliedNonce != null && nonce.equals(suppliedNonce);
            runOnMainThread(() -> saveUpload(fileName, url, sizeBytes, origin,
                    qrDataUrl, deleteKey, downloadKey, trusted));
        }

        @JavascriptInterface
        public void updateUploadKeys(String url, String deleteKey, String downloadKey,
                                     String origin, String suppliedNonce) {
            boolean trusted = suppliedNonce != null && nonce.equals(suppliedNonce);
            runOnMainThread(() -> saveUploadKeys(url, deleteKey, downloadKey, origin, trusted));
        }

        @JavascriptInterface
        public void removeUpload(String url, String origin, String suppliedNonce) {
            boolean trusted = suppliedNonce != null && nonce.equals(suppliedNonce);
            runOnMainThread(() -> saveUploadRemoval(url, origin, trusted));
        }

        private void runOnMainThread(Runnable action) {
            MainActivity activity = activityReference.get();
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(() -> {
                MainActivity current = activityReference.get();
                if (current != null && !current.destroyed) {
                    action.run();
                }
            });
        }

        private void saveUpload(String fileName, String url, long sizeBytes, String origin,
                                String qrDataUrl, String deleteKey, String downloadKey,
                                boolean trustedTransport) {
            MainActivity activity = activityReference.get();
            if (!trustedTransport || activity == null || activity.destroyed || activity.webView == null
                    || !GigaFilePolicy.isTrustedUploadOrigin(origin)
                    || !GigaFilePolicy.isTrustedUploadOrigin(activity.webView.getUrl())
                    || !GigaFilePolicy.isAllowedDownload(url)
                    || !GigaFilePolicy.isNumericUploadServerHost(GigaFilePolicy.hostOf(url))) {
                return;
            }
            HistoryStore.addUploadAsync(activity.getApplicationContext(), fileName, url,
                    Math.max(0L, sizeBytes), qrDataUrl, deleteKey, downloadKey);
        }

        private void saveUploadKeys(String url, String deleteKey, String downloadKey,
                                    String origin, boolean trustedTransport) {
            MainActivity activity = activityReference.get();
            if (!trustedTransport || activity == null || activity.destroyed || activity.webView == null
                    || !GigaFilePolicy.isTrustedUploadOrigin(origin)
                    || !GigaFilePolicy.isTrustedUploadOrigin(activity.webView.getUrl())
                    || !GigaFilePolicy.isAllowedDownload(url)
                    || !GigaFilePolicy.isNumericUploadServerHost(GigaFilePolicy.hostOf(url))) {
                return;
            }
            HistoryStore.updateUploadKeysAsync(activity.getApplicationContext(), url, deleteKey, downloadKey);
        }

        private void saveUploadRemoval(String url, String origin, boolean trustedTransport) {
            MainActivity activity = activityReference.get();
            if (!trustedTransport || activity == null || activity.destroyed || activity.webView == null
                    || !GigaFilePolicy.isTrustedUploadOrigin(origin)
                    || !GigaFilePolicy.isTrustedUploadOrigin(activity.webView.getUrl())
                    || !GigaFilePolicy.isAllowedDownload(url)
                    || !GigaFilePolicy.isNumericUploadServerHost(GigaFilePolicy.hostOf(url))) {
                return;
            }
            HistoryStore.removeUploadAsync(activity.getApplicationContext(), url);
        }
    }

    private static final class PendingDownload {
        final String url;
        final String name;
        final String userAgent;
        final String referer;
        final String mimeType;
        final String contentDisposition;

        PendingDownload(String url, String name, String userAgent, String referer,
                        String mimeType, String contentDisposition) {
            this.url = url;
            this.name = name;
            this.userAgent = userAgent;
            this.referer = referer;
            this.mimeType = mimeType;
            this.contentDisposition = contentDisposition;
        }
    }
}
