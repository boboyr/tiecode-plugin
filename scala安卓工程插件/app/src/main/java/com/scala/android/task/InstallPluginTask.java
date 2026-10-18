package com.scala.android.task;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.rapid.api.Platform;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.framework.data.SystemPath;
import com.rapid.util.android.ContentUtils;
import com.rapid.util.file.FileUtils;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.util.HostPaths;

import java.io.File;

/**
 * 「调试结绳插件」：把 {@link BuildPluginTask} 产出的 <b>.rmx</b> 安装进结绳宿主
 * 的公共 {@code Programs} 目录（与结绳本体 {@code DebugQiPluginConfig} 同一条路），
 * 然后<b>重启结绳</b>让装载器在新会话里加载插件。
 *
 * <h3>与结绳本体 {@code DebugQiPluginConfig} 的对应</h3>
 * <ul>
 *   <li>安装位置：{@code <publicRoot>/Programs/<插件id>/}（本任务按 {@code program.json}
 *       里的 {@code id} 建子目录，与 {@code ProgramInstallerImpl} 的
 *       {@code getPublicPath(PublicPath.PROGRAMS)} 同一份目录）；</li>
 *   <li>杀宿主：{@code IntentKeys.ACTION_SHUTDOWN} 广播（和宿主 {@code SystemReceiver} 一致）；</li>
 *   <li>首装：{@code am start -n com.tiecode.develop/…Splash -d file:///<rmx>}
 *       （宿主 {@code ImportingPluginHandler} 处理该 Uri，走
 *       {@code ProgramManager.installProgram} 真正安装）；
 *       覆盖：直接把 rmx 拷进 Programs 目录再重启宿主。</li>
 * </ul>
 *
 * <p>顺序与宿主一致：先 {@code killRunningDebugPlatform()}（停掉正在跑的结绳，
 * 避免它持有旧插件），再决定首装 / 覆盖，最后用显式 Intent 跳回结绳。
 */
public class InstallPluginTask extends ScalaAndroidTask {

    public static final String ID = "installPlugin";
    public static final String NAME = "安装结绳插件";

    /** 结绳宿主正式版包名（旧世代 {@code com.tiecode.develop}）。 */
    public static final String HOST_PACKAGE = "com.tiecode.develop";
    /**
     * 宿主包名候选：正式版优先，其次是内测版。
     *
     * <p>结绳 4.6-beta 走 {@code com.tiecode.develop.alpha}，两者在设备上可以并存；
     * 只认正式版的话，只装了内测版的机器上「调试结绳插件」会直接失败。
     */
    public static final String[] HOST_PACKAGES = {
            HOST_PACKAGE,
            "com.tiecode.develop.alpha"
    };
    /** 宿主启动页（{@code Splash} 活动，负责装载器启动）。 */
    public static final String HOST_SPLASH = "com.qiplat.develop.page.module.splash.Splash";
    /** 宿主广播：让运行中的结绳停下来。 */
    public static final String ACTION_SHUTDOWN = "app.intent.action.SHUTDOWN";

    /** 插件包里清单的位置（宿主装载器认的路径）。 */
    private static final String IN_APK_PROGRAM_JSON = "META-INF/program.json";

