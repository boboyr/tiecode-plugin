package com.goproject.plugin.lsp;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * gopls 相关的固定参数与 workspace 定位。
 *
 * <p>gopls 是面向模块的,workspace 必须指到含 {@code go.mod} 的那一层,
 * 否则它会对每个文件报 "not in a module",拿不到包级语义。
 */
public final class GoplsConfig {

    /** 语言 id:与插件语言注册键保持一致。 */
    public static final String LANGUAGE_ID = "go";

    /** gopls 的 LSP {@code languageId}。 */
    public static final String GOPLS_LANGUAGE_ID = "go";

    /** TCP 回环模式下使用的端口（仅在手动切到 {@link Transport#TCP} 时相关）。 */
    public static final int DEFAULT_PORT = 37374;

    /** 同设备回环地址。 */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /**
     * 模块代理。
     *
     * <p>默认 {@code https://goproxy.cn,direct}：Go 官方的 {@code proxy.golang.org}
     * 在部分网络下不可达，一旦取不到模块，gopls 就没法枚举第三方包，
     * "输入包名自动导包"这个能力会整体失效（表现为只有标准库能补全）。
     *
     * <p>末尾的 {@code ,direct} 表示代理取不到时回退直连。
     */
    private static volatile String goProxy = "https://goproxy.cn,direct";

    public static String getGoProxy() {
        return goProxy;
    }

    /** 自定义模块代理；传空则恢复官方默认（仅当官方源可达时才应这样做）。 */
    public static void setGoProxy(String proxy) {
        String trimmed = proxy == null ? "" : proxy.trim();
        goProxy = trimmed.isEmpty() ? "https://proxy.golang.org,direct" : trimmed;
    }

    // ------------------------------------------------------------------
    // 通道类型
    // ------------------------------------------------------------------

    /**
     * LSP 通道形态。
     *
     * <h3>默认用 Unix 文件路径</h3>
     * 现在 gopls 由插件自己拉起（见 {@link GoplsServerProcess}），与结绳<b>同 uid</b>，
     * 因此套接字文件放在 app 私有目录里双方都能访问，普通文件路径就够了。
     *
     * <p>相比之下：跑在 Termux 里时两者 uid 不同，私有目录互不可见，
     * 而 {@code /sdcard} 又不允许创建套接字文件（FUSE/sdcardfs 不支持
     * {@code mknod(S_IFSOCK)}），那时只能退到抽象命名空间。
     *
     * <p>路径过长时会自动退化为抽象命名空间（Linux 的 {@code sun_path} 上限 108 字节）。
     */
    public enum Transport {
        /** 文件系统路径：{@code gopls -listen="unix;/路径"}。自启动场景下的默认。 */
        UNIX_FILESYSTEM,
        /** 抽象命名空间：{@code gopls -listen="unix;@名字"}。路径超长或跨 uid 时使用。 */
        UNIX_ABSTRACT,
        /** TCP 回环：{@code gopls -listen=localhost:端口}。排查问题时可用。 */
        TCP
    }

    /** 默认通道：Unix 文件路径（地址由 {@link GoplsServerProcess} 在启动时确定）。 */
    private static volatile Transport transport = Transport.UNIX_FILESYSTEM;

    /**
     * 抽象命名空间的名字（仅路径超长时作为退路使用）。
     *
     * <p>客户端与服务端必须约定同一个名字，对应 {@code gopls -listen="unix;@dsh-gopls"}。
     */
    private static volatile String unixAbstractName = "dsh-gopls";

    /**
     * 文件系统命名空间下的套接字路径。
     *
     * <p>仅 {@link Transport#UNIX_FILESYSTEM} 使用。该路径必须同时满足两点：
     * 服务端（gopls，插件的子进程）能创建套接字文件，客户端（结绳）能 traverse 到它
     * 并对套接字文件有写权限。
     *
     * <p>自启动模式下两者 uid 相同，放在 app 私有目录即可满足；
     * 注意 {@code /sdcard} 不满足第一条（FUSE/sdcardfs 不支持 {@code mknod(S_IFSOCK)}）。
     */
    private static volatile String unixSocketPath = "";

    /** 当前通道类型。 */
    public static Transport getTransport() {
        return transport;
    }

    public static String getUnixAbstractName() {
        return unixAbstractName;
    }

    public static String getUnixSocketPath() {
        return unixSocketPath;
    }

    /** 切到 Unix 抽象命名空间（路径超长或需要跨 uid 时用）。 */
    public static void useUnixAbstract(String name) {
        if (name != null && !name.trim().isEmpty()) {
            unixAbstractName = name.trim();
        }
        transport = Transport.UNIX_ABSTRACT;
    }

    /** 切到 Unix 文件系统路径。 */
    public static void useUnixFilesystem(String path) {
        if (path != null && !path.trim().isEmpty()) {
            unixSocketPath = path.trim();
        }
        transport = Transport.UNIX_FILESYSTEM;
    }

    /** 切到 TCP 回环（排查问题时用，正常走 Unix 套接字）。 */
    public static void useTcp() {
        transport = Transport.TCP;
    }

