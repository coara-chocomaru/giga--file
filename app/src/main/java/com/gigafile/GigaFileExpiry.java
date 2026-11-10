package com.gigafile;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import java.util.Locale;

public final class GigaFileExpiry {
    private static final int MAX_LIFETIME_DAYS = 100;
    private static final Pattern DATE_CODE = Pattern.compile("^[01][0-9][0-3][0-9]$");
    private static final Pattern NUMERIC_HOST = Pattern.compile("^[0-9]+\\.gigafile\\.nu$");
    private static final String[] WEEKDAYS = {"日", "月", "火", "水", "木", "金", "土"};

    private GigaFileExpiry() {
    }

    public static LocalDate resolve(String url, long uploadedAt) {
        LocalDate uploadDate = uploadDate(uploadedAt);
        String code = extractDateCode(url);
        if (uploadDate == null || code == null) {
            return null;
        }
        int month = Integer.parseInt(code.substring(0, 2));
        int day = Integer.parseInt(code.substring(2, 4));
        for (int offset = 0; offset <= 1; offset++) {
            LocalDate candidate;
            try {
                candidate = LocalDate.of(uploadDate.getYear() + offset, month, day);
            } catch (RuntimeException ignored) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(uploadDate, candidate);
            if (days >= 0L && days <= MAX_LIFETIME_DAYS) {
                return candidate;
            }
        }
        return null;
    }

    public static LocalDate fromStoredOrResolve(String url, long uploadedAt, String storedDate) {
        LocalDate uploadDate = uploadDate(uploadedAt);
        LocalDate stored = parseStored(storedDate);
        String code = extractDateCode(url);
        if (uploadDate != null && stored != null && code != null
                && stored.getMonthValue() == Integer.parseInt(code.substring(0, 2))
                && stored.getDayOfMonth() == Integer.parseInt(code.substring(2, 4))) {
            long days = ChronoUnit.DAYS.between(uploadDate, stored);
            if (days >= 0L && days <= MAX_LIFETIME_DAYS) {
                return stored;
            }
        }
        return resolve(url, uploadedAt);
    }

    public static String toIsoDate(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    public static LocalDate parseStored(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static String format(LocalDate date) {
        if (date == null) {
            return "";
        }
        int weekday = date.getDayOfWeek().getValue() % 7;
        return date.getYear() + "年" + date.getMonthValue() + "月" + date.getDayOfMonth()
                + "日(" + WEEKDAYS[weekday] + ")";
    }

    public static boolean isExpired(LocalDate date) {
        return date != null && date.isBefore(LocalDate.now(ZoneId.systemDefault()));
    }

    private static LocalDate uploadDate(long uploadedAt) {
        if (uploadedAt <= 0L) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(uploadedAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String extractDateCode(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || !NUMERIC_HOST.matcher(uri.getHost().toLowerCase(Locale.ROOT)).matches()
                    || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return null;
            }
            String path = uri.getPath();
            if (path == null || path.isEmpty() || path.endsWith("/")) {
                return null;
            }
            int slash = path.lastIndexOf('/');
            String fileKey = path.substring(slash + 1);
            String[] parts = fileKey.split("-", -1);
            for (String part : parts) {
                if (!DATE_CODE.matcher(part).matches()) {
                    continue;
                }
                int month = Integer.parseInt(part.substring(0, 2));
                int day = Integer.parseInt(part.substring(2, 4));
                if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                    return part;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