    @Override
    public void prepare(com.rapid.api.project.Project p,
                        com.rapid.api.project.ProjectVariant variant,
                        com.rapid.api.logging.Logger logger) {
        super.prepare(p, variant, logger);
        // 安装是"动作"而不是"产物"，每次都执行
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
        // 1) 拿到刚构建好的 rmx：build/outputs/plugins/<variant>/<工程名>.rmx
        //    （不能用 buildPlugin.getOutputPath() —— 那是 build/outputs/buildPlugin/<variant>/）
        File rmx = builtPluginFile();
        if (!rmx.isFile() || rmx.length() <= 0) {
            throw fail("没有找到可安装的 rmx：" + rmx
                    + "（先执行「构建结绳插件」）");
        }

        Context context = AndroidPlugins.getContext(this);

        // 2) 校验宿主（结绳）装在这台设备上，否则后面广播 / 显式 Intent 都没意义。
        //    正式版包名优先，没装正式版就退到内测版（结绳 4.6-beta 用 .alpha 包名）。
        String hostPackage = resolveHostPackage(context);
        if (hostPackage == null) {
            throw fail("这台设备上没检测到结绳宿主（"
                    + String.join(" / ", HOST_PACKAGES) + "），无法安装调试插件");
        }

        // 3) 读 program.json 拿插件 id（装载器在 Programs/<id>/ 下找文件）
        String pluginId = readPluginId(scalaProject);
        if (pluginId == null) {
            pluginId = hostPackage; // 没有就退到包名，至少目录名稳定
        }

        // 4) 停掉运行中的结绳，避免它持有旧插件
        killHost(context, hostPackage);

        // 5) 决定"首装"还是"覆盖"
        File programsRoot = publicProgramsRoot(context, hostPackage);
        File installDir = new File(programsRoot, pluginId);
        File targetFile = new File(installDir, rmx.getName());
        if (installDir.exists() && listPluginFiles(installDir).length > 0) {
            info("检测到目标结绳已安装该插件，将会覆盖旧插件并重启");
            installDir.mkdirs();
            try {
                FileUtils.copyFile(rmx, targetFile);
            } catch (Throwable error) {
                throw fail("覆盖 rmx 失败：" + error);
            }
            // 关键：宿主启动时读的是**插件目录里的** program.json（安装时由宿主写下的一份
            // 清单副本 + "file" 字段），不是 rmx 里的那份。只覆盖 rmx 而不刷新它，
            // 结绳重启后仍然按旧清单加载（改了 id/扩展点/入口类都不生效）。
            refreshInstalledManifest(rmx, installDir, targetFile);
            deleteStalePluginFiles(installDir, targetFile);
            relaunchHost(context, hostPackage);
        } else {
            info("检测到目标结绳未安装该插件，将进行首次安装");
            installPluginViaFileUri(context, rmx, hostPackage);
        }
        info("rmx 已安装到 " + targetFile.getAbsolutePath()
                + " · 插件 id " + pluginId
                + " · 宿主将重启加载新插件");
    }

    /**
     * 首次安装：用 {@code file://} Uri 让宿主的 {@code ImportingPluginHandler}
     * 走 {@code ProgramManager.installProgram} 真正安装（与结绳本体一致）。
     */
    private void installPluginViaFileUri(Context context, File rmx, String hostPackage)
            throws TaskInterruptError {
        Intent intent = new Intent();
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setPackage(hostPackage);
        intent.setClassName(hostPackage, HOST_SPLASH);
        intent.setData(Uri.fromFile(rmx));
        try {
            context.startActivity(intent);
        } catch (Throwable error) {
            throw fail("调起宿主首装 rmx 失败：" + error);
        }
    }

    /** 覆盖安装后重启宿主（不传 Uri，让宿主直接从 Programs 目录读新文件）。 */
    private void relaunchHost(Context context, String hostPackage) throws TaskInterruptError {
        Intent intent = new Intent();
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setPackage(hostPackage);
        intent.setClassName(hostPackage, HOST_SPLASH);
        try {
            context.startActivity(intent);
        } catch (Throwable error) {
            throw fail("重启宿主失败：" + error);
        }
    }

    /** 广播 {@code ACTION_SHUTDOWN} 让运行中的宿主停下来（与宿主 {@code SystemReceiver} 一致）。 */
    private void killHost(Context context, String hostPackage) {
        Intent intent = new Intent();
        intent.setPackage(hostPackage);
        intent.setAction(ACTION_SHUTDOWN);
        try {
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
            // 宿主本来就没在跑就没事
        }
    }

    /** 设备上实际装着哪个宿主：正式版优先。都没有返回 null。 */
    private static String resolveHostPackage(Context context) {
        for (String candidate : HOST_PACKAGES) {
            try {
                if (ContentUtils.checkAppInstalled(context, candidate)) {
                    return candidate;
                }
            } catch (Throwable ignored) {
                // 查询本身失败（context 不正常）：跳过这个候选
            }
        }
        return null;
    }

    /**
     * 结绳公共 Programs 目录：{@code <publicRoot>/Programs}。
     *
     * <p>优先用 {@code Platform.getSystemPath().getPublicPath(PublicPath.PROGRAMS)}
     * （宿主装程序就用的这份目录），拿不到时按宿主惯例兜底
     * （{@code /sdcard/Tiecode/Programs} 或 {@code /sdcard/QiStudio/Programs}）。
     */
    private File publicProgramsRoot(Context context, String hostPackage) {
        try {
            SystemPath systemPath = Platform.getSystemPath();
            if (systemPath != null) {
                String value = systemPath.getPublicPath(com.rapid.api.ext.file.PublicPath.PROGRAMS);
                if (value != null && !value.isEmpty()) {
                    return new File(value);
                }
            }
        } catch (Throwable ignored) {
            // 落到兜底
        }
        File publicRoot = HostPaths.publicRoot();
        if (publicRoot != null) {
            return new File(publicRoot, com.rapid.api.ext.file.PublicPath.PROGRAMS);
        }
        // 彻底拿不到公共路径：退到宿主 Android data 目录（旧世代插件安装位）
        File legacy = new File(new File(android.os.Environment.getExternalStorageDirectory(),
                "Android/data/" + hostPackage + "/files/plugins"), "Programs");
        return legacy;
    }

