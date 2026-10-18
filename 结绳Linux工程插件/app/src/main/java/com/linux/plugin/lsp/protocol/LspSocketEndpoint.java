package com.linux.plugin.lsp.protocol;

/**
 * socket 形态服务端的连接端点，并附带"改端口重连"的小工具。
 *
 * <p>存在的理由：端口经常变（Termux 里重启 gopls 换了端口、被占用换了端口），
 * 而 {@link LspServerConfig} 是不可变值对象。不想让调用方为改一个端口
 * 就重建整个 config（会丢掉扩展名、超时等其它设置），于是把
 * "从已有配置派生一个只改端口的配置"这件事单独抽出来。
 */
public final class LspSocketEndpoint {

    /** 本机回环地址：客户端与服务端在同一台设备上时用这个。 */
    public static final String LOOPBACK = "127.0.0.1";

    private LspSocketEndpoint() {
    }

    /**
     * 构造一个 socket 形态的配置。
     *
     * @param id         语言 id（也是注册表的键）
     * @param host       主机，同设备时用 {@link #LOOPBACK}
     * @param port       端口
     * @param extensions 该服务端负责的扩展名
     */
    public static LspServerConfig config(String id, String host, int port, String... extensions) {
        return LspServerConfig.socket(id, host, port)
                .addExtensions(extensions)
                .build();
    }

    /** 构造本机回环的 socket 配置。 */
    public static LspServerConfig loopback(String id, int port, String... extensions) {
        return config(id, LOOPBACK, port, extensions);
    }

    /**
     * 从已有配置派生一个"只改端口"的新配置。
     *
     * <p>扩展名、languageId、initializationOptions、超时全部沿用原配置。
     *
     * @return 新配置；原配置不是 socket 形态时返回原配置（无从改起）
     */
    public static LspServerConfig withPort(LspServerConfig base, int port) {
        if (base == null || base.getKind() != LspServerConfig.Kind.SOCKET) {
            return base;
        }
        LspServerConfig.Builder builder = LspServerConfig.socket(base.getId(), base.getHost(), port);
        copyCommon(base, builder);
        return builder.build();
    }

    /** 派生一个"只改主机"的新配置。 */
    public static LspServerConfig withHost(LspServerConfig base, String host) {
        if (base == null || base.getKind() != LspServerConfig.Kind.SOCKET) {
            return base;
        }
        LspServerConfig.Builder builder = LspServerConfig.socket(base.getId(), host, base.getPort());
        copyCommon(base, builder);
        return builder.build();
    }

    private static void copyCommon(LspServerConfig base, LspServerConfig.Builder builder) {
        for (String extension : base.getExtensions()) {
            builder.addExtension(extension);
        }
        if (base.getLanguageId() != null) {
            builder.languageId(base.getLanguageId());
        }
        if (base.getInitializationOptions() != null) {
            builder.initializationOptions(base.getInitializationOptions());
        }
        builder.requestTimeoutMillis(base.getRequestTimeoutMillis());
    }

    /**
     * 判断端口是否形如 {@code host:port}（gopls 的 {@code -listen} 参数格式）。
     *
     * @return 合法返回 true；用于校验用户从 Termux 抄来的地址
     */
    public static boolean isValidListenAddress(String address) {
        if (address == null) {
            return false;
        }
        String text = address.trim();
        if (text.isEmpty()) {
            return false;
        }
        // 允许 ":37374"、"127.0.0.1:37374"、"[::1]:37374"
        int colon = text.lastIndexOf(':');
        if (colon < 0 || colon == text.length() - 1) {
            return false;
        }
        try {
            int port = Integer.parseInt(text.substring(colon + 1));
            return port > 0 && port <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 从 {@code host:port} 形式的监听地址里取端口。
     *
     * @return 端口；解析失败返回 -1
     */
    public static int portOf(String listenAddress) {
        if (!isValidListenAddress(listenAddress)) {
            return -1;
        }
        String text = listenAddress.trim();
        return Integer.parseInt(text.substring(text.lastIndexOf(':') + 1));
    }

    /**
     * 从 {@code host:port} 形式取主机；省略主机时（如 {@code :37374}）返回回环地址。
     */
    public static String hostOf(String listenAddress) {
        if (!isValidListenAddress(listenAddress)) {
            return LOOPBACK;
        }
        String text = listenAddress.trim();
        String host = text.substring(0, text.lastIndexOf(':'));
        if (host.isEmpty() || "0.0.0.0".equals(host) || "*".equals(host)) {
            // 监听所有网卡时，客户端从本机连回环即可。
            return LOOPBACK;
        }
        // 去掉 IPv6 的方括号。
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return host;
    }
}
