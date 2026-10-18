package com.goproject.plugin.lsp;

import android.content.Context;

import com.goproject.plugin.lsp.bridge.GoplsLogger;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.util.android.AssetUtils;
import com.rapid.util.android.EnvUtils;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Go 工具链定位与安装：把 assets 里的 zip 解到宿主提供的 Linux 环境目录，并给出 gopls 的运行环境。
 *
 * <h3>目录布局</h3>
 * <pre>
 * &lt;app filesDir&gt;/env/linux/          ← EnvUtils.getEnvironmentDir(ctx, "linux")
 * ├── go/                             ← Go 发行版（GOROOT）
 * │   ├── bin/go                      编译用
 * │   ├── bin/gopls                   LSP 服务端（本插件自己启动）
 * │   └── pkg/tool/android_arm64/     go 调用的内部工具
 * ├── gopath/                         GOPATH
 * ├── gocache/                        GOCACHE（必须可写，否则 go list 会失败）
 * ├── gotmp/                          GOTMPDIR
 * └── gopls.sock                      LSP 的 Unix 域套接字
 * </pre>
 *
 * <h3>为什么自己启动 gopls 就能用 Unix 套接字</h3>
 * 之前 gopls 跑在 Termux（另一个 app）里，两者 uid 不同：
 * 套接字文件无论放谁的私有目录，对方都访问不了；而 {@code /sdcard} 又无法创建套接字文件
 * （FUSE/sdcardfs 不支持 {@code mknod(S_IFSOCK)}）。
 * 现在 gopls 是插件的子进程，与结绳<b>同 uid</b>，放进 app 私有目录双方都能访问，
 * 于是普通文件路径就够了，不再需要考虑抽象命名空间与跨 app 权限。
 */
public final class GoplsToolchain {

    /** 工具链 zip 在 assets 里的位置。 */
    public static final String ASSET_ZIP = "go-toolchain/go1.27.1-aarch64.zip";

    /** 环境目录名（与既有编译器保持一致，复用同一份解压结果）。 */
    private static final String ENVIRONMENT_NAME = "linux";

    /** Unix 套接字文件名。 */
    private static final String SOCKET_FILE_NAME = "gopls.sock";

    /** 安装完成标记。 */
    private static final String INSTALL_LOCK = ".install.lock";

    /** 记录已解压的 zip 名；版本变化时据此清理旧文件。 */
    private static final String VERSION_MARKER = ".toolchain-version";

    /**
     * Linux 的 {@code sun_path} 上限：108 字节（含结尾 NUL），因此路径最长 107。
     * 超了就必须改用抽象命名空间，否则 {@code bind} 会返回 ENAMETOOLONG。
     */
    private static final int MAX_SOCKET_PATH_BYTES = 107;

    private GoplsToolchain() {
    }

    /** 环境根目录。 */
    public static File environmentDir(Context context) {
        return EnvUtils.getEnvironmentDir(context, ENVIRONMENT_NAME);
    }

    /** 插件上下文（宿主提供）。 */
    public static Context pluginContext() {
        return AndroidPlugins.getContext(GoplsToolchain.class);
    }

    /** GOROOT。 */
    public static File goRoot(Context context) {
        return new File(environmentDir(context), "go");
    }

    /** gopls 可执行文件。 */
    public static File goplsBinary(Context context) {
        return new File(new File(goRoot(context), "bin"), "gopls");
    }

    /** go 可执行文件。 */
    public static File goBinary(Context context) {
        return new File(new File(goRoot(context), "bin"), "go");
    }

    /** Unix 套接字路径。 */
    public static File socketFile(Context context) {
        return new File(environmentDir(context), SOCKET_FILE_NAME);
    }

    /**
     * 套接字路径是否可以作为文件路径使用。
     *
     * <p>超长时调用方应退化为抽象命名空间（不占文件系统，无长度限制）。
     */
    public static boolean isSocketPathUsable(Context context) {
        String path = socketFile(context).getAbsolutePath();
        return path.getBytes().length <= MAX_SOCKET_PATH_BYTES;
    }

