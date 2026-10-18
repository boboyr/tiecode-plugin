package com.scala.android.androidproject.template;

import android.content.Context;
import android.graphics.drawable.Drawable;

import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.project.BaseTemplate;
import com.rapid.api.project.Project;
import com.rapid.framework.data.form.Form;
import com.rapid.framework.data.form.FormInput;
import com.rapid.util.android.ContextUtils;
import com.scala.android.R;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.util.AssetTree;
import com.scala.android.util.ZipUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「结绳插件工程」模板：按结绳本体的 {@code BuildQiPluginConfig} /
 * {@code DebugQiPluginConfig} 的约定生成一个可编译成 {@code .rmx} 的插件工程。
 *
 * <pre>
 * build.sbt                        （占位符替换后的 sbt 脚本，带 tiecodePlugin 识别键）
 * project/build.properties         sbt.version=2.0.3
 * .sbtopts                         --server
 * release-test.keystore            测试用 release 签名（调试插件构建也用宿主测试证书）
 * src/main/AndroidManifest.xml     带 &lt;meta-data tiecode_plugin=true&gt; 等插件开关
 * src/main/resources/META-INF/program.json   结绳新世代插件清单（★插件装载器靠它识别）
 * src/main/scala/&lt;包名目录&gt;/TestMenuAction.scala  菜单扩展点示例（Scala 写法）
 * src/main/res/**                  图标 / 颜色 / 字符串
 * </pre>
 *
 * <p>关键差异（相对 {@link ScalaAndroidTemplate}）：
 * <ul>
 *   <li>产物是 <b>.rmx</b>（结绳插件）而不是可安装 APK：运行配置「构建结绳插件」指向
 *       {@code buildPlugin} 任务，「调试结绳插件」指向 {@code installPlugin} 任务；</li>
 *   <li>清单带 {@code <meta-data tiecode_plugin=true>}（旧世代插件开关，4.x 兼容）；</li>
 *   <li>必须附带 {@code META-INF/program.json}（新世代清单），构建任务会校验；</li>
 *   <li>示例源码用 {@code TestMenuAction.scala}（{@code domain.menu} 扩展点，继承
 *       {@code com.rapid.api.framework.domain.menu.BaseDomainMenuAction}），
 *       而不是 {@code MainActivity.scala}。</li>
 * </ul>
 */
public class ScalaPluginProjectTemplate extends BaseTemplate<ScalaAndroidProject> {

    public static final String TEMPLATE_NAME = "结绳插件工程";
    public static final String DEFAULT_PROJECT_NAME = "我的结绳scala插件";

    /** 模板的"单文件"形态（插件运行时优先用它，见 {@link ZipUtils} 的说明）。 */
    public static final String ASSET_ZIP = "templates/project/scala_tiecode_plugin.zip";
    /** 模板的"目录"形态（仓库里的可读来源；zip 缺失时兜底）。 */
    public static final String ASSET_DIR = "templates/project/scala_tiecode_plugin";

    public static final String KEY_PACKAGE = "plugin_package";
    public static final String KEY_APP_NAME = "plugin_name";
    public static final String KEY_SCALA_VERSION = "plugin_scala_version";
    public static final String KEY_MIN_SDK = "plugin_min_sdk";
    public static final String KEY_TARGET_SDK = "plugin_target_sdk";
    public static final String KEY_PLUGIN_NOTE = "plugin_note";

    public static final String DEFAULT_PACKAGE = "com.example.myplugin";
    public static final String DEFAULT_NAME = "我的结绳插件";
    public static final String DEFAULT_SCALA_VERSION = "3.8.4";
    /** Scala 3.8 运行库需要 API 33（java.lang.invoke.VarHandle）。 */
    public static final String DEFAULT_MIN_SDK = "33";
    public static final String DEFAULT_TARGET_SDK = "35";
    public static final String DEFAULT_PLUGIN_NOTE = "结绳 Scala 插件（模板生成）";

    /** 兜底内容：万一模板文件没被 aapt2 打进 assets，这几个"缺了就不能构建"的文件按内容直接写出来。 */
    private static final String BUILD_PROPERTIES = "sbt.version=2.0.3\n";
    private static final String SBT_OPTS = "--server\n";
    private static final String PROGRAM_JSON_FALLBACK =
            "{\n"
            + "  \"id\": \"${package_name}\",\n"
            + "  \"name\": \"${app_name}\",\n"
            + "  \"engine\": \"plugin\",\n"
            + "  \"versionCode\": 1,\n"
            + "  \"versionName\": \"1.0.0\",\n"
            + "  \"minSupport\": 1,\n"
            + "  \"extension\": [\n"
            + "    { \"point\": \"domain.menu\", \"class\": \"${plugin_class}\" }\n"
            + "  ]\n"
            + "}\n";

    @Override
    public String getName() {
        return TEMPLATE_NAME;
    }

