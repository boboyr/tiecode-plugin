package com.scala.android.androidproject.template;

import android.graphics.drawable.Drawable;

import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.project.BaseTemplate;
import com.rapid.api.project.Project;
import com.rapid.framework.data.form.Form;
import com.rapid.framework.data.form.FormInput;
import com.rapid.util.android.ContextUtils;
import com.scala.android.R;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.project.ScalaSources;
import com.scala.android.util.AssetTree;
import com.scala.android.util.ZipUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认模板：按 {@code scala-android-app-modern} 的结构生成一个可编译的 Scala 安卓工程。
 *
 * <pre>
 * build.sbt                        （占位符替换后的 sbt 脚本）
 * project/build.properties         sbt.version=2.0.3
 * .sbtopts                         --server
 * release-test.keystore            测试用 release 签名（与 build.sbt 里的配置对应）
 * src/main/AndroidManifest.xml
 * src/main/scala/&lt;包名目录&gt;/MainActivity.scala
 * src/main/res/**                  布局 / 图标 / values
 * </pre>
 */
public class ScalaAndroidTemplate extends BaseTemplate<ScalaAndroidProject> {

    public static final String TEMPLATE_NAME = "默认模板（sbt）";
    public static final String DEFAULT_PROJECT_NAME = "我的scala安卓工程";
    /** 模板的"单文件"形态（插件运行时优先用它，见 {@link ZipUtils} 的说明）。 */
    public static final String ASSET_ZIP = "templates/project/scala_android_sbt.zip";
    /** 模板的"目录"形态（仓库里的可读来源；zip 缺失时兜底）。 */
    public static final String ASSET_DIR = "templates/project/scala_android_sbt";

    public static final String KEY_PACKAGE = "package_name";
    public static final String KEY_APP_NAME = "app_name";
    public static final String KEY_SCALA_VERSION = "scala_version";
    public static final String KEY_MIN_SDK = "min_sdk";
    public static final String KEY_TARGET_SDK = "target_sdk";

    public static final String DEFAULT_PACKAGE = "com.example.myapp";
    public static final String DEFAULT_SCALA_VERSION = "3.8.4";
    /** Scala 3.8 运行库需要 API 33（java.lang.invoke.VarHandle）。 */
    public static final String DEFAULT_MIN_SDK = "33";
    public static final String DEFAULT_TARGET_SDK = "35";

    /**
     * 兜底内容：万一某个模板文件没被 aapt2 打进 assets（或 {@code AssetManager.list}
     * 拿不到子目录），这几个"缺了就不能构建"的文件按内容直接写出来。
     */
    private static final String BUILD_PROPERTIES = "sbt.version=2.0.3\n";
    private static final String SBT_OPTS = "--server\n";

    @Override
    public String getName() {
        return TEMPLATE_NAME;
    }

    /**
     * 「新建工程」对话框里的模板图片。
     *
     * <p>返回 null 时宿主会退回通用模板图标（见宿主 {@code TemplateAdapter}：
     * {@code drawable == null} → {@code R.drawable.ic_project_template_defalut}），
     * 所以这里必须给一张 Scala 自己的图。
     */
    @Override
    public Drawable getPicture() {
        return ContextUtils.getDrawable(
                AndroidPlugins.getContext(this),
                R.drawable.ic_scala_project
        );
    }

    @Override
    public String getDefaultProjectName() {
        return DEFAULT_PROJECT_NAME;
    }

    @Override
    public Form getForm() {
        Form form = new Form();
        form.addInput(new FormInput(
                FormInput.Kind.STRING,
                KEY_PACKAGE,
                "应用包名",
                "例如 " + DEFAULT_PACKAGE,
                DEFAULT_PACKAGE
        ));
        form.addInput(new FormInput(
                FormInput.Kind.STRING,
                KEY_APP_NAME,
                "应用名称",
                "显示在桌面图标下面",
                "我的应用"
        ));
        form.addInput(new FormInput(
                FormInput.Kind.STRING,
                KEY_SCALA_VERSION,
                "Scala 版本",
                "随插件打包的编译器是 " + DEFAULT_SCALA_VERSION,
                DEFAULT_SCALA_VERSION
        ));
        form.addInput(new FormInput(
                FormInput.Kind.STRING,
                KEY_MIN_SDK,
                "minSdk",
                "最低支持的 Android 版本（API 级别）；Scala 3.8 运行库要求 ≥ 33",
                DEFAULT_MIN_SDK
        ));
        form.addInput(new FormInput(
                FormInput.Kind.STRING,
                KEY_TARGET_SDK,
                "targetSdk",
                "目标 Android 版本（API 级别）",
                DEFAULT_TARGET_SDK
        ));
        return form;
    }

