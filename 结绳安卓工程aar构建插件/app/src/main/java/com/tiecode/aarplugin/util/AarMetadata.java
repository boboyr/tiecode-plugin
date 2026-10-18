package com.tiecode.aarplugin.util;

import com.rapid.util.file.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 结绳编译器在 {@code <任务中间目录>/metadata.json} 写出的产物信息。
 *
 * <p>宿主不同版本写出的结构不一样，这里两种都支持：
 * <pre>
 * 旧版(4.6 系，手机上装的就是这种)：
 * {
 *   "extraRes": [],
 *   "libs": [],
 *   "target": { "res": "...", "assets": "...", "jni": "...", "jniLibs": "...",
 *               "manifest": "...", "extra_res": "...", "libs": "...", "src": "..." }
 * }
 * 新版：
 * {
 *   "appConfig": {...},
 *   "productInfo": { "srcDir": "...", "resDir": "...", "assetsDir": "...", "jniLibsDir": "...",
 *                    "manifest": "...", "extraResDirs": [...], "libsDir": "...",
 *                    "packageName": "...", "rNamespace": "..." },
 *   "libs": [ { "file": "...", "compileOnly": false } ]
 * }
 * </pre>
 * 用 org.json 直接解析而不是用编译器自带的模型类：插件不带编译器，也不想跟宿主版本强绑定。
 */
public class AarMetadata {

    public static class AppConfig {
        public String appName;
        public int minSdk = 21;
        public int targetSdk = 28;
        public int versionCode = 1;
        public String versionName = "1.0";
    }

    public static class ProductInfo {
        public String packageName;
        public String srcDir;
        /** 编译器产出的 res（@安卓资源.图片 等都会落到这里） */
        public String resDir;
        /** 旧版 metadata 的 target.extra_res */
        public String extraResDir;
        public List<String> extraResDirs = new ArrayList<>();
        /** 编译器产出的 assets（@附加资源("...") 的内容会落到这里） */
        public String assetsDir;
        /** 旧版 metadata 的 target.jni（NDK 源目录，不打进 aar） */
        public String jniDir;
        /** 编译器产出的 jniLibs（@外部动态库("...") 的内容会落到这里） */
        public String jniLibsDir;
        public String libsDir;
        public String manifest;
        /** R 类所在包名，4.7 编译器才会写；老编译器固定为 rn_1 */
        public String rNamespace;
    }

    public static class Lib {
        public String file;
        public boolean compileOnly;
    }

    public AppConfig appConfig = new AppConfig();
    public ProductInfo productInfo = new ProductInfo();
    public List<Lib> libs = new ArrayList<>();

    public static AarMetadata read(File metadataFile) {
        if (metadataFile == null || !metadataFile.exists()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(FileUtils.readFile(metadataFile));
            AarMetadata result = new AarMetadata();
            result.readAppConfig(root.optJSONObject("appConfig"));
            result.readProductInfo(root.optJSONObject("productInfo"));
            result.readTarget(root.optJSONObject("target"));
            result.readExtraRes(root.optJSONArray("extraRes"));
            result.readLibs(root.optJSONArray("libs"));
            return result;
        } catch (Throwable t) {
            return null;
        }
    }

    private void readAppConfig(JSONObject app) {
        if (app == null) {
            return;
        }
        appConfig.appName = opt(app, "appName", appConfig.appName);
        appConfig.minSdk = app.optInt("minSdk", appConfig.minSdk);
        appConfig.targetSdk = app.optInt("targetSdk", appConfig.targetSdk);
        appConfig.versionCode = app.optInt("versionCode", appConfig.versionCode);
        appConfig.versionName = opt(app, "versionName", appConfig.versionName);
    }

    private void readProductInfo(JSONObject info) {
        if (info == null) {
            return;
        }
        ProductInfo p = productInfo;
        p.packageName = opt(info, "packageName", null);
        p.srcDir = opt(info, "srcDir", null);
        p.resDir = opt(info, "resDir", null);
        p.assetsDir = opt(info, "assetsDir", null);
        p.libsDir = opt(info, "libsDir", null);
        p.jniLibsDir = opt(info, "jniLibsDir", null);
        p.manifest = opt(info, "manifest", null);
        p.rNamespace = opt(info, "rNamespace", null);
        JSONArray extra = info.optJSONArray("extraResDirs");
        if (extra != null) {
            for (int i = 0; i < extra.length(); i++) {
                String dir = extra.optString(i, null);
                if (dir != null && !dir.isEmpty()) {
                    p.extraResDirs.add(dir);
                }
            }
        }
    }

    /** 旧版 metadata：{@code target.{res,assets,jni,jniLibs,manifest,extra_res,libs,src}} */
    private void readTarget(JSONObject target) {
        if (target == null) {
            return;
        }
        ProductInfo p = productInfo;
        if (p.srcDir == null) {
            p.srcDir = opt(target, "src", null);
        }
        if (p.resDir == null) {
            p.resDir = opt(target, "res", null);
        }
        if (p.assetsDir == null) {
            p.assetsDir = opt(target, "assets", null);
        }
        if (p.jniLibsDir == null) {
            p.jniLibsDir = opt(target, "jniLibs", null);
        }
        if (p.jniDir == null) {
            p.jniDir = opt(target, "jni", null);
        }
        if (p.manifest == null) {
            p.manifest = opt(target, "manifest", null);
        }
        if (p.extraResDir == null) {
            p.extraResDir = opt(target, "extra_res", null);
        }
        if (p.libsDir == null) {
            p.libsDir = opt(target, "libs", null);
        }
    }

    private void readExtraRes(JSONArray extraRes) {
        if (extraRes == null) {
            return;
        }
        for (int i = 0; i < extraRes.length(); i++) {
            String dir = extraRes.optString(i, null);
            if (dir != null && !dir.isEmpty() && !productInfo.extraResDirs.contains(dir)) {
                productInfo.extraResDirs.add(dir);
            }
        }
    }

    private void readLibs(JSONArray libs) {
        if (libs == null) {
            return;
        }
        for (int i = 0; i < libs.length(); i++) {
            JSONObject item = libs.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String path = opt(item, "file", null);
            if (path == null) {
                continue;
            }
            Lib lib = new Lib();
            lib.file = path;
            lib.compileOnly = item.optBoolean("compileOnly", false);
            this.libs.add(lib);
        }
    }

    /** 需要合并进 aar 的 res 目录（编译器 res + 附加资源目录）。 */
    public List<String> collectResDirs() {
        List<String> dirs = new ArrayList<>();
        addDir(dirs, productInfo.resDir);
        addDir(dirs, productInfo.extraResDir);
        for (String dir : productInfo.extraResDirs) {
            addDir(dirs, dir);
        }
        return dirs;
    }

    /** metadata 里记录的 R 类包名；没有就返回 null，交给源码探测。 */
    public String getRNamespace() {
        if (productInfo != null && productInfo.rNamespace != null && !productInfo.rNamespace.isEmpty()) {
            return productInfo.rNamespace;
        }
        return null;
    }

    private static void addDir(List<String> dirs, String dir) {
        if (dir != null && !dir.isEmpty() && !dirs.contains(dir)) {
            dirs.add(dir);
        }
    }

    private static String opt(JSONObject json, String key, String fallback) {
        String value = json.optString(key, null);
        if (value == null || value.isEmpty() || "null".equals(value)) {
            return fallback;
        }
        return value;
    }
}
