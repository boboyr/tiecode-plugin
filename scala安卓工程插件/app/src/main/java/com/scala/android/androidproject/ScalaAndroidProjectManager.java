package com.scala.android.androidproject;

import android.graphics.drawable.Drawable;

import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.framework.project.file.DefaultOpeningOption;
import com.rapid.api.project.CodeProjectManager;
import com.rapid.api.project.ProjectManager;
import com.rapid.api.project.TypeDescription;
import com.rapid.util.android.ContextUtils;
import com.scala.android.R;
import com.scala.android.androidproject.template.ScalaAndroidTemplate;
import com.scala.android.androidproject.template.ScalaPluginProjectTemplate;
import com.scala.android.lang.SbtLanguageFactory;
import com.scala.android.sbt.SbtAndroidConfig;
import com.scala.android.lang.ScalaLanguage;
import com.scala.android.lang.ScalaLanguageFactory;
import com.scala.android.lang.ScalaScriptLanguageFactory;
import com.scala.android.lang.operator.ScalaOperatorProvider;
import com.scala.android.project.ScalaLanguageEngine;
import com.scala.android.project.option.ScalaClassCreationOption;
import com.scala.android.project.option.ScalaSingletonCreationOption;
import com.scala.android.sbt.SbtBuild;
import com.scala.android.sbt.SbtBuildScriptLoader;

import java.io.File;

/**
 * Scala 安卓工程管理器。
 *
 * <p>与结绳本体的 {@code studio.java.android.project.AndroidProjectManager} 同构：
 * 注册语言引擎、文件打开方式、文件模板与新建工程模板；
 * 「这是不是一个 Scala 安卓工程」的判定同样落在 {@link #isProjectDir(File)} 上。
 *
 * <p>工程类型：{@link #TYPE} / {@link #IDENTIFIER}，判定依据是
 * <b>存在 build.sbt（或 project/plugins.sbt）且有 src/main/scala 或 AndroidManifest.xml</b>，
 * 顺序上排在 Java 系列之后，不会误判标准安卓工程。
 */
public class ScalaAndroidProjectManager extends CodeProjectManager<ScalaAndroidProject> {

    public static final String TYPE = "Scala安卓工程";
    public static final String IDENTIFIER = "scala.android.sbt";

    public ScalaAndroidProjectManager() {
        super();
        this.fileManager = new ScalaAndroidFileManager();
        fileManager.addPSObjectOpeningOption(DefaultOpeningOption.INSTANCE,
                ".scala", ".sc", ".sbt", ".properties");

        fileManager.addPSObjectCreationOption(new ScalaClassCreationOption());
        fileManager.addPSObjectCreationOption(new ScalaSingletonCreationOption());

        // 引擎/运算符按"语言 id"注册，语言工厂按"扩展名"注册：
        // .scala 与 .sbt 两个工厂共用同一个 languageId = scala，
        // 于是 build.sbt 也能高亮 + 补全（之前只认领了 scala，所以 .sbt 什么都没有）。
        languageManager.registerEngine(ScalaLanguage.ID, ScalaLanguageEngine.getInstance());
        languageManager.registerLanguageFactory(new ScalaLanguageFactory());
        languageManager.registerLanguageFactory(new SbtLanguageFactory());
        languageManager.registerLanguageFactory(new ScalaScriptLanguageFactory());
        languageManager.registerOperatorProvider(ScalaLanguage.ID, new ScalaOperatorProvider());

        addTemplate(new ScalaAndroidTemplate());
        addTemplate(new ScalaPluginProjectTemplate());
    }

    @Override
    public String getIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public TypeDescription getTypeDescription() {
        Drawable drawable = ContextUtils.getDrawable(
                AndroidPlugins.getContext(this),
                R.drawable.ic_scala_project
        );
        return new TypeDescription(TYPE, TypeDescription.APPLICATION, drawable);
    }

    @Override
    public ScalaAndroidProject newProject() {
        return new ScalaAndroidProject();
    }

    /**
     * 识别「Scala 安卓工程」：
     * 必须有 sbt 构建脚本（{@code build.sbt} 或 {@code project/plugins.sbt}），
     * 并且有 Android 工程的特征（{@code src/main/AndroidManifest.xml} 或 {@code src/main/scala}）。
     * 带 {@code src/main/resources/META-INF/program.json} 的「结绳插件工程」也走同一套判定。
     */
    @Override
    public boolean isProjectDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        return isSbtProjectDir(dir);
    }

    /** 真正的判定逻辑：sbt 脚本 + Android 特征（结绳插件工程自动命中）。 */
    private static boolean isSbtProjectDir(File dir) {
        boolean hasSbt = new File(dir, "build.sbt").isFile()
                || new File(dir, "project/plugins.sbt").isFile();
        if (!hasSbt) {
            return false;
        }
        boolean androidLike = new File(dir, "src/main/AndroidManifest.xml").isFile()
                || new File(dir, "src/main/scala").isDirectory()
                || new File(dir, "src/main/res").isDirectory()
                || new File(dir, "src/main/resources/META-INF/program.json").isFile();
        if (!androidLike) {
            return false;
        }
        // 再确认脚本能被我们的解析器读出一个工程模型（读不动就交给别的管理器）
        try {
            SbtBuild build = SbtBuildScriptLoader.load(dir);
            return build != null && !build.projects.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 「结绳插件工程」的识别证据：有 {@code src/main/resources/META-INF/program.json}
     * 且有 sbt 脚本。用于加载日志里的提示行（判定本身由 {@link #isSbtProjectDir} 负责，
     * program.json 是那里的 Android 特征之一）。
     */
    private static boolean isTiecodePluginProjectDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        File programJson = new File(dir, "src/main/resources/META-INF/program.json");
        if (!programJson.isFile()) {
            return false;
        }
        File sbt = new File(dir, "build.sbt");
        return sbt.isFile() || new File(dir, "project/plugins.sbt").isFile();
    }

    /**
     * 打开工程：解析 build.sbt 并写进日志（真正的工作在任务链里做）。
     */
    @Override
    public void load(ScalaAndroidProject project, ProjectManager.LoadingPresentation presentation) {
        try {
            SbtBuild build = project.loadBuild();
            if (build == null) {
                presentation.onFailure(new IllegalStateException(
                        "没有找到 build.sbt / project/*.sbt"));
                return;
            }
            // 工程目录此时已经确定：让 Scala 补全能用上 sbt 依赖里的类
            project.installCompletionClasspath();
            if (project.getLogger() != null) {
                project.getLogger().info("Scala安卓工程：" + project.describe());
                if (build.sbtVersion != null) {
                    project.getLogger().info("sbt.version = " + build.sbtVersion
                            + " · 脚本 " + build.scriptFiles.size() + " 个");
                }
                for (String line : build.issueLines()) {
                    project.getLogger().warning(line);
                }
                // 结绳插件工程：打一行识别提示，让用户看到"这是插件不是普通应用"
                SbtAndroidConfig config = SbtAndroidConfig.from(build);
                if (config.isTiecodePlugin) {
                    project.getLogger().info("结绳插件工程识别：✓ 检测到 "
                            + com.scala.android.sbt.TiecodePluginKeys.PROGRAM_JSON_RELATIVE
                            + "，「构建结绳插件」/「调试结绳插件」运行配置已激活");
                }
            }
            presentation.onSuccess();
        } catch (Throwable error) {
            presentation.onFailure(error);
        }
    }
}
