package com.gigafile;

import android.net.Uri;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class GigaFilePolicy {
    public static final String HOME_URL = "https://gigafile.nu/";
    private static final String BASE_HOST = "gigafile.nu";
    private static final String STATIC_HOST = "src.gigafile.nu";
    private static final String SHORTENER_HOST = "shortener.gigafile.nu";
    private static final String XGF_SHORT_HOST = "xgf.nu";
    private static final String SHORTENER_API_PATH = "/api/pub_short_url.php";
    private static final Set<String> SHORTENER_GET_PATHS = immutableSet(
            "/css/bootstrap.min.css",
            "/css/ress.css",
            "/css/custom.css",
            "/css/all.min.css",
            "/js/jquery-3.5.1.min.js",
            "/js/jquery.qrcode.min.js",
            "/js/bootstrap.min.js",
            "/favicon.ico",
            "/webfonts/fa-brands-400.eot",
            "/webfonts/fa-brands-400.woff2",
            "/webfonts/fa-brands-400.woff",
            "/webfonts/fa-brands-400.ttf",
            "/webfonts/fa-brands-400.svg",
            "/webfonts/fa-regular-400.eot",
            "/webfonts/fa-regular-400.woff2",
            "/webfonts/fa-regular-400.woff",
            "/webfonts/fa-regular-400.ttf",
            "/webfonts/fa-regular-400.svg",
            "/webfonts/fa-solid-900.eot",
            "/webfonts/fa-solid-900.woff2",
            "/webfonts/fa-solid-900.woff",
            "/webfonts/fa-solid-900.ttf",
            "/webfonts/fa-solid-900.svg"
    );
    private static final Set<String> MAIN_SITE_RESOURCES = immutableSet(
            "/favicon.ico",
            "/js/common.js",
            "/js/upload.js"
    );
    private static final Set<String> STATIC_SCRIPTS = immutableSet(
            "/js/jquery-1.11.0.min.js",
            "/js/jquery-qrcode-0.17.0.min.js",
            "/js/gfupload-1.0.2.min.js",
            "/js/gfv4_setting.js",
            "/js/gf_meter.js"
    );
    private static final Set<String> STATIC_CSS = immutableSet(
            "/css/style_v4_sp.css",
            "/css/sp_gigafile_update_2026aw.css",
            "/css/base_new.css",
            "/css/base_sp.css",
            "/css/normalize.css"
    );
    private static final Set<String> STATIC_IMAGES = immutableSet(
            "/images/dl_link_btn.gif",
            "/images/btn_cancel.png",
            "/images/btn_start.png",
            "/images/icon_gfofficial.svg",
            "/images/gf_btn_bg.png",
            "/images/upload_panel_top.png",
            "/images/upload_panel_mid.png",
            "/images/upload_panel_bottom.png",
            "/images/prog_bg.png",
            "/images/v5_matomete_up_explain_sp.png"
    );
    private static final Set<String> UPLOAD_SERVER_POST_PATHS = immutableSet(
            "/upload_chunk.php",
            "/matomete_get_url.php",
            "/matomete_associate_url.php",
            "/matomete_delete_url.php"
    );
    private static final Set<String> UPLOAD_SERVER_GET_PATHS = immutableSet(
            "/set_dlkey.php",
            "/check_dlkey.php",
            "/remove.php",
            "/get_uploaded_file_name_jx.php",
            "/get_download_prog.php",
            "/get_av_status.php",
            "/download.php",
            "/dl_zip.php",
            "/bypass_dl.php"
    );
    private static final Set<String> DOWNLOAD_API_GET_PATHS = immutableSet(
            "/get_uploaded_file_name_jx.php",
            "/get_download_prog.php",
            "/get_av_status.php",
            "/download.php",
            "/dl_zip.php"
    );

    private GigaFilePolicy() {
    }

    public static boolean isAllowedNavigation(String value) {
        Uri uri = parse(value);
        if (!isSecureHttpUri(uri)) {
            return false;
        }
        String host = hostOf(uri);
        String path = normalizedPath(uri);
        if (BASE_HOST.equals(host) || SHORTENER_HOST.equals(host)) {
            return "/".equals(path);
        }
        if (XGF_SHORT_HOST.equals(host)) {
            return isValidXgfShortPath(path);
        }
        return isNumericUploadServerHost(host);
    }

    public static boolean isAllowedExternalOpen(String value) {
        Uri uri = parse(value);
        if (!isSecureHttpUri(uri)) {
            return false;
        }
        String host = hostOf(uri);
        if (BASE_HOST.equals(host) || SHORTENER_HOST.equals(host)) {
            return "/".equals(normalizedPath(uri));
        }
        if (XGF_SHORT_HOST.equals(host)) {
            return isValidXgfShortPath(normalizedPath(uri));
        }
        return isNumericUploadServerHost(host);
    }

    public static boolean isAllowedDownload(String value) {
        Uri uri = parse(value);
        String host = hostOf(uri);
        String path = normalizedPath(uri);
        return isSecureHttpUri(uri)
                && (isNumericUploadServerHost(host) || isNumericApiServerHost(host))
                && !"/".equals(path);
    }

    public static boolean isAllowedFileTransfer(String value) {
        Uri uri = parse(value);
        String host = hostOf(uri);
        String path = normalizedPath(uri);
        return isSecureHttpUri(uri)
                && (isNumericUploadServerHost(host) || isNumericApiServerHost(host))
                && ("/download.php".equals(path)
                || "/dl_zip.php".equals(path)
                || "/bypass_dl.php".equals(path));
    }

    public static String getLocalStaticAssetPath(String value, String method) {
        Uri uri = parse(value);
        if (!isSecureHttpUri(uri)
                || !STATIC_HOST.equals(hostOf(uri))
                || !"GET".equals(upper(method))) {
            return null;
        }
        String path = normalizedPath(uri);
        return isAllowedStaticResource(path) ? "gigafile-static" + path : null;
    }

    public static boolean isAllowedRequest(String value, boolean mainFrame) {
        return isAllowedRequest(value, mainFrame, "GET");
    }

    public static boolean isAllowedRequest(String value, boolean mainFrame, String method) {
        Uri uri = parse(value);
        if (uri == null) {
            return false;
        }

        String scheme = lower(uri.getScheme());
        if ("data".equals(scheme) || "blob".equals(scheme)) {
            return !mainFrame;
        }
        if ("about".equals(scheme)) {
            return !mainFrame && "blank".equalsIgnoreCase(uri.getSchemeSpecificPart());
        }
        if (!isSecureHttpUri(uri)) {
            return false;
        }

        String host = hostOf(uri);
        String path = normalizedPath(uri);
        String requestMethod = upper(method);

        if (mainFrame) {
            return "GET".equals(requestMethod) && isAllowedNavigation(value);
        }

        if (BASE_HOST.equals(host)) {
            return "GET".equals(requestMethod) && MAIN_SITE_RESOURCES.contains(path);
        }

        if (STATIC_HOST.equals(host)) {
            return false;
        }

        if (SHORTENER_HOST.equals(host)) {
            if ("GET".equals(requestMethod)) {
                return SHORTENER_GET_PATHS.contains(path);
            }
            return ("POST".equals(requestMethod) || "OPTIONS".equals(requestMethod))
                    && SHORTENER_API_PATH.equals(path);
        }
        if (XGF_SHORT_HOST.equals(host)) {
            return false;
        }

        if (isNumericApiServerHost(host)) {
            return "GET".equals(requestMethod) && DOWNLOAD_API_GET_PATHS.contains(path);
        }

        if (isNumericUploadServerHost(host)) {
            if ("OPTIONS".equals(requestMethod)) {
                return UPLOAD_SERVER_POST_PATHS.contains(path);
            }
            if ("POST".equals(requestMethod)) {
                return UPLOAD_SERVER_POST_PATHS.contains(path);
            }
            if ("GET".equals(requestMethod)) {
                return UPLOAD_SERVER_GET_PATHS.contains(path) || isPageResourcePath(path);
            }
        }

        return false;
    }

    public static boolean isTrustedShortenerPage(String value) {
        Uri uri = parse(value);
        return isSecureHttpUri(uri)
                && SHORTENER_HOST.equals(hostOf(uri))
                && "/".equals(normalizedPath(uri));
    }

    public static boolean isSuppressedShortenerImage(String value, String method) {
        Uri uri = parse(value);
        if (!isSecureHttpUri(uri) || !SHORTENER_HOST.equals(hostOf(uri))
                || !"GET".equals(upper(method))) {
            return false;
        }
        String path = normalizedPath(uri);
        return "/images/top.jpg".equals(path) || "/images/logo.png".equals(path);
    }

    public static boolean isValidXgfShortUrl(String value) {
        Uri uri = parse(value);
        return isSecureHttpUri(uri)
                && XGF_SHORT_HOST.equals(hostOf(uri))
                && isValidXgfShortPath(uri.getEncodedPath())
                && (uri.getQuery() == null || uri.getQuery().isEmpty())
                && (uri.getFragment() == null || uri.getFragment().isEmpty());
    }

    private static boolean isValidXgfShortPath(String path) {
        if (path == null || (path.length() != 5 && path.length() != 6)
                || path.charAt(0) != '/') {
            return false;
        }
        for (int i = 1; i < path.length(); i++) {
            char c = path.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z'))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isTrustedUploadOrigin(String value) {
        Uri uri = parse(value);
        return isSecureHttpUri(uri)
                && BASE_HOST.equals(hostOf(uri))
                && "/".equals(normalizedPath(uri));
    }

    public static boolean isTrustedGigaFilePage(String value) {
        Uri uri = parse(value);
        if (!isSecureHttpUri(uri)) {
            return false;
        }
        String host = hostOf(uri);
        return BASE_HOST.equals(host) && "/".equals(normalizedPath(uri))
                || isNumericUploadServerHost(host);
    }

    public static String hostOf(String value) {
        return hostOf(parse(value));
    }

    public static boolean isNumericUploadServerHost(String host) {
        String value = lower(host);
        if (value == null || !value.endsWith(".gigafile.nu")) {
            return false;
        }
        String label = value.substring(0, value.length() - ".gigafile.nu".length());
        return isDigits(label);
    }

    public static boolean isNumericApiServerHost(String host) {
        String value = lower(host);
        if (value == null || !value.endsWith("x.gigafile.nu")) {
            return false;
        }
        String label = value.substring(0, value.length() - "x.gigafile.nu".length());
        return isDigits(label);
    }

    private static boolean isDigits(String value) {
        if (value == null || value.isEmpty() || value.length() > 63) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isPageResourcePath(String path) {
        return "/favicon.ico".equals(path)
                || path.startsWith("/js/")
                || path.startsWith("/css/")
                || path.startsWith("/images/")
                || path.startsWith("/fonts/")
                || path.startsWith("/font/");
    }

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private static boolean isAllowedStaticResource(String path) {
        return STATIC_CSS.contains(path) || STATIC_SCRIPTS.contains(path) || STATIC_IMAGES.contains(path);
    }

    private static String hostOf(Uri uri) {
        if (uri == null) {
            return "";
        }
        String host = lower(uri.getHost());
        return host == null ? "" : host;
    }

    private static boolean isSecureHttpUri(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) {
            return false;
        }
        int port = uri.getPort();
        return port == -1 || port == 443;
    }

    private static String normalizedPath(Uri uri) {
        if (uri == null) {
            return "/";
        }
        String value = uri.getEncodedPath();
        if (value == null || value.isEmpty()) {
            value = "/";
        }
        for (int i = 0; i < 3; i++) {
            try {
                String decoded = Uri.decode(value);
                if (decoded.equals(value)) {
                    break;
                }
                value = decoded;
            } catch (RuntimeException ignored) {
                break;
            }
        }
        value = value.replace('\\', '/').toLowerCase(Locale.ROOT);
        String[] parts = value.split("/");
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else {
                stack.addLast(part);
            }
        }
        StringBuilder result = new StringBuilder("/");
        java.util.Iterator<String> iterator = stack.iterator();
        while (iterator.hasNext()) {
            result.append(iterator.next());
            if (iterator.hasNext()) {
                result.append('/');
            }
        }
        return result.toString();
    }

    private static Uri parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Uri.parse(value.trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