    /**
     * 「新建工程」对话框里的模板图片：Scala 图标。
     *
     * <p>宿主 {@code TemplateAdapter} 在 {@code getPicture() == null} 时会退回通用模板
     * 图标，所以这里返回 null 就等于「创建工程」里没有 Scala 标识。
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
                "插件 id / 包名",
                "同时用作 program.json 的 id 与入口类所在包名，例如 " + DEFAULT_PACKAGE,
                DEFAULT_PACKAGE
        ));
        form.addInput(new FormInput(
                FormInput.Kind.STRING,
                KEY_APP_NAME,
                "插件名称",
                "结绳里显示的插件名",
                DEFAULT_NAME
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
        form.addInput(new FormInput(
                FormInput.Kind.STRING,
                KEY_PLUGIN_NOTE,
                "插件说明",
                "写入 program.json 的 description 与 AndroidManifest 的 plugin_note",
                DEFAULT_PLUGIN_NOTE
        ));
        return form;
    }

    @Override
    public void create(ScalaAndroidProject project, Form form, Project.OnProjectCreateListener listener) {
        try {
            String packageName = read(form, KEY_PACKAGE, DEFAULT_PACKAGE);
            String appName = read(form, KEY_APP_NAME, DEFAULT_NAME);
            String scalaVersion = read(form, KEY_SCALA_VERSION, DEFAULT_SCALA_VERSION);
            String minSdk = read(form, KEY_MIN_SDK, DEFAULT_MIN_SDK);
            String targetSdk = read(form, KEY_TARGET_SDK, DEFAULT_TARGET_SDK);
            String pluginNote = read(form, KEY_PLUGIN_NOTE, DEFAULT_PLUGIN_NOTE);

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
            replacements.put("version_code", "1");
            replacements.put("plugin_class", dotToSlash(packageName) + "/TestMenuAction");
            replacements.put("plugin_note", pluginNote);

            File root = project.getProjectDir();
            List<String> written = extractTemplate(project, root, replacements);
            moveSampleSource(project, packageName, "TestMenuAction.scala");
            // 关键文件兜底：缺了 plugins.sbt / build.properties / program.json，插件就不知道这是结绳插件工程
            ensureProjectFiles(root, written, replacements);
            writeSbtOpts(root);
            if (project.getLogger() != null) {
                project.getLogger().info("已按「结绳插件工程」模板生成工程：" + written.size() + " 个文件"
                        + " · 插件 id " + packageName + " · Scala " + scalaVersion
                        + " · minSdk " + minSdk + " / targetSdk " + targetSdk);
            }
            listener.onSuccess();
        } catch (Throwable error) {
            error.printStackTrace();
            listener.onFail("创建结绳 Scala 插件工程失败：" + error);
        }
    }

    /** 把模板铺到工程目录。zip 优先（AssetManager.list() 对嵌套目录不可靠，见 {@link ZipUtils}）。 */
    private List<String> extractTemplate(ScalaAndroidProject project, File root,
                                         Map<String, String> replacements) {
        Context context = AndroidPlugins.getContext(this);
        List<String> written = new ArrayList<>();
        if (ZipUtils.assetExists(context, ASSET_ZIP)) {
            try {
                File temp = new File(project.getChildFile("build/.template"), "scala_tiecode_plugin.zip");
                File parent = temp.getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    parent.mkdirs();
                }
                ZipUtils.copyAsset(context, ASSET_ZIP, temp);
                written = ZipUtils.unzip(temp, root);
                int replaced = ZipUtils.replacePlaceholders(root, replacements);
                if (project.getLogger() != null) {
                    project.getLogger().info("插件模板解包：" + written.size() + " 个文件，"
                            + replaced + " 个文件完成占位符替换");
                }
                return written;
            } catch (Throwable error) {
                if (project.getLogger() != null) {
                    project.getLogger().warning("插件模板 zip 解包失败，改用目录方式：" + error);
                }
            }
        }
        try {
            written = AssetTree.copy(context, ASSET_DIR, root, replacements);
        } catch (Throwable error) {
            if (project.getLogger() != null) {
                project.getLogger().warning("插件模板目录拷贝失败：" + error);
            }
        }
        return written;
    }

    /**
     * 保证 {@code project/build.properties} 与 {@code src/main/resources/META-INF/program.json}
     * 一定存在 —— 这两个文件决定"能不能被识别为结绳插件工程、能不能被结绳装载器读出来"。
     */
    private void ensureProjectFiles(File root, List<String> written, Map<String, String> replacements) {
        ensureFile(new File(root, "project/build.properties"), BUILD_PROPERTIES, written);
        ensureFile(new File(root, "src/main/resources/META-INF/program.json"),
                fillPlaceholders(PROGRAM_JSON_FALLBACK, replacements), written);
    }

    private String fillPlaceholders(String text, Map<String, String> replacements) {
        String result = text;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
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
    private void moveSampleSource(ScalaAndroidProject project, String packageName, String fileName)
            throws IOException {
        File sampleDir = project.getChildFile("src/main/scala/pkg");
        File sample = new File(sampleDir, fileName);
        if (!sample.isFile()) {
            return;
        }
        String relative = com.scala.android.project.ScalaSources.sourcePathUnder(
                com.scala.android.project.ScalaSources.ANDROID_SOURCE_ROOT, packageName, fileName);
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

    /** {@code com.example.myplugin} → {@code com.example}。 */
    private static String organizationOf(String packageName) {
        if (packageName == null) {
            return "com.example";
        }
        int index = packageName.lastIndexOf('.');
        return index > 0 ? packageName.substring(0, index) : packageName;
    }

    /** 包名转类路径（入口类在 {@code <包名>/TestMenuAction}）。 */
    private static String dotToSlash(String packageName) {
        return packageName == null ? "" : packageName.replace('.', '/');
    }
}
