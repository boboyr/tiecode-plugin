package com.scala.android.util;

import java.io.File;
import java.io.FileInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 AndroidManifest.xml 里读几个我们需要的属性。
 *
 * <p>插件不需要完整的清单解析（合并交给宿主的 {@code Tool.MANIFEST_MERGER}），
 * 只取 <b>package</b>（aapt2 link 的 {@code --extra-packages}）与
 * <b>minSdkVersion / targetSdkVersion</b>（用于和 build.sbt 里的值兜底合并）。
 */
public final class ManifestInfo {

    private static final Pattern PACKAGE = Pattern.compile(
            "<manifest[^>]*\\bpackage\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIN_SDK = Pattern.compile(
            "android:minSdkVersion\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_SDK = Pattern.compile(
            "android:targetSdkVersion\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_CODE = Pattern.compile(
            "android:versionCode\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_NAME = Pattern.compile(
            "android:versionName\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    public final String packageName;
    public final Integer minSdk;
    public final Integer targetSdk;
    public final Integer versionCode;
    public final String versionName;

    private ManifestInfo(String packageName, Integer minSdk, Integer targetSdk,
                         Integer versionCode, String versionName) {
        this.packageName = packageName;
        this.minSdk = minSdk;
        this.targetSdk = targetSdk;
        this.versionCode = versionCode;
        this.versionName = versionName;
    }

    public static ManifestInfo parse(File manifest) {
        if (manifest == null || !manifest.isFile()) {
            return null;
        }
        String text = read(manifest);
        if (text == null) {
            return null;
        }
        return new ManifestInfo(
                match(PACKAGE, text),
                toInt(match(MIN_SDK, text)),
                toInt(match(TARGET_SDK, text)),
                toInt(match(VERSION_CODE, text)),
                match(VERSION_NAME, text));
    }

    /** 只取包名（AAR 清单用）。 */
    public static String packageOf(File manifest) {
        ManifestInfo info = parse(manifest);
        return info == null ? null : info.packageName;
    }

    private static String match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static Integer toInt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String read(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.max(1024, file.length())];
            int read = input.read(buffer);
            return read <= 0 ? null : new String(buffer, 0, read, "UTF-8");
        } catch (Throwable error) {
            return null;
        }
    }
}