    /**
     * 用新 rmx 里的 {@code META-INF/program.json} 刷新插件目录里的清单副本。
     *
     * <p>宿主装载插件时读的是 {@code <Programs>/<插件id>/program.json}
     * （{@code ProgramManagerImpl} 枚举目录 + {@code ProgramModel.FILE_PROPERTY}），
     * 这份文件是宿主**首次安装时**写的快照，多一个 {@code "file"} 字段指向真正的
     * 插件包。覆盖安装只换 rmx 而不刷新它，宿主就会一直按旧快照加载：
     * 改了 {@code extension} / 入口类 / 版本号都不生效。
     */
    private void refreshInstalledManifest(File rmx, File installDir, File pluginFile)
            throws TaskInterruptError {
        String manifest;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(rmx)) {
            java.util.zip.ZipEntry entry = zip.getEntry(IN_APK_PROGRAM_JSON);
            if (entry == null) {
                throw fail("rmx 里没有 " + IN_APK_PROGRAM_JSON + "，无法刷新插件清单：" + rmx);
            }
            try (java.io.InputStream input = zip.getInputStream(entry)) {
                manifest = com.rapid.util.file.StreamUtils.readStream(input);
            }
        } catch (TaskInterruptError error) {
            throw error;
        } catch (Throwable error) {
            throw fail("读取 rmx 里的插件清单失败：" + error);
        }
        try {
            FileUtils.writeFile(com.scala.android.util.PluginManifest.withFileName(
                    manifest, pluginFile.getName()),
                    new File(installDir, "program.json"));
        } catch (Throwable error) {
            throw fail("写入插件目录的 program.json 失败：" + error);
        }
        info("已刷新插件清单：" + new File(installDir, "program.json").getAbsolutePath()
                + "（宿主下次启动按这份加载）");
    }

    /**
     * 删掉插件目录里除本次产物以外的旧插件包（改了工程名之后旧 {@code .rmx} 会残留，
     * 宿主/用户在目录里看到两个包容易装回旧的）。
     */
    private void deleteStalePluginFiles(File installDir, File keep) {
        File[] files = listPluginFiles(installDir);
        for (File file : files) {
            if (keep != null && file.getName().equals(keep.getName())) {
                continue;
            }
            if (file.delete()) {
                info("已清理旧插件包：" + file.getName());
            }
        }
    }

    /** 读 {@code src/main/resources/META-INF/program.json} 里的 {@code id}。 */
    private String readPluginId(ScalaAndroidProject scalaProject) {
        File json = scalaProject.getChildFile(com.scala.android.sbt.TiecodePluginKeys.PROGRAM_JSON_RELATIVE);
        if (!json.isFile()) {
            return null;
        }
        try {
            String text = FileUtils.readFile(json);
            return extractId(text);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 纯文本提取 {@code "id": "..."}（不用 JSON 库，避免多一个依赖）。 */
    private static String extractId(String text) {
        if (text == null) {
            return null;
        }
        int key = text.indexOf("\"id\"");
        if (key < 0) {
            return null;
        }
        int colon = text.indexOf(':', key + 3);
        if (colon < 0) {
            return null;
        }
        int open = text.indexOf('"', colon + 1);
        if (open < 0) {
            return null;
        }
        int close = text.indexOf('"', open + 1);
        if (close < 0 || close <= open) {
            return null;
        }
        return text.substring(open + 1, close);
    }

    /** 安装目录里现存的 rmx / tpq 文件（判断"已装过"的依据）。 */
    private static File[] listPluginFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return new File[0];
        }
        java.util.List<File> result = new java.util.ArrayList<>();
        for (File file : files) {
            if (file.isFile()
                    && (file.getName().endsWith(".rmx") || file.getName().endsWith(".tpk"))) {
                result.add(file);
            }
        }
        return result.toArray(new File[0]);
    }
}
