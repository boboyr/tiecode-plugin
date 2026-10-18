package com.scala.android.task;

import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.dependency.SbtDependencyResolver;
import com.scala.android.dependency.SbtResolutionResult;
import com.scala.android.sbt.SbtAndroidConfig;
import com.scala.android.sbt.SbtBuild;
import com.scala.android.sbt.SbtBuildScriptLoader;
import com.scala.android.setting.ScalaAndroidSettings;
import com.scala.android.util.HostPaths;

import java.io.File;
import java.util.List;

/**
 * 「解析并下载依赖」：本插件构建链的入口任务。
 *
 * <p>它一次做完两件事：
 * <ol>
 *   <li>用自研的 sbt 解析器读 {@code build.sbt / project/*.sbt / project/build.properties}，
 *       得到工程配置（这一步<b>不单独占一行任务</b>，日志里只在有诊断时提示）；</li>
 *   <li>把 {@code libraryDependencies} / {@code resolvers} 交给
 *       {@link SbtDependencyResolver} 下载到本地仓库并解包 AAR。</li>
 * </ol>
 * 解析器与下载器都是插件自己实现的（不依赖宿主的 Java 插件 / aether），
 * 缓存目录优先复用宿主的公共 Maven 目录。
 */
public class SbtResolveTask extends ScalaAndroidTask {

    public static final String ID = "sbtResolve";
    public static final String NAME = "解析并下载依赖";

    @Override
    public void prepare(Project p, ProjectVariant variant, Logger logger) {
        super.prepare(p, variant, logger);
        // 脚本可能刚被编辑过，每次都重新解析；这是整条链的入口，不做增量跳过。
        alwaysRun();
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void execute() throws TaskInterruptError {
        ScalaAndroidProject scalaProject = requireProject();

        // ---- 1. 解析 build.sbt（静默；只有诊断才提示） ----
        SbtBuild build = SbtBuildScriptLoader.load(scalaProject.getProjectDir());
        if (build == null) {
            throw fail("工程目录下没有找到任何 .sbt 构建脚本："
                    + scalaProject.getProjectDir());
        }
        SbtAndroidConfig config = SbtAndroidConfig.from(build);
        scalaProject.setSbtBuild(build);
        scalaProject.setAndroidConfig(config);
        for (String line : build.issueLines()) {
            warn(line);
        }
        info(config.summary());

        // ---- 2. 依赖解析下载 ----
        if (config.dependencies.isEmpty()) {
            info("libraryDependencies 为空，只收集 lib/ 下的本地 jar");
        }

        File cacheDir = HostPaths.resolverCacheDir(AndroidPlugins.getContext(this));
        File unpackDir = HostPaths.aarUnpackDir(AndroidPlugins.getContext(this));
        SbtDependencyResolver.Options options = new SbtDependencyResolver.Options();
        options.cacheDir = cacheDir;
        options.unpackDir = unpackDir;
        options.offline = ScalaAndroidSettings.offline();
        options.includeTest = ScalaAndroidSettings.includeTestDependencies();
        options.includeProvided = true;
        options.useDefaultRepositories =
                !ScalaAndroidSettings.MODE_SCRIPT_ONLY.equals(ScalaAndroidSettings.repositoryMode());
        options.extraRepositories.addAll(ScalaAndroidSettings.extraRepositories());
        if (!options.useDefaultRepositories) {
            // 脚本没写 resolvers 时也要有个中央仓库兜底
            options.extraRepositories.add("https://repo1.maven.org/maven2/");
        }

        SbtDependencyResolver resolver = new SbtDependencyResolver(options,
                message -> info("  " + message));
        SbtResolutionResult result = resolver.resolve(config);
        scalaProject.setResolution(result);

        info(result.summary());
        for (String failure : result.failures) {
            warn("  " + failure);
        }
    }
}
