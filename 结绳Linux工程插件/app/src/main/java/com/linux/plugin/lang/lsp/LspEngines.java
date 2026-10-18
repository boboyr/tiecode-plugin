package com.linux.plugin.lang.lsp;

import com.linux.plugin.lang.lsp.bridge.LspBridgeContext;
import com.linux.plugin.lsp.LspClient;
import com.linux.plugin.lsp.LspLogger;
import com.linux.plugin.lsp.protocol.LspServerConfig;
import com.rapid.api.lang.engine.DefaultLanguageContext;
import com.rapid.api.lang.engine.LanguageEngine;
import com.rapid.api.project.service.LanguageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 语言引擎管理器：把 {@link LspServerRegistry} 里的配置变成可用的
 * {@link LspLanguageEngine}，并按语言 id 复用。
 *
 * <p>工程接入点就一条：
 * <pre>
 * // 在 ProjectManager 的构造函数里
 * LspEngines.install("cpp", new CppLanguageContext());
 *
 * // 或者更省事：按注册表里的服务端批量装
 * LspEngines.installAll();
 * </pre>
 *
 * <p>启动策略：{@link #install} 立刻返回，服务端进程在后台线程拉起。
 * 这样打开工程不会被 clangd 的索引初始化卡住；在就绪之前，
 * 各 provider 会安静地返回空结果。
 */
public final class LspEngines {

    /** 语言 id → 引擎。 */
    private static final Map<String, LspLanguageEngine> ENGINES = new ConcurrentHashMap<>();

    /** 语言 id → 协议客户端。 */
    private static final Map<String, LspClient> CLIENTS = new ConcurrentHashMap<>();

    /** 语言 id → 会话。 */
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    /** 拉起服务端用单线程就够：一个进程几十毫秒，串行更可控。 */
    private static final ExecutorService STARTER = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "lsp-starter");
            thread.setDaemon(true);
            return thread;
        }
    });

    /** 一个语言 id 下的"客户端 + 桥接上下文 + 引擎"三件套。 */
    private static final class Session {

        final String languageId;
        final LspServerConfig config;
        final LspClient client;
        final LspBridgeContext bridge;
        final LspLanguageEngine engine;
        volatile File workspaceDirectory;

        /**
         * 当前连接正在使用的 workspace 目录。
         *
         * <p>null 表示"没连"；非 null 表示"已连上或正在连，且用的是这个目录"。
         * 它同时承担两个职责，少一个字段、少一处不一致：
         * <ul>
         *   <li>幂等——同一 workspace 重复 openProject 不会重启服务端；</li>
         *   <li>纠错——workspace 真的变了（换了工程/换了模块根）才重连，
         *       避免服务端拿着旧目录做索引，给出完全对不上的补全。</li>
         * </ul>
         */
        volatile File activeWorkspace;

        Session(String languageId, LspServerConfig config, LspClient client,
                LspBridgeContext bridge, LspLanguageEngine engine) {
            this.languageId = languageId;
            this.config = config;
            this.client = client;
            this.bridge = bridge;
            this.engine = engine;
        }
    }

    private static volatile LspLogger logger = LspLogger.DEFAULT;

    /** 当前工程的根目录；用于 {@link #install} 之后又装配进新语言时补一次连接。 */
    private static volatile File currentWorkspace;

    /** 是否处于"工程已打开"状态。 */
    private static volatile boolean projectActive;

    private LspEngines() {
    }

    /** 替换日志出口（需在任何 {@link #install} 之前调用）。 */
    public static void setLogger(LspLogger value) {
        logger = value == null ? LspLogger.DEFAULT : value;
    }

    public static LspLogger getLogger() {
        return logger;
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    /**
     * 为某个语言 id 装配 LSP 引擎。
     *
     * <p>若该语言 id 已装过，会先把旧会话<strong>关掉</strong>再重建——
     * 因为工程切换时旧的 workspace 已经失效，保留半死的连接只会产生错误结果。
     *
     * @param languageId      语言 id（同时是注册表里的键）
     * @param languageContext 结绳语言上下文；为 null 时用 {@link DefaultLanguageContext}
     * @return 引擎实例；注册表里没有该语言的服务端时返回 null
     */
    public static LspLanguageEngine install(String languageId, DefaultLanguageContext languageContext) {
        if (languageId == null || languageId.trim().isEmpty()) {
            return null;
        }
        String key = languageId.toLowerCase(Locale.ROOT);
        LspServerConfig config = LspServerRegistry.getInstance().findByLanguageId(key);
        if (config == null) {
            logger.warning("没有为语言 " + key + " 注册 LSP 服务端，已跳过");
            return null;
        }

        shutdown(key);

        LspClient client = new LspClient(config, logger);
        LspBridgeContext bridge = new LspBridgeContext(logger);
        bridge.setClient(client);
        DefaultLanguageContext context = languageContext == null
                ? new DefaultLanguageContext()
                : languageContext;
        // 注意要把解析后的 context 传进引擎：它同时决定了"未保存正文"从哪取。
        LspLanguageEngine engine = new LspLanguageEngine(context, bridge);
        Session session = new Session(key, config, client, bridge, engine);
        SESSIONS.put(key, session);
        CLIENTS.put(key, client);
        ENGINES.put(key, engine);

        // 工程已经打开的情况下又装了新语言（例如打开 Go 支持）：
        // 不能等下一次 openProject，否则这门语言一直连不上。
        if (projectActive) {
            openSession(session, workspaceFor(session, currentWorkspace, null));
        }
        return engine;
    }

    /**
     * 装配 LSP 引擎，并<b>用传入的配置覆盖注册表</b>。
     *
     * <p>给"配置在运行期才知道"的场景用，典型是 gopls 的端口：
     * 端口由 Termux 里 {@code gopls -listen=localhost:PORT} 决定，代码里写死不合理。
     * 这里先覆盖注册表再装配，保证后续 {@link #install} 拿到的是同一份配置。
     *
     * @param serverConfig 覆盖用的服务端配置，可为 null（等价于不覆盖）
     * @return 装配好的引擎；语言 id 为空或注册表里没有时返回 null
     */
    public static LspLanguageEngine install(String languageId,
                                           DefaultLanguageContext languageContext,
                                           LspServerConfig serverConfig) {
        if (serverConfig != null) {
            LspServerRegistry.getInstance().register(serverConfig);
        }
        return install(languageId, languageContext);
    }

    /**
     * 按注册表批量装配，每个语言用一个通用 {@code DefaultLanguageContext}。
     *
     * <p>工程目录此时还不知道，等 {@link #openProject(File)} 时再补上。
     *
     * @return 实际装配的语言 id 列表
     */
    public static List<String> installAll() {
        List<String> installed = new ArrayList<>();
        Set<String> languageIds = new LinkedHashSet<>();
        for (LspServerConfig config : LspServerRegistry.getInstance().all()) {
            String languageId = config.getLanguageId();
            languageIds.add(languageId == null || languageId.isEmpty() ? config.getId() : languageId);
        }
        for (String languageId : languageIds) {
            if (install(languageId, null) != null) {
                installed.add(languageId);
            }
        }
        return installed;
    }

    /**
     * 装配并把引擎注册进结绳的语言管理器。
     *
     * <p>语言 id 必须与 {@code LanguageFactory.getLanguageId()} 保持一致：
     * 编辑器是"扩展名 → 工厂 → 语言 id → 引擎"这样定位引擎的
     * （见 {@code LanguageManager.getEngineByExtension}）。
     * 所以工程里既然已有 {@code CppLanguageFactory} 声明了 {@code "cpp"}，
     * 这里注册 {@code "cpp"} 就能被 C/C++ 文件用上。
     *
     * @return 装配好的引擎；该语言没有注册服务端时返回 null
     */
    public static LspLanguageEngine installAndRegister(LanguageManager languageManager,
                                                      String languageId,
                                                      DefaultLanguageContext context) {
        LspLanguageEngine engine = install(languageId, context);
        if (engine != null && languageManager != null) {
            languageManager.registerEngine(languageId, engine);
            logger.info("LSP 引擎已注册到语言管理器: " + languageId);
        }
        return engine;
    }

    // ------------------------------------------------------------------
    // 工程生命周期
    // ------------------------------------------------------------------

    /**
     * 打开工程：设定 workspace 并（后台）拉起所有已装配的服务端。
     *
     * @param projectDirectory 工程根目录
     */
    public static void openProject(final File projectDirectory) {
        openProject(projectDirectory, null);
    }

    /**
     * 打开工程，并允许按语言覆盖 workspace 目录。
     *
     * <p>{@code workspaceOverrides} 的键是小写语言 id，值是该项目语言应该用的
     * workspace 根。典型用途：gopls 需要指向含 {@code go.mod} 的那一层目录，
     * 而不是结绳工程根。没有覆盖项时退回 {@code projectDirectory}。
     *
     * @param projectDirectory   结绳工程根目录
     * @param workspaceOverrides 语言 id → workspace 目录，可为 null
     */
    public static void openProject(final File projectDirectory, final Map<String, File> workspaceOverrides) {
        currentWorkspace = projectDirectory;
        projectActive = true;
        for (final Session session : SESSIONS.values()) {
            openSession(session, workspaceFor(session, projectDirectory, workspaceOverrides));
        }
    }

    /**
     * 选定某个会话该用的 workspace 目录。
     *
     * @param session            目标会话
     * @param projectDirectory   结绳工程根
     * @param workspaceOverrides 语言 id → workspace 覆盖表，可为 null
     * @return 实际使用的目录
     */
    private static File workspaceFor(Session session, File projectDirectory,
                                    Map<String, File> workspaceOverrides) {
        if (workspaceOverrides != null) {
            File override = workspaceOverrides.get(session.languageId);
            if (override != null) {
                return override;
            }
            // 有覆盖表但本语言不在表里：沿用会话自己已定的目录
            // （调用方可能用 null 表示"别碰其它语言"）。
            if (session.workspaceDirectory != null) {
                return session.workspaceDirectory;
            }
        }
        return projectDirectory;
    }

    /**
     * 在后台拉起某个会话的连接。
     *
     * <p>后台执行是为了不让服务端的索引初始化卡住界面线程——
     * 在就绪之前，各 provider 会安静地返回空结果。
     */
    private static void openSession(final Session session, final File workspace) {
        session.workspaceDirectory = workspace;
        session.bridge.setWorkspaceDirectory(workspace);

        File active = session.activeWorkspace;
        if (active != null && sameWorkspace(active, workspace)) {
            // 同一个 workspace 重复打开：不重启进程（gopls 的索引很贵）。
            return;
        }
        if (active != null) {
            // workspace 变了：必须换一条连接，否则服务端还在索引旧目录。
            logger.info("LSP workspace 变化(" + session.languageId + "): " + active + " → " + workspace);
            session.activeWorkspace = null;
            try {
                session.client.shutdown();
            } catch (Throwable ignore) {
                // 旧连接可能已经断了，忽略。
            }
        }
        session.activeWorkspace = workspace;
        STARTER.execute(new Runnable() {
            @Override
            public void run() {
                startSession(session, workspace);
            }
        });
    }

    /** 两个目录是否指向同一位置（都用绝对路径比较，避免 "/a/b/" 与 "/a/b" 被当成不同）。 */
    private static boolean sameWorkspace(File left, File right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.getAbsolutePath().equals(right.getAbsolutePath());
    }

    /** 关闭工程：断开所有连接并清空诊断。 */
    public static void closeProject() {
        projectActive = false;
        currentWorkspace = null;
        for (Session session : SESSIONS.values()) {
            session.activeWorkspace = null;
            session.bridge.getDiagnostics().clear();
            session.bridge.setWorkspaceDirectory(null);
            session.workspaceDirectory = null;
            try {
                session.client.shutdown();
            } catch (Throwable t) {
                logger.warning("关闭 LSP 会话失败(" + session.languageId + "): " + t.getMessage());
            }
        }
    }

    /** 彻底卸载：关闭并按语言 id 移除会话。 */
    public static void shutdown(String languageId) {
        if (languageId == null) {
            return;
        }
        String key = languageId.toLowerCase(Locale.ROOT);
        Session session = SESSIONS.remove(key);
        CLIENTS.remove(key);
        ENGINES.remove(key);
        if (session == null) {
            return;
        }
        session.bridge.getDiagnostics().clear();
        try {
            session.client.shutdown();
        } catch (Throwable t) {
            logger.warning("关闭 LSP 会话失败(" + key + "): " + t.getMessage());
        }
    }

    /** 关闭全部会话（应用退出时调用）。 */
    public static void shutdownAll() {
        for (String languageId : new ArrayList<>(SESSIONS.keySet())) {
            shutdown(languageId);
        }
        ENGINES.clear();
        CLIENTS.clear();
        SESSIONS.clear();
    }

    private static void startSession(Session session, File projectDirectory) {
        try {
            session.client.start(projectDirectory);
            logger.info("LSP 服务端已启动: " + session.languageId + " → " + session.config);
        } catch (Throwable t) {
            logger.error("启动 LSP 服务端失败(" + session.languageId + " → " + session.config + ")", t);
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 取已装配的引擎；未装配返回 null。 */
    public static LspLanguageEngine getEngine(String languageId) {
        return languageId == null ? null : ENGINES.get(languageId.toLowerCase(Locale.ROOT));
    }

    /** 取协议客户端；未装配返回 null。 */
    public static LspClient getClient(String languageId) {
        return languageId == null ? null : CLIENTS.get(languageId.toLowerCase(Locale.ROOT));
    }

    public static LanguageEngine getLanguageEngine(String languageId) {
        return getEngine(languageId);
    }

    /**
     * 取某个语言当前实际生效的服务端配置。
     *
     * <p>给"运行期改端口"这类场景用：改之前先问一句现在的值，避免无谓重连。
     *
     * @return 会话的配置；未装配返回 null
     */
    public static LspServerConfig getServerConfig(String languageId) {
        Session session = languageId == null ? null : SESSIONS.get(languageId.toLowerCase(Locale.ROOT));
        return session == null ? null : session.config;
    }

    /** 取桥接上下文（诊断缓存等从这里拿）。 */
    public static LspBridgeContext getBridge(String languageId) {
        Session session = languageId == null ? null : SESSIONS.get(languageId.toLowerCase(Locale.ROOT));
        return session == null ? null : session.bridge;
    }

    /** 已装配的语言 id 快照。 */
    public static List<String> installedLanguageIds() {
        return Collections.unmodifiableList(new ArrayList<>(SESSIONS.keySet()));
    }

    /**
     * 把某个文件的变更转发给对应语言的服务端。
     *
     * <p>工程侧的 {@code LanguageContext} 回调里调一次即可，
     * 走这里就不用让调用方自己认语言 id。
     *
     * @param pathOrUri 文件路径或 URI
     */
    public static void didChangeDocument(String pathOrUri, String newContent) {
        Session session = sessionFor(pathOrUri);
        if (session == null) {
            return;
        }
        session.client.didChangeDocument(pathOrUri, newContent);
    }

    /** 转发"文档保存"。 */
    public static void didSaveDocument(String pathOrUri) {
        Session session = sessionFor(pathOrUri);
        if (session != null) {
            session.client.didSaveDocument(pathOrUri);
        }
    }

    /** 转发"文档关闭"。 */
    public static void didCloseDocument(String pathOrUri) {
        Session session = sessionFor(pathOrUri);
        if (session != null) {
            session.client.didCloseDocument(pathOrUri);
        }
    }

    /** 转发"文件新建/删除/重命名"。 */
    public static void didCreateFile(String pathOrUri) {
        Session session = sessionFor(pathOrUri);
        if (session != null) {
            session.client.didCreateFile(pathOrUri);
        }
    }

    public static void didDeleteFile(String pathOrUri) {
        Session session = sessionFor(pathOrUri);
        if (session != null) {
            session.client.didDeleteFile(pathOrUri);
        }
    }

    public static void didRenameFile(String oldPathOrUri, String newPathOrUri) {
        // 新旧两个路径可能落在不同服务端上，两边都要通知。
        Session before = sessionFor(oldPathOrUri);
        if (before != null) {
            before.client.didRenameFile(oldPathOrUri, newPathOrUri);
            return;
        }
        Session after = sessionFor(newPathOrUri);
        if (after != null) {
            after.client.didRenameFile(oldPathOrUri, newPathOrUri);
        }
    }

    /** 按文件路径找到负责它的会话。 */
    private static Session sessionFor(String pathOrUri) {
        if (pathOrUri == null) {
            return null;
        }
        String extension = com.linux.plugin.lsp.protocol.Uris.extensionOf(pathOrUri);
        LspServerConfig config = LspServerRegistry.getInstance().findByExtension(extension);
        if (config == null) {
            return null;
        }
        String languageId = config.getLanguageId();
        if (languageId == null || languageId.isEmpty()) {
            languageId = config.getId();
        }
        return SESSIONS.get(languageId.toLowerCase(Locale.ROOT));
    }
}