    /**
     * 工具链是否已就绪。
     *
     * <p>两个条件都必需：
     * <ol>
     *   <li>gopls 二进制存在且可执行——只看 {@code .install.lock} 会在换版本后误判；</li>
     *   <li><b>记录的版本与当前 zip 一致</b>——这一条同样不能省。
     *       若只检查二进制，则"新版本覆盖解压到旧版本目录"之后 gopls 确实存在，
     *       于是 {@code isInstalled()} 返回 true，{@link #install} 再也不会被调用，
     *       清理逻辑也就永远不执行，两代 stdlib 源码会一直叠在一起。</li>
     * </ol>
     *
     * <p>混装的症状很好认：编译时报 stdlib 符号重复定义或类型不匹配，例如
     * {@code internal/strconv: uint64pow10 redeclared}、
     * {@code undefined: decimalSlice}。这类错误与自己的代码无关，
     * 是工具链本身被污染了。
     */
    public static boolean isInstalled(Context context) {
        File gopls = goplsBinary(context);
        if (!gopls.isFile() || gopls.length() <= 0 || !gopls.canExecute()) {
            return false;
        }
        return ASSET_ZIP.equals(readVersion(new File(environmentDir(context), VERSION_MARKER)));
    }

    /**
     * 强制清空重装工具链。
     *
     * <p>用于修复已经污染的解压目录：删掉整个 {@code go/} 后重新解压。
     * 耗时较长（解压 92MB），必须在后台线程调用。
     */
    public static boolean reinstall(Context context) {
        File environment = environmentDir(context);
        File goroot = new File(environment, "go");
        GoplsLogger.get().info("开始重装 Go 工具链，先清除 " + goroot);
        if (goroot.isDirectory() && !deleteRecursively(goroot)) {
            GoplsLogger.get().warning("旧的 go/ 目录未能完全删除，重装可能仍有残留");
        }
        File marker = new File(environment, VERSION_MARKER);
        if (marker.exists()) {
            marker.delete();
        }
        return install(context);
    }

    /**
     * 解压工具链并给关键二进制加可执行位。
     *
     * <p>解压 92MB、1.3 万个条目需要几秒，调用方应在后台线程执行。
     *
     * @return 是否安装成功（以 gopls 可用为准）
     */
    public static boolean install(Context context) {
        File environment = environmentDir(context);
        try {
            // 换过 zip 版本时清掉旧的 go/ 目录。
            // 直接覆盖解压会留下上一版多出来的文件（例如旧版的 pkg/tool 条目），
            // 混在一起可能让 go 或 gopls 行为异常，且极难排查。
            purgeIfVersionChanged(environment);

            GoplsLogger.get().info("开始解压 Go 工具链: " + ASSET_ZIP + " → " + environment);
            AssetUtils.unZipAssetsFolder(context, ASSET_ZIP, environment.getPath());

            // go 自身、以及它调用的内部工具都需要可执行位；
            // gopls 也必须显式加上，否则 ProcessBuilder 会报 Permission denied。
            makeExecutable(new File(goRoot(context), "bin"));
            makeExecutable(new File(new File(goRoot(context), "pkg"), "tool/android_arm64"));

            // 提前建好 go 需要的可写目录，避免首次 go list 失败。
            ensureDir(new File(environment, "gopath"));
            ensureDir(new File(environment, "gocache"));
            ensureDir(new File(environment, "gotmp"));

            new File(environment, INSTALL_LOCK).createNewFile();
            writeVersion(environment);

            if (!isInstalled(context)) {
                GoplsLogger.get().error("工具链解压后仍未找到可执行的 gopls: "
                        + goplsBinary(context).getAbsolutePath());
                return false;
            }
            GoplsLogger.get().info("Go 工具链就绪: gopls=" + goplsBinary(context).getAbsolutePath());
            return true;
        } catch (Throwable t) {
            GoplsLogger.get().error("解压 Go 工具链失败", t);
            return false;
        }
    }

    /** 确保工具链已安装；需要时同步解压。 */
    public static boolean ensureInstalled(Context context) {
        if (isInstalled(context)) {
            return true;
        }
        return install(context);
    }

