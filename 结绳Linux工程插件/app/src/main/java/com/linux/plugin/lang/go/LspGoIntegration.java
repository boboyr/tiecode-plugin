package com.linux.plugin.lang.go;

import com.linux.plugin.lang.lsp.LspDiagnostics;
import com.linux.plugin.lang.lsp.LspEngines;
import com.linux.plugin.lang.lsp.LspLanguageEngine;
import com.linux.plugin.lang.lsp.LspServerRegistry;
import com.linux.plugin.lsp.LspClient;
import com.linux.plugin.lsp.LspLogger;
import com.linux.plugin.lsp.protocol.LspServerConfig;
import com.linux.plugin.lsp.protocol.LspSocketEndpoint;
import com.rapid.api.lang.factory.LanguageFactory;
import com.rapid.api.project.service.LanguageManager;

import java.io.File;
import java.util.Collections;

/**
 * gopls 接入入口：一行完成"注册 Go 语言 + 装配 LSP 引擎 + 连 Termux 上的 gopls"。
 *
 * <h3>Termux 侧</h3>
 * <pre>
 * # 守护进程模式：持久、可重连，客户端断开后仍会保留索引缓存。
 * # 必须写显式主机：新版本 gopls 拒绝 "-listen=:端口" 这种隐式绑定全部网卡的写法。
 * gopls -listen=localhost:37374
 * </pre>
 *
 * <h3>插件侧</h3>
 * <pre>
 * // 1) ProjectManager 构造函数里 —— 注册语言与引擎
 * LspGoIntegration.install(languageManager);
 * // 端口不是 37374 时：
 * LspGoIntegration.setListenAddress("localhost:38000");
 * LspGoIntegration.install(languageManager);
 *
 * // 2) 打开/关闭工程时
 * LspGoIntegration.openProject(project.getProjectDir());
 * LspGoIntegration.closeProject();
 * </pre>
 *
 * <h3>为什么不需要做路径映射</h3>
 * Termux 与结绳跑在同一台设备、同一个内核上，{@code /sdcard/...} 对 gopls 是
 * 真实可读路径。所以客户端直接发 {@code file:///sdcard/...}，gopls 就能打开对应文件——
 * 不像远程主机方案那样需要"客户端路径 ↔ 服务端路径"翻译。
 */
public final class LspGoIntegration {

    private static volatile GoLspContext context;
    private static volatile LspLanguageEngine engine;
    private static volatile File workspace;

    /** 端口在 {@code install} 之前就被设置时，先存这里，装配时再应用。 */
    private static volatile String pendingListenAddress;

    private LspGoIntegration() {
    }

    /**
     * 装配 gopls 通道，并把 Go 语言工厂注册进结绳。
     *
     * <p>幂等：重复调用只做一次真正的装配（避免每次打开工程都重启一次连接）。
     *
     * @param languageManager 结绳语言管理器；传 null 则只装配引擎、不注册语言
     * @return LSP 引擎实例；装配失败返回 null
     */
    public static synchronized LspLanguageEngine install(LanguageManager languageManager) {
        if (engine != null) {
            // 已经装过：只补注册语言工厂与引擎，不重启连接。
            if (languageManager != null) {
                registerLanguage(languageManager);
            }
            return engine;
        }

        GoLspContext created = new GoLspContext();
        // 关键顺序：先把之前 pending 的端口落实到 context，再 buildServerConfig()。
        // 否则 `setListenAddress(...)` → `install(...)` 这种调用序列会用默认端口固化配置。
        applyPendingAddress(created);
        created.logEndpointOnce();
        context = created;

        // 先把配置写进注册表，再装配引擎；端口等设置由此固化下来。
        engine = LspEngines.install(
                GoLspContext.LANGUAGE_ID,
                created,
                created.buildServerConfig());

        if (engine == null) {
            LspLogger.DEFAULT.error("gopls 引擎装配失败: " + GoLspContext.LANGUAGE_ID);
            return null;
        }
        if (languageManager != null) {
            registerLanguage(languageManager);
        }
        return engine;
    }

    /**
     * 覆盖 gopls 端点。
     *
     * <p>必须在首次 {@link #install} 之前调用；已经装过时为了拿到新端口，
     * 会先卸载旧会话再重新装配。
     *
     * @param listenAddress {@code host:port}，也就是 Termux 里
     *                      {@code gopls -listen=} 后面那一串（例如 {@code localhost:37374}）
     */
    public static synchronized void setListenAddress(String listenAddress) {
        if (listenAddress == null || listenAddress.trim().isEmpty()) {
            return;
        }
        String trimmed = listenAddress.trim();
        if (!LspSocketEndpoint.isValidListenAddress(trimmed)) {
            LspLogger.DEFAULT.warning("非法的 gopls 监听地址，已忽略: " + trimmed);
            return;
        }
        if (context == null) {
            // 还没装：记下来，等 install 时生效。
            pendingListenAddress = trimmed;
            return;
        }

        String host = LspSocketEndpoint.hostOf(trimmed);
        int port = LspSocketEndpoint.portOf(trimmed);
        LspServerConfig inUse = LspEngines.getServerConfig(GoLspContext.LANGUAGE_ID);
        boolean unchanged = inUse != null && inUse.getKind() == LspServerConfig.Kind.SOCKET
                && host.equals(inUse.getHost()) && port == inUse.getPort();
        if (unchanged) {
            // 端点没变，不要白重启一次连接（会丢掉 gopls 已经建好的索引）。
            return;
        }

        context.setEndpoint(host, port);
        // 重新装配：LspEngines.install 会先关旧会话再建新的，因此会带上新端点。
        engine = LspEngines.install(GoLspContext.LANGUAGE_ID, context, context.buildServerConfig());
        LspLogger.DEFAULT.info("gopls 端点已切换到 " + host + ":" + port + "，连接将在下次打开工程时重建");
    }