    @Override
    public void create(ScalaAndroidProject project, Form form, Project.OnProjectCreateListener listener) {
        try {
            String packageName = read(form, KEY_PACKAGE, DEFAULT_PACKAGE);
            String appName = read(form, KEY_APP_NAME, "我的应用");
            String scalaVersion = read(form, KEY_SCALA_VERSION, DEFAULT_SCALA_VERSION);
            String minSdk = read(form, KEY_MIN_SDK, DEFAULT_MIN_SDK);
            String targetSdk = read(form, KEY_TARGET_SDK, DEFAULT_TARGET_SDK);

            Map<String, String> replacements = new LinkedHashMap<>();
            replacements.put("package_name", packageName);
            replacements.put("app_name", appName);
            replacements.put("project_name", project.getName());
            replacements.put("organization", organizationOf(packageName));
            replacements.put("scala_version", scalaVersion);
            replacements.put("min_sdk", minSdk);
            replacements.put("target_sdk", targetSdk);
            replacements.put("platform_target", "android-" + targetSdk);
            replacements.put("version_name", "1.0.0");

            File root = project.getProjectDir();
            List<String> written = extractTemplate(project, root, replacements);
            moveSampleSource(project, packageName);
            // 关键文件兜底：缺了 plugins.sbt / build.properties，插件就不知道这是安卓工程
            ensureProjectFiles(root, written);
            writeSbtOpts(root);
            if (project.getLogger() != null) {
                project.getLogger().info("已按模板生成工程：" + written.size() + " 个文件"
                        + " · 包名 " + packageName + " · Scala " + scalaVersion
                        + " · minSdk " + minSdk + " / targetSdk " + targetSdk);
            }
            listener.onSuccess();
        } catch (Throwable error) {
            error.printStackTrace();
            listener.onFail("创建 Scala 安卓工程失败：" + error);
        }
    }

    /**
     * 把模板铺到工程目录。
     *
     * <p>优先用 <b>zip 形态</b>：{@code assets/.../scala_android_sbt.zip}
     * → {@code open()} 读出来 → {@code java.util.zip} 解包 → 对文本做占位符替换。
     * 这条路径只依赖"能按路径读单个 assets 文件"，不依赖 {@code AssetManager.list()}
     * （实测 list() 对嵌套目录会漏，见 {@link com.scala.android.util.ZipUtils}）。
     *
     * <p>zip 不存在时退回目录形态（{@link AssetTree}），再不行就只靠
     * {@link #ensureProjectFiles} 把"缺了就不能构建"的文件写出来。
     */
    private List<String> extractTemplate(ScalaAndroidProject project, File root,
                                         Map<String, String> replacements) {
        android.content.Context context = AndroidPlugins.getContext(this);
        List<String> written = new ArrayList<>();
        if (ZipUtils.assetExists(context, ASSET_ZIP)) {
            try {
                File temp = new File(project.getChildFile("build/.template"),
                        "scala_android_sbt.zip");
                File parent = temp.getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    parent.mkdirs();
                }
                ZipUtils.copyAsset(context, ASSET_ZIP, temp);
                written = ZipUtils.unzip(temp, root);
                int replaced = ZipUtils.replacePlaceholders(root, replacements);
                if (project.getLogger() != null) {
                    project.getLogger().info("模板解包：" + written.size() + " 个文件，"
                            + replaced + " 个文件完成占位符替换");
                }
                return written;
            } catch (Throwable error) {
                if (project.getLogger() != null) {
                    project.getLogger().warning("模板 zip 解包失败，改用目录方式：" + error);
                }
            }
        }
        try {
            written = AssetTree.copy(context, ASSET_DIR, root, replacements);
        } catch (Throwable error) {
            if (project.getLogger() != null) {
                project.getLogger().warning("模板目录拷贝失败：" + error);
            }
        }
        return written;
    }

    /**
     * 保证 {@code project/plugins.sbt} 与 {@code project/build.properties} 一定存在。
     *
     * <p>这两个文件决定"能不能被识别为 Scala 安卓工程、能不能知道 sbt 版本"，
     * 所以即使 assets 拷贝出了问题也要补上。
     */
    private void ensureProjectFiles(File root, List<String> written) {
        // 只要 build.properties（声明 sbt 版本，纯元信息）。
        // 不生成 project/plugins.sbt：本插件自己解析 build.sbt 里的
        // platformTarget / androidApplicationId / … 这些键，
        // 并不需要真正的 sbt 插件（真要用命令行 sbt 构建时再加回 addSbtPlugin）。
        ensureFile(new File(root, "project/build.properties"), BUILD_PROPERTIES, written);
    }

    private void ensureFile(File file, String content, List<String> written) {
        try {
            if (file.isFile() && file.length() > 0) {
                return;
            }
            AssetTree.writeText(file, content);
            written.add(file.getName());
        } catch (IOException error) {
            error.printStackTrace();
        }
    }

    /** 模板里示例源码放在 {@code src/main/scala/pkg/}，这里挪到真实包目录下。 */
    private void moveSampleSource(ScalaAndroidProject project, String packageName) throws IOException {
        File sampleDir = project.getChildFile("src/main/scala/pkg");
        File sample = new File(sampleDir, "MainActivity.scala");
        if (!sample.isFile()) {
            return;
        }
        String relative = ScalaSources.sourcePathUnder(
                ScalaSources.ANDROID_SOURCE_ROOT, packageName, "MainActivity.scala");
        File target = project.getChildFile(relative);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建包目录 " + parent);
        }
        com.rapid.util.file.FileUtils.copyFile(sample, target);
        deleteRecursively(sampleDir);
    }

    /** sbt 在 Termux/低内存设备上建议开 server 模式（与 scala-android-app-modern 一致）。 */
    private void writeSbtOpts(File root) {
        try {
            AssetTree.writeText(new File(root, ".sbtopts"), SBT_OPTS);
        } catch (IOException ignored) {
            // 这个小文件写不出来不影响构建。
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private static String read(Form form, String key, String fallback) {
        if (form == null || form.getInput(key) == null) {
            return fallback;
        }
        String value = form.getInput(key).getStringValue();
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** {@code com.example.myapp} → {@code com.example}。 */
    private static String organizationOf(String packageName) {
        if (packageName == null) {
            return "com.example";
        }
        int index = packageName.lastIndexOf('.');
        return index > 0 ? packageName.substring(0, index) : packageName;
    }
}
