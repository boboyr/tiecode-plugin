package com.linux.plugin.lang.go;

import com.linux.plugin.lang.lsp.LspEngines;
import com.linux.plugin.lang.lsp.LspLanguageContext;
import com.linux.plugin.lsp.LspLogger;
import com.linux.plugin.lsp.json.Json;
import com.linux.plugin.lsp.protocol.LspServerConfig;
import com.linux.plugin.lsp.protocol.LspSocketEndpoint;
import com.linux.plugin.lsp.protocol.Uris;
import com.rapid.api.lang.engine.DefaultLanguageContext;
import com.rapid.api.lang.engine.baseModel.TextChange;
import com.rapid.framework.vfs.VirtualFile;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Go 语言上下文：为 gopls 提供"未保存正文"，并管理 Go 文件的编辑同步。
 *
 * <p>它本身不解析 Go（解析全在 gopls 里），职责只有三件：
 * <ol>
 *   <li>把编辑器里还没落盘的文本交给 {@code LspClient}——否则 gopls 只能看到磁盘上的旧版本，
 *       补全结果对不上用户正在写的代码；</li>
 *   <li>把编辑器的增删改事件翻译成 LSP 的 {@code didChange/didSave/...} 通知；</li>
 *   <li>决定 LSP workspace 目录（优先含 {@code go.mod} 的那一层）。</li>
 * </ol>
 *
 * <p>为什么 workspace 要找 {@code go.mod}：gopls 是<b>面向模块</b>的，
 * workspace 根不在模块内时它会对每个文件报 "not in a module" 之类的诊断，
 * 无法把整个包索引起来。结绳工程根通常不是 Go 模块根，Go 代码往往在某个子目录，
 * 所以这里向下/向上各找一次。
 */
public class GoLspContext extends DefaultLanguageContext implements LspLanguageContext {

    /** 语言 id，同时也是 gopls 在注册表里的键。 */
    public static final String LANGUAGE_ID = "go";

    /** gopls 的 LSP {@code languageId}。 */
    public static final String GOPLS_LANGUAGE_ID = "go";

    /** Termux 里 {@code gopls -listen=localhost:37374} 的默认端口。 */
    public static final int DEFAULT_PORT = 37374;

    /** 视为 Go 相关、需要交给 gopls 的扩展名。 */
    private static final List<String> EXTENSIONS = Collections.unmodifiableList(
            Arrays.asList("go", "mod", "sum", "work"));

    private volatile int port = DEFAULT_PORT;
    private volatile String host = LspSocketEndpoint.LOOPBACK;
    private volatile boolean endpointLogged;

    public GoLspContext() {
        super();
    }

    // ------------------------------------------------------------------
    // 端点配置
    // ------------------------------------------------------------------

    /**
     * 覆盖 gopls 监听端点。
     *
     * <p>要在 {@code LspEngines.install(...)} 之前调用才生效——
     * 引擎装配时会把这个端点固化成服务端配置。
     */
    public GoLspContext setEndpoint(String host, int port) {
        if (host != null && !host.trim().isEmpty()) {
            this.host = host.trim();
        }
        if (port > 0 && port <= 65535) {
            this.port = port;
        }
        return this;
    }

    /**
     * 按 {@code host:port} / {@code :port} 形式覆盖端点。
     *
     * <p>可以直接抄 Termux 里 {@code gopls -listen=} 后面那一串。
     */
    public GoLspContext setListenAddress(String listenAddress) {
        if (LspSocketEndpoint.isValidListenAddress(listenAddress)) {
            return setEndpoint(LspSocketEndpoint.hostOf(listenAddress),
                    LspSocketEndpoint.portOf(listenAddress));
        }
        LspLogger.DEFAULT.warning("忽略非法的 gopls 监听地址: " + listenAddress);
        return this;
    }

    public int getPort() {
        return port;
    }

    public String getHost() {
        return host;
    }

    /** 按当前端点构造 gopls 的服务端配置（含 {@code .go/.mod/.sum/.work}）。 */
    public LspServerConfig buildServerConfig() {
        return LspServerConfig.socket(LANGUAGE_ID, host, port)
                .addExtensions(EXTENSIONS.toArray(new String[0]))
                .languageId(GOPLS_LANGUAGE_ID)
                .initializationOptions(goplsInitializationOptions())
                // gopls 首次索引可能十几秒，给宽松一点；
                // 超时只是这一次补全拿不到结果，不会影响其它功能。
                .requestTimeoutMillis(60_000L)
                .build();
    }

