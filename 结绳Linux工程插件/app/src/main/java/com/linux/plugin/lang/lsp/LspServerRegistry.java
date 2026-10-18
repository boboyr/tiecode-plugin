package com.linux.plugin.lang.lsp;

import com.linux.plugin.lsp.json.Json;
import com.linux.plugin.lsp.protocol.LspServerConfig;
import com.linux.plugin.lsp.protocol.Uris;
import com.rapid.api.Platform;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LSP 服务端注册表：登记"哪种文件该由哪个服务端处理"。
 *
 * <p>桥接层只认这个注册表，因此换服务端（clangd / pyright / 自研）不需要改代码，
 * 注册一条配置即可。
 *
 * <p>支持配置来源（优先级从低到高）：
 * <ol>
 *   <li>{@link #installDefaults()} 内置默认（clangd 等，按常见路径探测）；</li>
 *   <li>{@link #register(String, LspServerConfig)} 运行时注册；</li>
 *   <li>{@link #loadFromJson(String)} 从插件 assets 里的 JSON 读取，便于不改代码调参。</li>
 * </ol>
 *
 * <p>JSON 结构示例（{@code lsp.json}）：
 * <pre>
 * {
 *   "servers": [
 *     {"id": "clangd", "kind": "stdio", "command": ["clangd", "--stdio"],
 *      "extensions": ["c", "cpp", "h", "hpp"], "workingDirectory": "/data/.../bin"},
 *     {"id": "tie-lsp", "kind": "socket", "host": "127.0.0.1", "port": 9333,
 *      "extensions": ["t"], "languageId": "tie"}
 *   ]
 * }
 * </pre>
 */
public final class LspServerRegistry {

    private static final LspServerRegistry INSTANCE = new LspServerRegistry();

    private final Map<String, LspServerConfig> byLanguageId = new java.util.concurrent.ConcurrentHashMap<>();

    private LspServerRegistry() {
    }

    public static LspServerRegistry getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // 注册与查询
    // ------------------------------------------------------------------

    /** 注册（或覆盖）一个服务端配置。 */
    public void register(LspServerConfig config) {
        if (config == null) {
            return;
        }
        byLanguageId.put(keyOf(config), config);
    }

    /** 按语言 id 注销。 */
    public void unregister(String languageId) {
        if (languageId != null) {
            byLanguageId.remove(languageId.toLowerCase(Locale.ROOT));
        }
    }

    /** 清空全部注册。 */
    public void clear() {
        byLanguageId.clear();
    }

    /** 按语言 id 查找。 */
    public LspServerConfig findByLanguageId(String languageId) {
        if (languageId == null) {
            return null;
        }
        return byLanguageId.get(languageId.toLowerCase(Locale.ROOT));
    }

    /**
     * 按文件扩展名查找服务端。
     *
     * @param extension 形如 {@code .cpp} 或 {@code cpp}
     */
    public LspServerConfig findByExtension(String extension) {
        String normalized = LspServerConfig.normalizeExtension(extension);
        if (normalized.isEmpty()) {
            return null;
        }
        LspServerConfig best = null;
        for (LspServerConfig config : byLanguageId.values()) {
            if (!config.handlesExtension(normalized)) {
                continue;
            }
            // 多个服务端都能处理时，挑扩展名列表更专一的那个（条目少的优先）。
            if (best == null || config.getExtensions().size() < best.getExtensions().size()) {
                best = config;
            }
        }
        return best;
    }

    /** 按文件路径/URI 的扩展名查找。 */
    public LspServerConfig findByPath(String pathOrUri) {
        return findByExtension(Uris.extensionOf(pathOrUri));
    }

    /** 已注册服务端快照。 */
    public List<LspServerConfig> all() {
        return Collections.unmodifiableList(new ArrayList<>(byLanguageId.values()));
    }

    // ------------------------------------------------------------------
    // 默认服务端与配置加载
    // ------------------------------------------------------------------

    /**
     * 登记内置默认服务端。
     *
     * <p>两类处理方式不同：
     * <ul>
     *   <li><b>stdio</b>（clangd）：只在可执行文件真的存在时登记，
     *       否则会留下一条永远启动失败的配置，日志噪声很大；</li>
     *   <li><b>socket</b>（gopls）：端口无法预检，直接登记。
     *       连不上时只是这一门语言不可用，不会影响其它语言。</li>
     * </ul>
     *
     * @param searchRoots 额外搜索可执行文件的目录
     */
    public void installDefaults(String... searchRoots) {
        String clangd = findExecutable("clangd", searchRoots);
        if (clangd != null) {
            register(LspServerConfig
                    .stdio("cpp", clangd, "--stdio")
                    .addExtensions("c", "cc", "cpp", "cxx", "c++", "h", "hh", "hpp", "hxx", "inl", "ipp")
                    .workingDirectory(new File(clangd).getParent())
                    .requestTimeoutMillis(30_000L)
                    .build());
        }

        // gopls：Termux 里 `gopls -listen=localhost:37374` 起的守护进程。
        // 同一台设备，所以走回环地址，并且完全不需要路径映射。
        register(LspServerConfig
                .socket(GOPS_LANGUAGE_ID, GOPS_HOST, GOPS_PORT)
                .addExtensions("go", "mod", "sum", "work")
                .languageId(GOPS_LANGUAGE_ID)
                .requestTimeoutMillis(60_000L)
                .build());
    }

    /** gopls 默认端点；与 {@code lang.go.GoLspContext} 保持一致。 */
    public static final String GOPS_HOST = "127.0.0.1";
    public static final int GOPS_PORT = 37374;
    public static final String GOPS_LANGUAGE_ID = "go";

    /**
     * 从 JSON 文本载入配置，返回成功注册的数量。
     *
     * @throws com.linux.plugin.lsp.json.JsonException JSON 非法时抛出
     */
    public int loadFromJson(String json) throws com.linux.plugin.lsp.json.JsonException {
        Map<String, Object> root = Json.readObject(json);
        List<Object> servers = Json.array(root, "servers");
        int count = 0;
        for (Object entry : servers) {
            Map<String, Object> object = Json.object(entry);
            if (object == null) {
                continue;
            }
            String id = Json.string(object, "id", null);
            if (id == null) {
                continue;
            }
            String kind = Json.string(object, "kind", "stdio").toLowerCase(Locale.ROOT);
            LspServerConfig.Builder builder;
            if ("socket".equals(kind) || "tcp".equals(kind)) {
                builder = LspServerConfig.socket(id,
                        Json.string(object, "host", "127.0.0.1"),
                        Json.integer(object, "port", 0));
            } else {
                List<Object> command = Json.array(object, "command");
                List<String> parts = new ArrayList<>(command.size());
                for (Object part : command) {
                    String text = Json.string(part, null);
                    if (text != null) {
                        parts.add(text);
                    }
                }
                builder = LspServerConfig.stdio(id, parts.toArray(new String[0]));
            }

            for (Object extension : Json.array(object, "extensions")) {
                builder.addExtension(Json.string(extension, null));
            }
            String workingDirectory = Json.string(object, "workingDirectory", null);
            if (workingDirectory != null) {
                builder.workingDirectory(workingDirectory);
            }
            String languageId = Json.string(object, "languageId", null);
            if (languageId != null) {
                builder.languageId(languageId);
            }
            Object options = object.get("initializationOptions");
            if (options != null) {
                builder.initializationOptions(options);
            }
            long timeout = Json.integer(object, "requestTimeoutMillis", 30_000);
            builder.requestTimeoutMillis(timeout);

            try {
                register(builder.build());
                count++;
            } catch (RuntimeException e) {
                com.linux.plugin.lsp.LspLogger.DEFAULT.warning("跳过非法的 LSP 配置(" + id + "): " + e.getMessage());
            }
        }
        return count;
    }

    /**
     * 从插件 assets 读取配置。
     *
     * <p>用反射调用 {@code Activity.getAssets()} 而不是直接写死 Android 类型：
     * 这样协议层与桥接层的其余部分都不依赖 Android API，
     * 独立编译/单元测试时不会被 SDK 缺失卡住。
     *
     * @param assetName assets 内的文件名，如 {@code lsp.json}
     * @return 注册数量；文件不存在或取不到 assets 时返回 0
     */
    public int loadFromAssets(String assetName) {
        try {
            Object activity = Platform.getActivity();
            if (activity == null) {
                return 0;
            }
            Object assets = activity.getClass().getMethod("getAssets").invoke(activity);
            if (assets == null) {
                return 0;
            }
            java.io.InputStream in = (java.io.InputStream)
                    assets.getClass().getMethod("open", String.class).invoke(assets, assetName);
            if (in == null) {
                return 0;
            }
            try {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                return loadFromJson(new String(buffer.toByteArray(), "UTF-8"));
            } finally {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
        } catch (Throwable t) {
            // assets 里没有这个文件是常态，不打错误日志。
            return 0;
        }
    }

    private static String keyOf(LspServerConfig config) {
        String languageId = config.getLanguageId();
        return (languageId == null || languageId.isEmpty() ? config.getId() : languageId)
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 在搜索目录里找可执行文件。
     *
     * <p>除了给定目录，还会看 {@code PATH} 环境变量——桌面版调试时常把 clangd 装在 PATH 里。
     *
     * @return 找到的绝对路径；找不到返回 null
     */
    public static String findExecutable(String name, String... searchRoots) {
        Set<String> directories = new LinkedHashSet<>();
        if (searchRoots != null) {
            Collections.addAll(directories, searchRoots);
        }
        if (name.contains("/")) {
            File direct = new File(name);
            return direct.isFile() && direct.canExecute() ? direct.getAbsolutePath() : null;
        }
        String path = System.getenv("PATH");
        if (path != null) {
            Collections.addAll(directories, path.split(File.pathSeparator));
        }
        for (String directory : directories) {
            if (directory == null || directory.isEmpty()) {
                continue;
            }
            File candidate = new File(directory, name);
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }
}
