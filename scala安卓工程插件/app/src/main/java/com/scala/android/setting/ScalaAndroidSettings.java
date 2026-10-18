package com.scala.android.setting;

import android.util.Log;

import com.rapid.api.Platform;
import com.rapid.framework.data.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 「Scala安卓工程」的设置项。
 *
 * <p>全部存在宿主的 {@link SharedPreferences} 里，键以 {@code SCALA_ANDROID_} 开头，
 * 与其它插件不冲突。构建任务读这里，设置页写这里。
 */
public final class ScalaAndroidSettings {

    private static final String TAG = "ScalaAndroidSettings";

    /** 离线模式：只用本地已下载的依赖，不访问网络。 */
    public static final String KEY_OFFLINE = "SCALA_ANDROID_OFFLINE";
    /** 复用宿主公共 Maven 目录（{@code /sdcard/Tiecode/Maven}）作为本地仓库。 */
    public static final String KEY_HOST_MAVEN = "SCALA_ANDROID_HOST_MAVEN";
    /** 是否把 {@code % Test} 依赖也算进 classpath。 */
    public static final String KEY_INCLUDE_TEST = "SCALA_ANDROID_INCLUDE_TEST";
    /** 仓库模式：默认（脚本 + 内置镜像）/ 仅脚本声明。 */
    public static final String KEY_REPOSITORY_MODE = "SCALA_ANDROID_REPOSITORY_MODE";
    /** 额外仓库（每行一个 URL）。 */
    public static final String KEY_EXTRA_REPOSITORIES = "SCALA_ANDROID_EXTRA_REPOS";

    public static final String MODE_DEFAULT = "默认（内置镜像 + 脚本声明）";
    public static final String MODE_SCRIPT_ONLY = "仅脚本里声明的仓库";

    private ScalaAndroidSettings() {
    }

    private static SharedPreferences preferences() {
        try {
            return Platform.getSharedPreferences();
        } catch (Throwable error) {
            return null;
        }
    }

    public static boolean offline() {
        SharedPreferences preferences = preferences();
        return preferences != null && preferences.getBoolean(KEY_OFFLINE, false);
    }

    public static void setOffline(boolean value) {
        putBoolean(KEY_OFFLINE, value);
    }

    public static boolean useHostMavenRepository() {
        SharedPreferences preferences = preferences();
        return preferences == null || preferences.getBoolean(KEY_HOST_MAVEN, true);
    }

    public static void setUseHostMavenRepository(boolean value) {
        putBoolean(KEY_HOST_MAVEN, value);
    }

    public static boolean includeTestDependencies() {
        SharedPreferences preferences = preferences();
        return preferences != null && preferences.getBoolean(KEY_INCLUDE_TEST, false);
    }

    public static void setIncludeTestDependencies(boolean value) {
        putBoolean(KEY_INCLUDE_TEST, value);
    }

    public static String repositoryMode() {
        SharedPreferences preferences = preferences();
        if (preferences == null) {
            return MODE_DEFAULT;
        }
        String value = preferences.getString(KEY_REPOSITORY_MODE);
        return value == null || value.isEmpty() ? MODE_DEFAULT : value;
    }

    public static void setRepositoryMode(String mode) {
        SharedPreferences preferences = preferences();
        if (preferences != null) {
            preferences.putString(KEY_REPOSITORY_MODE, mode);
            preferences.commit();
        }
    }

    /** 额外仓库 URL（每行一个）。 */
    public static List<String> extraRepositories() {
        List<String> result = new ArrayList<>();
        SharedPreferences preferences = preferences();
        if (preferences == null) {
            return result;
        }
        String value = preferences.getString(KEY_EXTRA_REPOSITORIES);
        if (value == null || value.trim().isEmpty()) {
            return result;
        }
        for (String line : value.split("[\\r\\n]+")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                result.add(trimmed);
            }
        }
        return result;
    }

    public static void setExtraRepositories(String text) {
        SharedPreferences preferences = preferences();
        if (preferences != null) {
            preferences.putString(KEY_EXTRA_REPOSITORIES, text == null ? "" : text);
            preferences.commit();
        }
    }

    public static String extraRepositoriesText() {
        SharedPreferences preferences = preferences();
        if (preferences == null) {
            return "";
        }
        String value = preferences.getString(KEY_EXTRA_REPOSITORIES);
        return value == null ? "" : value;
    }

    private static void putBoolean(String key, boolean value) {
        SharedPreferences preferences = preferences();
        if (preferences != null) {
            preferences.putBoolean(key, value);
            preferences.commit();
        } else {
            Log.w(TAG, "无法保存设置 " + key);
        }
    }

    /** 设置页上的一行摘要。 */
    public static String describe() {
        return (offline() ? "离线 " : "联网 ")
                + "· " + (useHostMavenRepository() ? "复用宿主Maven目录" : "插件私有目录")
                + "· " + (includeTestDependencies() ? "含Test依赖" : "不含Test依赖")
                + "· " + repositoryMode();
    }
}