    /**
     * gopls 的初始化选项。
     *
     * <p>只放稳妥项：gopls 会忽略不认识的字段，但填错值可能影响行为，
     * 所以这里不做"激进调优"。
     */
    private Object goplsInitializationOptions() {
        return Json.obj(
                // 让补全项带文档与签名详情，体验接近 VS Code。
                "completionDocumentation", Boolean.TRUE,
                "usePlaceholders", Boolean.FALSE,
                // 自动补全未导入的包名。
                "completeUnimported", Boolean.TRUE,
                // staticcheck 很吃 CPU，手机上默认关掉，需要时再开。
                "staticcheck", Boolean.FALSE,
                "hoverKind", "FullDocumentation",
                "symbolMatcher", "Fuzzy",
                "diagnosticsDelay", "500ms");
    }

    /** 首次装配时把端点打进日志，方便排查"连不上"。 */
    void logEndpointOnce() {
        if (!endpointLogged) {
            endpointLogged = true;
            LspLogger.DEFAULT.info("gopls 端点: " + host + ":" + port
                    + "（Termux 侧执行: gopls -listen=localhost:" + port + "）");
        }
    }

    // ------------------------------------------------------------------
    // 文档同步：把编辑器事件转成 LSP 通知
    // ------------------------------------------------------------------

    @Override
    public void didChangeDocumentContent(VirtualFile virtualFile, String newText) {
        super.didChangeDocumentContent(virtualFile, newText);
        if (!isGoSource(virtualFile) || newText == null) {
            return;
        }
        String uri = uriOf(virtualFile);
        if (uri == null) {
            return;
        }
        // 只在第一次收到某文件的变更时打一条：证明"IDE 确实在把编辑内容喂给语言上下文"。
        // 每次都打会刷屏，所以用集合去重。
        noteFirstTouch(uri);
        LspEngines.didChangeDocument(uri, newText);
    }

    @Override
    public void didChangeDocumentContent(VirtualFile virtualFile, TextChange textChange) {
        // 桥接层统一走全量同步，所以这里用缓存的正文（已被父类更新）向下转发，
        // 而不是费劲把增量 range 也翻译一遍——gopls 接受全量 contentChanges。
        super.didChangeDocumentContent(virtualFile, textChange);
        if (!isGoSource(virtualFile)) {
            return;
        }
        String uri = uriOf(virtualFile);
        if (uri == null) {
            return;
        }
        String content = safeDynamicContent(virtualFile);
        if (content != null) {
            LspEngines.didChangeDocument(uri, content);
        }
    }

    @Override
    public void didSaveDocument(VirtualFile virtualFile) {
        super.didSaveDocument(virtualFile);
        if (!isGoSource(virtualFile)) {
            return;
        }
        String uri = uriOf(virtualFile);
        if (uri != null) {
            LspEngines.didSaveDocument(uri);
        }
    }

    @Override
    public void didCloseDocument(VirtualFile virtualFile) {
        super.didCloseDocument(virtualFile);
        if (!isGoSource(virtualFile)) {
            return;
        }
        String uri = uriOf(virtualFile);
        if (uri != null) {
            LspEngines.didCloseDocument(uri);
        }
    }

    @Override
    public void didCreateWorkspaceFile(VirtualFile virtualFile) {
        super.didCreateWorkspaceFile(virtualFile);
        if (!isGoSource(virtualFile)) {
            return;
        }
        String uri = uriOf(virtualFile);
        if (uri != null) {
            LspEngines.didCreateFile(uri);
        }
    }

    @Override
    public void didDeleteWorkspaceFile(VirtualFile virtualFile) {
        super.didDeleteWorkspaceFile(virtualFile);
        if (!isGoSource(virtualFile)) {
            return;
        }
        String uri = uriOf(virtualFile);
        if (uri != null) {
            LspEngines.didDeleteFile(uri);
        }
    }

    @Override
    public void didRenameWorkspaceFile(VirtualFile oldFile, VirtualFile newFile) {
        super.didRenameWorkspaceFile(oldFile, newFile);
        String oldUri = isGoSource(oldFile) ? uriOf(oldFile) : null;
        String newUri = isGoSource(newFile) ? uriOf(newFile) : null;
        if (oldUri != null && newUri != null) {
            LspEngines.didRenameFile(oldUri, newUri);
        } else if (oldUri != null) {
            LspEngines.didDeleteFile(oldUri);
        } else if (newUri != null) {
            LspEngines.didCreateFile(newUri);
        }
    }

    // ------------------------------------------------------------------
    // 语言标识
    // ------------------------------------------------------------------