    /**
     * 注册 Go 语言工厂（语言 id 与文件类型都是 {@code "go"}）。
     *
     * <p>注册完立刻把结果打出来自检——补全能否工作完全依赖这一环，
     * 而"注册失败/注册错 id"在运行期是完全静默的（编辑器只会表现为不弹补全框）。
     * 这段自检能把问题钉死在注册层还是别的层。
     */
    private static void registerLanguage(LanguageManager languageManager) {
        try {
            GoLanguageFactory factory = new GoLanguageFactory();
            languageManager.registerLanguageFactory(factory);

            LanguageFactory found = languageManager.findLanguageFactory("go");
            com.rapid.api.lang.engine.LanguageEngine registered =
                    languageManager.getEngine(GoLanguage.ID);
            LspLogger.DEFAULT.info("Go 语言注册自检:"
                    + " 工厂语言id=" + factory.getLanguageId()
                    + " 关联扩展名=" + factory.getAssociateFileType()
                    + " | findLanguageFactory(go)=" + (found == null ? "null(未命中)" : "命中")
                    + " | getEngine(" + GoLanguage.ID + ")=" + (registered == null ? "null(未注册!)" : "已注册")
                    + " | 已注册引擎=" + languageManager.getEnginesMap().keySet());
        } catch (Throwable t) {
            LspLogger.DEFAULT.error("注册 Go 语言工厂失败", t);
        }
    }

    /**
     * 打开工程：把 workspace 指到含 {@code go.mod} 的目录，并在后台连上 gopls。
     *
     * @param projectDirectory 结绳工程根目录
     */
    public static void openProject(File projectDirectory) {
        GoLspContext current = context;
        if (current == null) {
            install(null);
            current = context;
        }
        if (current == null) {
            return;
        }
        // 已装好之后才调 setListenAddress 的情况：这里把新端点固化下来。
        applyPendingAddress(current);

        File resolved = GoLspContext.resolveWorkspace(projectDirectory);
        workspace = resolved;
        LspLogger.DEFAULT.info("gopls workspace: " + resolved);

        // 只动 Go 这一条会话：触发 LspEngines 里"workspace 变了才重连"的判断。
        // 传 null 作为工程根，保留 resolved 这个更准确的 go.mod 目录。
        LspEngines.openProject(null, Collections.singletonMap(
                GoLspContext.LANGUAGE_ID, resolved));
        GoLspDiagnostics.attach();
    }

    /** 关闭工程：断开 gopls 并丢弃旧诊断。 */
    public static void closeProject() {
        LspEngines.closeProject();
    }

    /** 彻底卸载 Go 语言会话（例如切换端口或工程类型时）。 */
    public static synchronized void uninstall() {
        GoLspDiagnostics.clear();
        LspEngines.shutdown(GoLspContext.LANGUAGE_ID);
        engine = null;
        context = null;
        workspace = null;
    }

    /**
     * 把待应用的端落实到 context 和注册表。
     *
     * <p>分成两步是刻意的：{@code LspEngines.install(...)} 会"先关旧会话再装配"，
     * 如果这里调它，就变成"先 shutdown 再 install"，而外层随后还要再加一个
     * session——纯属多余重启。
     * 所以这里只更新配置（注册表 + context），会话交接留给
     * {@link #install}（首次）或 {@code openProject} 之后的那一次重装。
     */
    private static void applyPendingAddress(GoLspContext target) {
        String pending = pendingListenAddress;
        if (pending == null || !LspSocketEndpoint.isValidListenAddress(pending)) {
            return;
        }
        String host = LspSocketEndpoint.hostOf(pending);
        int port = LspSocketEndpoint.portOf(pending);
        pendingListenAddress = null;
        if (host.equals(target.getHost()) && port == target.getPort()) {
            return;
        }
        target.setEndpoint(host, port);
        // 同步刷新注册表：引擎重连（或首次装配）时要用到新端点。
        LspServerRegistry.getInstance().register(target.buildServerConfig());
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 诊断缓存；用于把 gopls 的问题显示到自己的面板里。
     *
     * <p>等价于 {@code GoLspDiagnostics.store()}，这里保留一个入口方便从
     * gopls 接入口直接拿到。
     */
    public static LspDiagnostics diagnostics() {
        return GoLspDiagnostics.store();
    }

    /** 协议客户端；需要手动发 LSP 请求时用。 */
    public static LspClient client() {
        return LspEngines.getClient(GoLspContext.LANGUAGE_ID);
    }

    public static LspLanguageEngine engine() {
        return engine;
    }

    public static GoLspContext context() {
        return context;
    }

    /** 当前生效的 LSP workspace（含 go.mod 的那个目录）。 */
    public static File workspace() {
        return workspace;
    }

    /** gopls 是否已握手完成（可以用来提示用户"正在索引"。 */
    public static boolean isReady() {
        LspClient current = client();
        return current != null && current.isReady();
    }
}
