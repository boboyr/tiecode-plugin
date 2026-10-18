package com.linux.plugin.lsp.protocol;

import com.linux.plugin.lsp.transport.SocketTransport;
import com.linux.plugin.lsp.transport.StdioTransport;
import com.linux.plugin.lsp.transport.Transport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 一个 LSP 服务端的启动配置。
 *
 * <p>两种形态（与 {@code Transport} 抽象一一对应）：
 * <ul>
 *   <li>{@link Kind#STDIO}：命令 + 参数，插件负责把服务端拉起来；</li>
 *   <li>{@link Kind#SOCKET}：服务端已在 host:port 监听，插件只连。</li>
 * </ul>
 *
 * <p>用 {@link #builder(String)} 构造，例如：
 * <pre>
 * LspServerConfig clangd = LspServerConfig.stdio("clangd", "/path/to/clangd", "--stdio")
 *         .addExtension(".cpp").addExtension(".h")
 *         .build();
 *
 * LspServerConfig remote = LspServerConfig.socket("clangd-tcp", 9333)
 *         .addExtension(".cpp")
 *         .build();
 * </pre>
 */
public final class LspServerConfig {

    public enum Kind {
        /** 插件拉起子进程，走标准输入输出。 */
        STDIO,
        /** 连接已存在的 TCP 监听端口。 */
        SOCKET
    }

    private final String id;
    private final Kind kind;
    private final List<String> command;
    private final String workingDirectory;
    private final String host;
    private final int port;
    private final List<String> extensions;
    private final String languageId;
    private final Object initializationOptions;
    private final long requestTimeoutMillis;

    private LspServerConfig(Builder builder) {
        this.id = builder.id;
        this.kind = builder.kind;
        this.command = Collections.unmodifiableList(new ArrayList<>(builder.command));
        this.workingDirectory = builder.workingDirectory;
        this.host = builder.host;
        this.port = builder.port;
        this.extensions = Collections.unmodifiableList(new ArrayList<>(builder.extensions));
        this.languageId = builder.languageId;
        this.initializationOptions = builder.initializationOptions;
        this.requestTimeoutMillis = builder.requestTimeoutMillis;
    }

    public String getId() {
        return id;
    }

    public Kind getKind() {
        return kind;
    }

    public List<String> getCommand() {
        return command;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /** 该服务端负责的文件扩展名，统一带点且小写，如 {@code .cpp}。 */
    public List<String> getExtensions() {
        return extensions;
    }

    /**
     * 发给服务端的 {@code languageId}（影响服务端按哪种语言解析）。
     * 为空时由调用方按扩展名推断。
     */
    public String getLanguageId() {
        return languageId;
    }

    /** initialize 的 {@code initializationOptions} 原样透传，可为 null。 */
    public Object getInitializationOptions() {
        return initializationOptions;
    }

    /** 单条请求的超时毫秒；&le;0 表示不超时（长耗时索引场景可设 0）。 */
    public long getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    /** 按配置创建对应的传输通道（尚未 open）。 */
    public Transport createTransport() {
        if (kind == Kind.SOCKET) {
            return new SocketTransport(host, port);
        }
        return new StdioTransport(command, workingDirectory);
    }

    /** 该服务端是否负责处理这个扩展名（大小写不敏感）。 */
    public boolean handlesExtension(String extension) {
        if (extension == null) {
            return false;
        }
        String normalized = normalizeExtension(extension);
        for (String candidate : extensions) {
            if (candidate.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 统一成 {@code .ext} 小写形式；传入 {@code "cpp"} 与 {@code ".CPP"} 都得到 {@code .cpp}。 */
    public static String normalizeExtension(String extension) {
        if (extension == null) {
            return "";
        }
        String text = extension.trim().toLowerCase(java.util.Locale.ROOT);
        if (text.isEmpty()) {
            return "";
        }
        return text.charAt(0) == '.' ? text : "." + text;
    }

    @Override
    public String toString() {
        return "LspServerConfig{" + id + ", " + kind
                + (kind == Kind.SOCKET ? " " + host + ":" + port : " " + command)
                + ", extensions=" + extensions + "}";
    }

    // ------------------------------------------------------------------
    // 构造入口
    // ------------------------------------------------------------------

    /**
     * 创建 stdio 形态的配置构造器。
     *
     * @param id      服务端标识（同时作为默认 languageId）
     * @param command 完整命令行，第一个元素是可执行文件
     */
    public static Builder stdio(String id, String... command) {
        Builder builder = new Builder(id, Kind.STDIO);
        builder.command.addAll(Arrays.asList(command));
        builder.languageId = id;
        return builder;
    }

    /** 创建 socket 形态的配置构造器。 */
    public static Builder socket(String id, String host, int port) {
        Builder builder = new Builder(id, Kind.SOCKET);
        builder.host = host;
        builder.port = port;
        builder.languageId = id;
        return builder;
    }

    /** 连接本机回环端口。 */
    public static Builder socket(String id, int port) {
        return socket(id, "127.0.0.1", port);
    }

    public static final class Builder {

        private final String id;
        private final Kind kind;
        private final List<String> command = new ArrayList<>();
        private final List<String> extensions = new ArrayList<>();
        private String workingDirectory;
        private String host;
        private int port;
        private String languageId;
        private Object initializationOptions;
        private long requestTimeoutMillis = 30_000L;

        private Builder(String id, Kind kind) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("服务端 id 不能为空");
            }
            this.id = id.trim();
            this.kind = kind;
        }

        public Builder addExtension(String extension) {
            String normalized = normalizeExtension(extension);
            if (!normalized.isEmpty()) {
                extensions.add(normalized);
            }
            return this;
        }

        public Builder addExtensions(String... values) {
            if (values != null) {
                for (String value : values) {
                    addExtension(value);
                }
            }
            return this;
        }

        /** 覆盖命令行（stdio 形态）。 */
        public Builder command(String... values) {
            command.clear();
            if (values != null) {
                command.addAll(Arrays.asList(values));
            }
            return this;
        }

        /** 追加命令行参数（stdio 形态）。 */
        public Builder arg(String... values) {
            if (values != null) {
                command.addAll(Arrays.asList(values));
            }
            return this;
        }

        public Builder workingDirectory(String directory) {
            this.workingDirectory = directory;
            return this;
        }

        public Builder languageId(String value) {
            this.languageId = value;
            return this;
        }

        public Builder initializationOptions(Object value) {
            this.initializationOptions = value;
            return this;
        }

        public Builder requestTimeoutMillis(long value) {
            this.requestTimeoutMillis = value;
            return this;
        }

        public LspServerConfig build() {
            if (kind == Kind.STDIO && command.isEmpty()) {
                throw new IllegalStateException("stdio 形态必须提供命令行: " + id);
            }
            if (kind == Kind.SOCKET && (port <= 0 || port > 65535)) {
                throw new IllegalStateException("socket 形态端口非法: " + port);
            }
            return new LspServerConfig(this);
        }
    }
}