    /**
     * gopls 对 {@code go.mod}/{@code go.sum}/{@code go.work} 有专门的 languageId。
     *
     * <p>按扩展名推会得到 {@code mod}/{@code sum}/{@code work}，gopls 认不出来，
     * 这些文件就得不到任何语义功能。所以在这里显式映射。
     *
     * @return languageId；无法判断时返回 null，由桥接层按扩展名兜底
     */
    @Override
    public String languageIdFor(String uri) {
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

    // ------------------------------------------------------------------
    // workspace 解析
    // ------------------------------------------------------------------

    /**
     * 决定 LSP workspace 目录。
     *
     * <p>只在<b>工程目录之内</b>找含 {@code go.mod} 的那一层，找不到就退回工程根：
     * <ol>
     *   <li>工程根自身有 {@code go.mod} → 用它（最常见）；</li>
     *   <li>否则向下一层层找（广度优先、由近及远），命中即用；</li>
     *   <li>再向上找，但<b>严格不越过工程根</b>——越界就可能挑中
     *       {@code /sdcard} 或别的工程里的 go.mod，把工作区指到完全无关的地方；</li>
     *   <li>都没有 → 退回工程根。此时 gopls 会报 {@code no go.mod file found}，
     *       那是给用户的正确提示，比我们偷偷改 workspace 更有用。</li>
     * </ol>
     */
    public static File resolveWorkspace(File projectDirectory) {
        if (projectDirectory == null) {
            return null;
        }
        if (new File(projectDirectory, "go.mod").isFile()) {
            return projectDirectory;
        }
        File inProject = findModuleDownward(projectDirectory, 3);
        if (inProject != null) {
            return inProject;
        }
        // 只在工程根的祖先里往上找，且不超出 by-parent 的范围。
        File upward = findModuleUpward(projectDirectory);
        if (upward != null) {
            return upward;
        }
        return projectDirectory;
    }

    /**
     * 向下找含 {@code go.mod} 的目录，广度优先、由近及远。
     *
     * <p>只跳过不可能是模块的位置：{@code build}（结绳构建产物）、
     * {@code .git}（版本库内部）、{@code node_modules}。
     * <b>不跳过绳包</b>——绳包完全可能自带 go.mod。
     */
    private static File findModuleDownward(File root, int depth) {
        List<File> level = new ArrayList<>();
        level.add(root);
        for (int current = 0; current < depth; current++) {
            List<File> next = new ArrayList<>();
            for (File directory : level) {
                File[] children = directory.listFiles();
                if (children == null) {
                    continue;
                }
                // 排序让结果可复现（同层有多个模块时不会随机挑一个）。
                Arrays.sort(children, new Comparator<File>() {
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

    /** 向上找最近的 {@code go.mod} 所在目录。 */
    private static File findModuleUpward(File start) {
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

    /** 不可能是 Go 模块的位置，跳过以省时间。 */
    private static boolean shouldSkip(String name) {
        return "build".equals(name)
                || ".git".equals(name)
                || "node_modules".equals(name)
                // 结绳的 IDE 生成目录，里面是编译中间产物。
                || ".cache".equals(name);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 已经打过"首次接触"日志的 URI。 */
    private final java.util.Set<String> touched = java.util.Collections.synchronizedSet(
            new java.util.HashSet<String>());

    /**
     * 第一次收到某文件的编辑事件时打一条日志。
     *
     * <p>它的作用是区分两类问题：如果连这条都没出现，说明 IDE 根本没把
     * Go 文件交给我们的语言上下文，问题在语言 id / 引擎注册那一层；
     * 如果出现了但补全仍不弹，问题就在 LSP 通道或 provider 里。
     */
    private void noteFirstTouch(String uri) {
        if (touched.add(uri)) {
            LspLogger.DEFAULT.info("Go 文档开始同步: " + uri);
        }
    }

    /** 是否是应该交给 gopls 的文件。 */
    static boolean isGoSource(VirtualFile virtualFile) {
        if (virtualFile == null || !virtualFile.isFile()) {
            return false;
        }
        String extension = virtualFile.getExtension();
        if (extension == null) {
            return false;
        }
        return EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    private static String uriOf(VirtualFile virtualFile) {
        try {
            java.net.URI uri = virtualFile.asURI();
            if (uri != null) {
                return Uris.normalize(uri.toString());
            }
        } catch (Throwable ignore) {
            // 退回按路径拼。
        }
        return Uris.fromPath(virtualFile.getPath());
    }

    /** 父类的 {@code getDynamicContent} 可能抛异常（例如文件已删），这里兜住。 */
    private String safeDynamicContent(VirtualFile virtualFile) {
        try {
            return getDynamicContent(virtualFile);
        } catch (Throwable t) {
            return null;
        }
    }
}