    /**
     * 当前通道对应的 gopls 启动参数值。
     *
     * <p>自启动模式下这个值由 {@link GoplsServerProcess} 决定并注入；
     * 这里保留一份推导逻辑，用于日志与"手动启动排查"的场景。
     */
    public static String listenArgument() {
        switch (transport) {
            case UNIX_ABSTRACT:
                return "unix;@" + unixAbstractName;
            case UNIX_FILESYSTEM:
                return "unix;" + unixSocketPath;
            default:
                return DEFAULT_HOST + ":" + DEFAULT_PORT;
        }
    }

    /** 当前通道的人类可读描述，用于日志。 */
    public static String describeTransport() {
        switch (transport) {
            case UNIX_ABSTRACT:
                return "Unix 抽象命名空间 @" + unixAbstractName;
            case UNIX_FILESYSTEM:
                return "Unix 套接字文件 " + unixSocketPath;
            default:
                return "TCP " + DEFAULT_HOST + ":" + DEFAULT_PORT;
        }
    }

    /** 由 {@link GoplsServerProcess} 在启动成功后注入实际套接字路径。 */
    static void setResolvedUnixSocketPath(String path) {
        if (path != null && !path.trim().isEmpty()) {
            unixSocketPath = path.trim();
        }
    }

    /** 交给 gopls 的扩展名。 */
    public static final String[] EXTENSIONS = { "go", "mod", "sum", "work" };

    /** 建立 TCP 连接的超时毫秒。 */
    public static final int CONNECT_TIMEOUT_MILLIS = 3000;

    /** initialize 握手超时毫秒;首次索引可能十几秒。 */
    public static final int INIT_TIMEOUT_MILLIS = 180_000;

    /** 单次补全请求超时毫秒。 */
    public static final int COMPLETION_TIMEOUT_MILLIS = 60_000;

    private GoplsConfig() {
    }

    /**
     * gopls 的初始化选项。
     *
     * <p>只放稳妥项:gopls 会忽略不认识的字段,但填错值会影响行为。
     * {@code staticcheck} 刻意关掉——手机上很吃 CPU。
     */
    public static Object initializationOptions() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("completionDocumentation", Boolean.TRUE);
        options.put("usePlaceholders", Boolean.FALSE);
        // 自动补全尚未 import 的包名,对点号补全体验影响很大。
        options.put("completeUnimported", Boolean.TRUE);
        options.put("staticcheck", Boolean.FALSE);
        options.put("hoverKind", "FullDocumentation");
        options.put("symbolMatcher", "Fuzzy");
        options.put("diagnosticsDelay", "500ms");
        return options;
    }

    /** 决定 LSP workspace 目录。 */
    public static File resolveWorkspace(File startFrom) {
        if (startFrom == null) {
            return null;
        }
        File root = startFrom.isDirectory() ? startFrom : startFrom.getParentFile();
        if (root == null) {
            return null;
        }
        if (new File(root, "go.mod").isFile()) {
            return root;
        }
        File downward = findDownward(root, 3);
        if (downward != null) {
            return downward;
        }
        File upward = findUpward(root);
        return upward != null ? upward : root;
    }

    private static File findDownward(File root, int depth) {
        List<File> level = new ArrayList<>();
        level.add(root);
        for (int i = 0; i < depth; i++) {
            List<File> next = new ArrayList<>();
            for (File directory : level) {
                File[] children = directory.listFiles();
                if (children == null) {
                    continue;
                }
                java.util.Arrays.sort(children, new Comparator<File>() {
                    @Override
                    public int compare(File left, File right) {
                        return left.getName().compareToIgnoreCase(right.getName());
                    }
                });
                for (File child : children) {
                    if (!child.isDirectory() || shouldSkip(child.getName())) {
                        continue;
                    }
                    if (new File(child, "go.mod").isFile()) {
                        return child;
                    }
                    next.add(child);
                }
            }
            level = next;
            if (level.isEmpty()) {
                break;
            }
        }
        return null;
    }

    private static File findUpward(File start) {
        File current = start;
        for (int i = 0; i < 8 && current != null; i++) {
            File parent = current.getParentFile();
            if (parent == null) {
                break;
            }
            if (new File(parent, "go.mod").isFile()) {
                return parent;
            }
            current = parent;
        }
        return null;
    }

    private static boolean shouldSkip(String name) {
        return "build".equals(name) || ".git".equals(name)
                || "node_modules".equals(name) || ".cache".equals(name);
    }

    /** gopls 对 go.mod/go.sum/go.work 有专门的 languageId。 */
    public static String languageIdFor(String uri) {
        String extension = Uris.extensionOf(uri);
        switch (extension) {
            case ".mod":
                return "go.mod";
            case ".sum":
                return "go.sum";
            case ".work":
                return "go.work";
            case ".go":
                return GOPLS_LANGUAGE_ID;
            default:
                return null;
        }
    }

    /** 是否是应该交给 gopls 的文件名。 */
    public static boolean isGoFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".go") || lower.endsWith(".mod")
                || lower.endsWith(".sum") || lower.endsWith(".work");
    }
}
