package com.gigafile;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ShortUrlService {
    private static final String ORIGIN = "https://shortener.gigafile.nu";
    private static final String API_PATH = "/api/pub_short_url.php";
    private static final int PAGE_TIMEOUT_MS = 125000;
    private static final int READY_INTERVAL_MS = 150;
    private static final int MAX_READY_CHECKS = 180;
    private static final int CLICK_DELAY_MS = 700;
    private static final int RESULT_INTERVAL_MS = 200;
    private static final int API_START_TIMEOUT_MS = 6000;
    private static final int MAX_RESULT_CHECKS = 560;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ThreadPoolExecutor SAVE_EXECUTOR = createSaveExecutor();
    private static Controller activeController;

    public interface Callback {
        void onSuccess(String shortUrl, boolean saved);
        void onFailure(String reason);
        void onAlreadyRunning();
    }

    private ShortUrlService() {
    }

    private static ThreadPoolExecutor createSaveExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, "GigaFile-ShortURL-Store");
                        thread.setPriority(Thread.NORM_PRIORITY - 1);
                        thread.setDaemon(true);
                        return thread;
                    }
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    public static void generate(Context context, String originalUrl, String title, Callback callback) {
        if (!(context instanceof Activity) || callback == null || !isValidOriginalUrl(originalUrl)) {
            postFailure(callback, "アップロード履歴の共有URLが正しくありません");
            return;
        }
        String cached = HistoryStore.getUploadShortUrl(context, originalUrl);
        if (cached != null) {
            MAIN_HANDLER.post(() -> callback.onSuccess(cached, true));
            return;
        }
        Activity activity = (Activity) context;
        if (!IN_FLIGHT.add(originalUrl)) {
            MAIN_HANDLER.post(callback::onAlreadyRunning);
            return;
        }
        MAIN_HANDLER.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                IN_FLIGHT.remove(originalUrl);
                return;
            }
            if (activeController != null) {
                IN_FLIGHT.remove(originalUrl);
                callback.onAlreadyRunning();
                return;
            }
            Controller controller = new Controller(activity, originalUrl, normalizeTitle(title), callback);
            activeController = controller;
            controller.start();
        });
    }

    public static void cancel(Activity activity) {
        if (activity == null) {
            return;
        }
        Runnable action = () -> {
            Controller controller = activeController;
            if (controller != null && controller.activity == activity) {
                controller.cancel();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            MAIN_HANDLER.post(action);
        }
    }

    private static boolean isValidOriginalUrl(String value) {
        return value != null && value.length() <= 2048
                && GigaFilePolicy.isAllowedDownload(value)
                && GigaFilePolicy.isNumericUploadServerHost(GigaFilePolicy.hostOf(value));
    }

    private static String normalizeTitle(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "ファイル";
        }
        String result = value.replace('\r', ' ').replace('\n', ' ').trim();
        return result.length() > 240 ? result.substring(0, 240) : result;
    }

    private static String buildPageUrl(String originalUrl, String title) {
        return Uri.parse(ORIGIN + "/").buildUpon()
                .appendQueryParameter("url", originalUrl)
                .appendQueryParameter("filename", title)
                .build().toString();
    }

    private static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private static String decodeJavascriptResult(String value) {
        if (value == null || value.isEmpty() || "null".equals(value)) {
            return "";
        }
        try {
            Object decoded = new JSONTokener(value).nextValue();
            return decoded instanceof String ? (String) decoded : "";
        } catch (JSONException ignored) {
            return "";
        }
    }

    private static void postFailure(Callback callback, String reason) {
        if (callback != null) {
            MAIN_HANDLER.post(() -> callback.onFailure(reason));
        }
    }

    private static final class Controller {
        final Activity activity;
        final String originalUrl;
        final String title;
        final Callback callback;
        final String pageUrl;
        final Runnable timeout;
        final Runnable readyPoll;
        final Runnable clickAction;
        final Runnable resultPoll;
        WebView webView;
        boolean completed;
        boolean ready;
        boolean clickTriggered;
        boolean readinessCheckRunning;
        int readinessChecks;
        int resultChecks;
        volatile boolean apiRequestSeen;
        volatile int apiHttpStatus;
        volatile String apiNetworkError = "";
        volatile String javascriptAlert = "";

        Controller(Activity activity, String originalUrl, String title, Callback callback) {
            this.activity = activity;
            this.originalUrl = originalUrl;
            this.title = title;
            this.callback = callback;
            this.pageUrl = buildPageUrl(originalUrl, title);
            this.timeout = () -> fail("短縮ページまたは短縮APIがタイムアウトしました");
            this.readyPoll = this::checkPageReady;
            this.clickAction = this::triggerOfficialButton;
            this.resultPoll = this::pollResult;
        }

        void start() {
            try {
                webView = new WebView(activity);
                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setUserAgentString(WebSettings.getDefaultUserAgent(activity));
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(false);
                settings.setLoadsImagesAutomatically(false);
                settings.setBlockNetworkImage(true);
                settings.setBlockNetworkLoads(false);
                settings.setCacheMode(WebSettings.LOAD_DEFAULT);
                settings.setAllowFileAccess(false);
                settings.setAllowContentAccess(false);
                settings.setSupportZoom(false);
                settings.setBuiltInZoomControls(false);
                settings.setDisplayZoomControls(false);
                settings.setJavaScriptCanOpenWindowsAutomatically(false);
                settings.setSupportMultipleWindows(false);
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                settings.setSafeBrowsingEnabled(true);
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setAcceptCookie(true);
                cookieManager.setAcceptThirdPartyCookies(webView, false);
                webView.setAlpha(0f);
                webView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                webView.setVisibility(View.VISIBLE);
                webView.setFocusable(false);
                webView.setFocusableInTouchMode(false);
                webView.setClickable(false);
                webView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                webView.setWebViewClient(new Client());
                webView.setWebChromeClient(new ChromeClient());
                activity.addContentView(webView, new ViewGroup.LayoutParams(1, 1));
                MAIN_HANDLER.postDelayed(timeout, PAGE_TIMEOUT_MS);
                webView.loadUrl(pageUrl);
            } catch (Exception error) {
                fail("短縮処理用WebViewを初期化できませんでした");
            }
        }

        private void checkPageReady() {
            if (completed || ready || readinessCheckRunning || webView == null) {
                return;
            }
            readinessCheckRunning = true;
            webView.evaluateJavascript(readinessScript(), value -> {
                readinessCheckRunning = false;
                if (completed || ready) {
                    return;
                }
                String decoded = decodeJavascriptResult(value);
                try {
                    JSONObject result = new JSONObject(decoded);
                    String state = result.optString("state", "wait");
                    if ("ready".equals(state)) {
                        ready = true;
                        MAIN_HANDLER.postDelayed(clickAction, CLICK_DELAY_MS);
                    } else if ("error".equals(state)) {
                        fail(result.optString("message", "短縮ページの初期化に失敗しました"));
                    } else if (++readinessChecks >= MAX_READY_CHECKS) {
                        fail("短縮ページのjQueryまたは入力欄を確認できませんでした");
                    } else {
                        MAIN_HANDLER.postDelayed(readyPoll, READY_INTERVAL_MS);
                    }
                } catch (Exception ignored) {
                    if (++readinessChecks >= MAX_READY_CHECKS) {
                        fail("短縮ページの初期化状態を読み取れませんでした");
                    } else {
                        MAIN_HANDLER.postDelayed(readyPoll, READY_INTERVAL_MS);
                    }
                }
            });
        }

        private String readinessScript() {
            return "(function(){try{"
                    + "if(location.protocol!=='https:'||location.hostname!=='shortener.gigafile.nu'||location.pathname!=='/')return JSON.stringify({state:'error',message:'短縮サービスのURLが一致しません'});"
                    + "if(document.readyState!=='complete'||!window.jQuery)return JSON.stringify({state:'wait'});"
                    + "var $=window.jQuery,button=document.getElementById('pub-button'),input=document.getElementById('original-url'),title=document.getElementById('title'),output=document.getElementById('short-url');"
                    + "if(typeof $.fn==='undefined'||typeof $.fn.jquery!=='string'||typeof $.ajax!=='function'||!button||!input||!title||!output)return JSON.stringify({state:'wait'});"
                    + "if(button.disabled)return JSON.stringify({state:'error',message:'短縮ボタンが使用できません'});"
                    + "return JSON.stringify({state:'ready',jquery:$.fn.jquery});"
                    + "}catch(e){return JSON.stringify({state:'wait'});}})();";
        }

        private void triggerOfficialButton() {
            if (completed || clickTriggered || webView == null) {
                return;
            }
            clickTriggered = true;
            webView.evaluateJavascript(triggerScript(), value -> {
                if (completed) {
                    return;
                }
                String decoded = decodeJavascriptResult(value);
                try {
                    JSONObject result = new JSONObject(decoded);
                    if (!"triggered".equals(result.optString("state", ""))) {
                        fail(result.optString("message", "公式の短縮ボタンを実行できませんでした"));
                        return;
                    }
                    MAIN_HANDLER.postDelayed(resultPoll, RESULT_INTERVAL_MS);
                } catch (Exception ignored) {
                    fail("公式の短縮ボタンを実行できませんでした");
                }
            });
        }

        private String triggerScript() {
            return "(function(){try{"
                    + "if(location.protocol!=='https:'||location.hostname!=='shortener.gigafile.nu'||location.pathname!=='/')return JSON.stringify({state:'error',message:'短縮サービスのページが不正です'});"
                    + "var $=window.jQuery,button=document.getElementById('pub-button'),input=document.getElementById('original-url'),titleNode=document.getElementById('title'),output=document.getElementById('short-url');"
                    + "if(!$||!button||!input||!titleNode||!output)return JSON.stringify({state:'error',message:'短縮ページの必要な要素がありません'});"
                    + "if(window.__gigaNativeShortTriggered)return JSON.stringify({state:'error',message:'短縮処理は既に開始されています'});"
                    + "$(input).val(" + JSONObject.quote(originalUrl) + ");"
                    + "$(titleNode).text(" + JSONObject.quote(title) + ");"
                    + "$(output).val('');"
                    + "window.__gigaNativeShortAlert='';window.__gigaNativeShortTriggered=true;"
                    + "$(button).trigger('click');"
                    + "return JSON.stringify({state:'triggered'});"
                    + "}catch(e){return JSON.stringify({state:'error',message:String(e&&e.message||e)});}})();";
        }

        private void pollResult() {
            if (completed || !clickTriggered || webView == null) {
                return;
            }
            resultChecks++;
            if (javascriptAlert != null && !javascriptAlert.isEmpty()) {
                fail(sanitizeMessage(javascriptAlert));
                return;
            }
            if (apiNetworkError != null && !apiNetworkError.isEmpty()) {
                fail(sanitizeMessage(apiNetworkError));
                return;
            }
            if (apiHttpStatus >= 400) {
                fail("短縮APIがHTTP " + apiHttpStatus + " を返しました");
                return;
            }
            if (!apiRequestSeen && resultChecks * RESULT_INTERVAL_MS >= API_START_TIMEOUT_MS) {
                fail("公式短縮ボタンから短縮APIへの通信が開始されませんでした");
                return;
            }
            webView.evaluateJavascript(resultScript(), value -> {
                if (completed) {
                    return;
                }
                String decoded = decodeJavascriptResult(value);
                try {
                    JSONObject result = new JSONObject(decoded);
                    String state = result.optString("state", "pending");
                    if ("success".equals(state)) {
                        completeSuccess(result.optString("value", ""));
                    } else if ("error".equals(state)) {
                        fail(sanitizeMessage(result.optString("message", "短縮URLを生成できませんでした")));
                    } else if (resultChecks >= MAX_RESULT_CHECKS) {
                        fail(apiRequestSeen
                                ? "短縮APIへの通信後に短縮URLが表示されませんでした"
                                : "短縮APIの通信を確認できませんでした");
                    } else {
                        MAIN_HANDLER.postDelayed(resultPoll, RESULT_INTERVAL_MS);
                    }
                } catch (Exception ignored) {
                    if (resultChecks >= MAX_RESULT_CHECKS) {
                        fail("短縮ページから生成結果を取得できませんでした");
                    } else {
                        MAIN_HANDLER.postDelayed(resultPoll, RESULT_INTERVAL_MS);
                    }
                }
            });
        }

        private String resultScript() {
            return "(function(){try{"
                    + "var alertText=String(window.__gigaNativeShortAlert||'');if(alertText)return JSON.stringify({state:'error',message:alertText});"
                    + "var output=document.getElementById('short-url'),value=output?String(output.value||'').trim():'';"
                    + "if(value)return JSON.stringify({state:'success',value:value});"
                    + "return JSON.stringify({state:'pending'});"
                    + "}catch(e){return JSON.stringify({state:'error',message:'短縮結果を読み取れませんでした'});}})();";
        }

        private void completeSuccess(String value) {
            if (completed) {
                return;
            }
            String shortUrl = value == null ? "" : value.trim();
            if (!GigaFilePolicy.isValidXgfShortUrl(shortUrl)) {
                fail("短縮APIが有効な短縮URLを返しませんでした");
                return;
            }
            completed = true;
            releaseWebView();
            releaseController();
            try {
                SAVE_EXECUTOR.execute(() -> {
                    boolean saved = false;
                    try {
                        saved = HistoryStore.saveUploadShortUrl(
                                activity.getApplicationContext(), originalUrl, shortUrl);
                    } catch (RuntimeException ignored) {
                    }
                    boolean persisted = saved;
                    MAIN_HANDLER.post(() -> callback.onSuccess(shortUrl, persisted));
                });
            } catch (RejectedExecutionException error) {
                MAIN_HANDLER.post(() -> callback.onSuccess(shortUrl, false));
            }
        }

        private void fail(String reason) {
            if (completed) {
                return;
            }
            completed = true;
            releaseWebView();
            releaseController();
            callback.onFailure(reason == null || reason.trim().isEmpty()
                    ? "短縮URLを生成できませんでした" : reason);
        }

        void cancel() {
            if (completed) {
                return;
            }
            completed = true;
            releaseWebView();
            releaseController();
        }

        private void releaseController() {
            MAIN_HANDLER.removeCallbacks(timeout);
            MAIN_HANDLER.removeCallbacks(readyPoll);
            MAIN_HANDLER.removeCallbacks(clickAction);
            MAIN_HANDLER.removeCallbacks(resultPoll);
            IN_FLIGHT.remove(originalUrl);
            if (activeController == this) {
                activeController = null;
            }
        }

        private void releaseWebView() {
            WebView target = webView;
            webView = null;
            if (target == null) {
                return;
            }
            try {
                ViewGroup parent = (ViewGroup) target.getParent();
                if (parent != null) {
                    parent.removeView(target);
                }
            } catch (Exception ignored) {
            }
            try {
                target.stopLoading();
                target.setWebChromeClient(null);
                target.setWebViewClient(null);
                target.removeAllViews();
                target.destroy();
            } catch (Exception ignored) {
            }
        }

        private String sanitizeMessage(String value) {
            if (value == null || value.trim().isEmpty()) {
                return "短縮URLを生成できませんでした";
            }
            String result = value.replace('\r', ' ').replace('\n', ' ').trim();
            return result.length() > 180 ? result.substring(0, 180) : result;
        }

        private boolean isShortenerOrigin(Uri uri) {
            return uri != null
                    && "https".equalsIgnoreCase(uri.getScheme())
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && "shortener.gigafile.nu".equalsIgnoreCase(uri.getHost());
        }

        private boolean isAllowedScript(String path) {
            return "/js/jquery-3.5.1.min.js".equals(path)
                    || "/js/jquery.qrcode.min.js".equals(path)
                    || "/js/bootstrap.min.js".equals(path);
        }

        private boolean isRequiredScript(String path) {
            return "/js/jquery-3.5.1.min.js".equals(path);
        }

        private final class Client extends WebViewClient {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request == null ? null : request.getUrl();
                return uri == null || !GigaFilePolicy.isTrustedShortenerPage(uri.toString());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) {
                    return emptyResponse();
                }
                Uri uri = request.getUrl();
                String path = uri.getEncodedPath();
                String method = request.getMethod();
                if (isShortenerOrigin(uri)) {
                    if (request.isForMainFrame() && "GET".equalsIgnoreCase(method)
                            && (path == null || path.isEmpty() || "/".equals(path))) {
                        return null;
                    }
                    if (!request.isForMainFrame() && "GET".equalsIgnoreCase(method)
                            && isAllowedScript(path)) {
                        return null;
                    }
                    if (!request.isForMainFrame() && API_PATH.equals(path)) {
                        if ("OPTIONS".equalsIgnoreCase(method)) {
                            return null;
                        }
                        if ("POST".equalsIgnoreCase(method)) {
                            apiRequestSeen = true;
                            return null;
                        }
                    }
                }
                return emptyResponse();
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!GigaFilePolicy.isTrustedShortenerPage(url)) {
                    fail("短縮サービス以外のページへの遷移を拒否しました");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!completed && GigaFilePolicy.isTrustedShortenerPage(url)) {
                    MAIN_HANDLER.removeCallbacks(readyPoll);
                    checkPageReady();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request == null || request.getUrl() == null) {
                    return;
                }
                Uri uri = request.getUrl();
                if (request.isForMainFrame()) {
                    fail("短縮ページを読み込めませんでした");
                } else if (isShortenerOrigin(uri) && isRequiredScript(uri.getEncodedPath())) {
                    fail("短縮ページのjQueryを読み込めませんでした");
                } else if (isShortenerOrigin(uri) && API_PATH.equals(uri.getEncodedPath())) {
                    apiNetworkError = "短縮APIへの通信に失敗しました";
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request == null || request.getUrl() == null || errorResponse == null) {
                    return;
                }
                Uri uri = request.getUrl();
                int status = errorResponse.getStatusCode();
                if (request.isForMainFrame() && status >= 400) {
                    fail("短縮ページがHTTP " + status + " を返しました");
                } else if (isShortenerOrigin(uri) && API_PATH.equals(uri.getEncodedPath())
                        && status >= 400) {
                    apiHttpStatus = status;
                } else if (isShortenerOrigin(uri) && isRequiredScript(uri.getEncodedPath())
                        && status >= 400) {
                    fail("短縮ページのjQueryがHTTP " + status + " を返しました");
                }
            }
        }

        private final class ChromeClient extends WebChromeClient {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, android.webkit.JsResult result) {
                if (!completed && message != null && !message.trim().isEmpty()) {
                    javascriptAlert = message;
                }
                result.confirm();
                return true;
            }
        }
    }
}
