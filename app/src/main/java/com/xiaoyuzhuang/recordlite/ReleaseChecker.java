package com.xiaoyuzhuang.recordlite;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks public GitHub Releases only when the user taps "检查更新".
 * Never downloads, installs or requests APK install permissions.
 */
final class ReleaseChecker {
    private static final String API_URL =
            "https://api.github.com/repos/XiaoyuZhuang/recordlite/releases/latest";
    private static final String RELEASE_PREFIX =
            "https://github.com/XiaoyuZhuang/recordlite/releases/";
    private static final Pattern VERSION = Pattern.compile(
            "^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$");

    static final class Result {
        final String installed;
        final String latest;
        final String releasePage;
        final boolean hasUpdate;

        Result(String installed, String latest, String releasePage, boolean hasUpdate) {
            this.installed = installed;
            this.latest = latest;
            this.releasePage = releasePage;
            this.hasUpdate = hasUpdate;
        }
    }

    private ReleaseChecker() { }

    static Result check(Context context) throws Exception {
        String installed = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).versionName;
        if (installed == null) installed = "0.0.0";

        HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "RecordLite-Android-Update-Checker");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("GitHub 返回 HTTP " + code);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = conn.getInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (output.size() + n > 65536) {
                        throw new IllegalStateException("更新信息过大");
                    }
                    output.write(buffer, 0, n);
                }
            }
            JSONObject json = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            String latest = json.optString("tag_name", "");
            String page = json.optString("html_url", "");
            if (!page.startsWith(RELEASE_PREFIX) || latest.isEmpty()) {
                throw new IllegalStateException("GitHub 发布信息无效");
            }
            return new Result(installed, latest, page, compareVersions(latest, installed) > 0);
        } finally {
            conn.disconnect();
        }
    }

    /** Compares three numeric version components, never lexical strings. */
    static int compareVersions(String candidate, String installed) throws Exception {
        Matcher a = VERSION.matcher(candidate);
        Matcher b = VERSION.matcher(installed);
        if (!a.matches() || !b.matches()) {
            throw new IllegalArgumentException("无法解析版本号");
        }
        for (int i = 1; i <= 3; i++) {
            long x = Long.parseLong(a.group(i));
            long y = Long.parseLong(b.group(i));
            if (x != y) return Long.compare(x, y);
        }
        return 0;
    }
}
