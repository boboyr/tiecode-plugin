package com.goproject.plugin.tool;

import android.content.Context;

import com.rapid.api.logging.Logger;
import com.rapid.util.android.EnvUtils;

import java.io.File;
import java.io.IOException;

/**
 * 两套工具链的安装与定位。
 *
 * <p>1) <b>Go 工具链</b>：和 go语言控制台插件 完全一样的做法 —— 插件 assets 里自带
 * {@code go-toolchain/go1.27.1-aarch64.zip}，解压到私有环境目录 {@code <env>/go}。
 *
 * <p>2) <b>Android C 工具链</b>：这是和「只编可执行文件」的 Go 控制台插件最大的区别。
 * {@code go build -buildmode=c-shared}（产出能被 System.load + dlsym 的 libmain.so）
 * 在 Go 里强制要求外部(CGO)链接，也就是必须要有一个<b>面向 bionic 的 C 编译器</b>。
 * 这里复用结绳Linux工程插件同一份工具链：
 * {@code gcc-17.0.0-android-aarch64.tar.xz}，从公共读的阿里云 OSS 下载后解压到
 * {@code <env>/gcc-17.0.0}。
 */
public final class ToolchainManager {

    /** assets 里的 Go 工具链压缩包（与 go语言控制台插件 同一个文件）。 */
    public static final String GO_ASSET_ZIP = "go-toolchain/go1.27.1-aarch64.zip";

    /** OSS 上的 Android GCC 压缩包对象名（与结绳Linux工程插件同一个文件）。 */
    public static final String GCC_TAR_XZ = "gcc-17.0.0-android-aarch64.tar.xz";

    /**
     * 工具链存放地址（公共读，无需凭证，约 64MB）。
     * 直接拼 {@code <BASE>/<对象名>} 用 HTTP GET 取即可，不需要 OSS SDK。
     */
    public static final String TOOLCHAIN_BASE_URL = "https://toolchain.oss-cn-guangzhou.aliyuncs.com";

    /** 解压后的 GCC 目录名。 */
    public static final String GCC_DIR = "gcc-17.0.0";

    private ToolchainManager() {
    }

    // ---------------------------------------------------------------- 路径

    /** 私有环境目录：<应用私有目录>/linux 。外置存储禁止 dlopen，必须放这里。 */
    public static File envDir(Context context) {
        return EnvUtils.getEnvironmentDir(context, "linux");
    }

    public static File goRoot(Context context) {
        return new File(envDir(context), "go");
    }

    public static File goBinary(Context context) {
        return new File(new File(goRoot(context), "bin"), "go");
    }

    public static File gccRoot(Context context) {
        return new File(envDir(context), GCC_DIR);
    }

    public static File gccBinary(Context context) {
        return new File(new File(gccRoot(context), "bin"), "aarch64-linux-android-gcc");
    }

    public static File gxxBinary(Context context) {
        return new File(new File(gccRoot(context), "bin"), "aarch64-linux-android-g++");
    }

    /** 构建产物目录：与 SDLPluginPage / CopySoFiletask 约定一致。 */
    public static File tmpDir(Context context) {
        return new File(envDir(context), "tmp");
    }

    // ---------------------------------------------------------------- 安装

    public static boolean isGoInstalled(Context context) {
        return goBinary(context).exists();
    }

    public static boolean isGccInstalled(Context context) {
        return gccBinary(context).exists();
    }

    public static File ensureGo(Context context, Logger logger) throws IOException {
        if (isGoInstalled(context)) {
            return goBinary(context);
        }
        // 直接委托给 go语言控制台插件 那套 GoplsToolchain：
        // 用的是同一个 zip、同一个目录（<env>/linux/go），但它还会
        //   * 处理换版本时的清理（避免新旧文件混在一起）
        //   * 给 bin/ 和 pkg/tool/ 补可执行位（gopls 也要）
        //   * 建好 gopath / gocache / gotmp
        // 补全与构建共用同一份解压逻辑，行为不会分叉。
        logger.info("安装 Go 工具链（含 gopls）: " + GO_ASSET_ZIP);
        if (!com.goproject.plugin.lsp.GoplsToolchain.ensureInstalled(context)) {
            throw new IOException("Go 工具链安装失败，详见日志");
        }
        if (!isGoInstalled(context)) {
            throw new IOException("解压后仍未找到 go: " + goBinary(context));
        }
        logger.info("Go 工具链就绪: " + goBinary(context));
        return goBinary(context);
    }

    public static File ensureGcc(Context context, Logger logger) throws IOException {
        if (isGccInstalled(context)) {
            return gccBinary(context);
        }
        File env = envDir(context);
        if (!env.exists() && !env.mkdirs()) {
            throw new IOException("创建环境目录失败: " + env);
        }

        File tarXz = new File(env, GCC_TAR_XZ);
        logger.info("下载 Android GCC 工具链（约 64MB）: " + GCC_TAR_XZ);
        ToolchainDownloader.download(TOOLCHAIN_BASE_URL + "/" + GCC_TAR_XZ, tarXz);

        logger.info("解压 Android GCC 工具链 -> " + env);
        TarXzUtils.extractTarXz(tarXz.getAbsolutePath(), env.getAbsolutePath());

        makeExecutable(new File(gccRoot(context), "bin"));

        if (!isGccInstalled(context)) {
            throw new IOException("解压后仍未找到 C 编译器: " + gccBinary(context));
        }
        logger.info("Android GCC 就绪: " + gccBinary(context));
        // 压缩包用完即删，省几十 MB
        //noinspection ResultOfMethodCallIgnored
        tarXz.delete();
        return gccBinary(context);
    }

    // ---------------------------------------------------------------- 小工具

    public static void ensureDir(File dir) {
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    /** 递归补可执行位（AssetUtils 解压出来的文件默认没有 x 位）。 */
    public static void makeExecutable(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    makeExecutable(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.setExecutable(true, false);
    }
}
