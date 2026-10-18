package com.scala.android.task;

import android.app.Activity;
import android.content.Context;

import com.rapid.api.Platform;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.util.android.ContentUtils;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.sbt.SbtAndroidConfig;

import java.io.File;

/**
 * 「安装APK」：把 {@link BuildApkTask} 产出的 APK 交给系统安装器装上设备。
 *
 * <p>用的就是结绳本体 {@code DebugRunConfig} 的做法：
 * <pre>
 * ContentUtils.installApk(activity, apkPath);
 * </pre>
 * （android.content.Intent 走到系统安装界面，用户在弹窗里确认即可；
 * 结绳自己有没有 {@code REQUEST_INSTALL_PACKAGES} 权限决定了能否直接弹安装界面。）
 *
 * <p>装之前会看一下设备上是否已经装过同包名的应用，日志里区分"首次安装 / 覆盖安装"。
 */
public class InstallApkTask extends ScalaAndroidTask {

    public static final String ID = "installApk";
    public static final String NAME = "安装APK";

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
        File apk = new File(scalaProject.apkOutputDir(), scalaProject.getName() + ".apk");
        if (!apk.isFile() || apk.length() <= 0) {
            throw fail("没有找到可安装的 APK：" + apk
                    + "（先执行「构建APK」或「构建调试APK」）");
        }
        SbtAndroidConfig config = scalaProject.getAndroidConfig();
        Context context = AndroidPlugins.getContext(this);
        Activity activity = null;
        try {
            activity = Platform.getActivity();
        } catch (Throwable ignored) {
            // 拿不到 Activity 就用 Application Context
        }
        Context target = activity != null ? activity : context;

        String packageName = config == null ? null : config.applicationId;
        boolean installed = false;
        if (packageName != null) {
            try {
                installed = ContentUtils.checkAppInstalled(context, packageName);
            } catch (Throwable ignored) {
                // 查不到就当没装过
            }
        }
        info("安装 " + apk.getName() + "（" + (apk.length() / 1024) + " KB）"
                + " · 包名 " + (packageName == null ? "?" : packageName)
                + (installed ? " · 设备上已安装，将覆盖安装" : " · 首次安装")
                + " · " + (variant == null ? "release" : variant.getName()) + " 变体");

        try {
            ContentUtils.installApk(target, apk.getAbsolutePath());
        } catch (Throwable error) {
            throw fail("调起系统安装器失败：" + error);
        }
        info("已交给系统安装器，请在弹窗中确认");
    }
}