    /**
     * 给 gopls 准备的进程环境变量。
     *
     * <p>要点：
     * <ul>
     *   <li>{@code PATH} 必须含 {@code go/bin}——gopls 要靠 {@code go list} 解析包；</li>
     *   <li>{@code GOROOT} 指向解压出来的发行版，否则 go 会去猜路径而失败；</li>
     *   <li>{@code GOTOOLCHAIN=local} 很关键：不设的话，当 go.mod 声明的版本高于本机时，
     *       go 会尝试<b>下载另一个工具链</b>，在离线环境下会直接报错；</li>
     *   <li>缓存目录都放在私有目录内，保证可写。</li>
     * </ul>
     */
    public static Map<String, String> buildEnvironment(Context context) {
        File environment = environmentDir(context);
        File goroot = goRoot(context);

        Map<String, String> env = new HashMap<>();
        String binDir = new File(goroot, "bin").getAbsolutePath();

        String inheritedPath = System.getenv("PATH");
        env.put("PATH", inheritedPath == null || inheritedPath.isEmpty()
                ? binDir
                : binDir + File.pathSeparator + inheritedPath);

        env.put("GOROOT", goroot.getAbsolutePath());
        env.put("GOPATH", new File(environment, "gopath").getAbsolutePath());
        // 显式指定模块缓存，不要依赖 GOPATH 的默认推导：
        // gopls 的"未导入包补全"是<b>扫描模块缓存</b>来枚举候选包的，
        // 缓存为空时第三方包（如 github.com/ulikunitz/xz 的 xz）永远不会出现在候选里。
        env.put("GOMODCACHE", new File(environment, "gopath/pkg/mod").getAbsolutePath());
        env.put("GOCACHE", new File(environment, "gocache").getAbsolutePath());
        env.put("GOTMPDIR", new File(environment, "gotmp").getAbsolutePath());
        // gopls 会把配置与索引缓存放在这里。
        env.put("HOME", environment.getAbsolutePath());
        env.put("GO111MODULE", "on");
        // 必须设 GOPROXY：默认的 proxy.golang.org 在部分网络下不可达，
        // 那样模块永远下不下来，第三方包也就补全不出来。
        env.put("GOPROXY", GoplsConfig.getGoProxy());
        // 允许 go 在需要时更新 go.mod（默认 -mod=readonly 会直接拒绝）。
        env.put("GOFLAGS", "-mod=mod");
        // 禁止 go 因版本不匹配去联网下载工具链。
        env.put("GOTOOLCHAIN", "local");
        // 关闭 Google Analytics 类的遥测上传（离线环境下只会拖慢启动）。
        env.put("GOPLS_TELEMETRY", "off");

        // 把生效值记进日志：模块下载失败时这几个变量是首要怀疑对象。
        GoplsLogger.get().info("gopls 环境: GOROOT=" + goroot
                + " GOPATH=" + env.get("GOPATH")
                + " GOMODCACHE=" + env.get("GOMODCACHE")
                + " GOPROXY=" + env.get("GOPROXY")
                + " GOFLAGS=" + env.get("GOFLAGS"));
        return env;
    }

    /** gopls 的日志文件（进程 stdout/stderr 重定向到这里）。 */
    public static File serverLogFile(Context context) {
        return new File(environmentDir(context), "gopls-server.log");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 若记录的版本与当前 zip 名不一致，删掉旧的解压结果。
     *
     * <p>删除失败不阻断流程——顶多是沿用旧文件，比直接不装好。
     */
    private static void purgeIfVersionChanged(File environment) {
        File marker = new File(environment, VERSION_MARKER);
        String recorded = readVersion(marker);
        if (ASSET_ZIP.equals(recorded)) {
            return;
        }
        File goroot = new File(environment, "go");
        if (goroot.isDirectory()) {
            GoplsLogger.get().info("工具链版本变化(" + recorded + " → " + ASSET_ZIP
                    + ")，清理旧的 go/ 目录");
            if (!deleteRecursively(goroot)) {
                GoplsLogger.get().warning("旧的 go/ 目录未能完全删除，可能存在残留文件");
            }
        }
        if (marker.exists()) {
            marker.delete();
        }
    }

    private static String readVersion(File marker) {
        if (!marker.isFile()) {
            return "";
        }
        try {
            byte[] bytes = new byte[(int) Math.min(marker.length(), 256L)];
            java.io.InputStream in = new java.io.FileInputStream(marker);
            try {
                int read = in.read(bytes);
                return read <= 0 ? "" : new String(bytes, 0, read, "UTF-8").trim();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeVersion(File environment) {
        try {
            java.io.OutputStream out =
                    new java.io.FileOutputStream(new File(environment, VERSION_MARKER));
            try {
                out.write(ASSET_ZIP.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable t) {
            // 记不住版本只会导致下次多清一次，不影响功能。
        }
    }

    private static boolean deleteRecursively(File target) {
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return !target.exists() || target.delete();
    }

    private static void makeExecutable(File directory) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            child.setExecutable(true, false);
        }
    }

    private static void ensureDir(File directory) {
        if (!directory.isDirectory()) {
            directory.mkdirs();
        }
    }
}
