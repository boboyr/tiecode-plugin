package com.linux.plugin.lsp;

/** LSP 模块日志出口；默认转到结绳 IDE 的日志（拿不到时退回 stderr）。 */
public interface LspLogger {

    void info(String message);

    void warning(String message);

    void error(String message);

    void error(String message, Throwable cause);

    /** 默认实现：优先写入结绳 IDE 日志，失败则退回 stderr。 */
    LspLogger DEFAULT = new LspLogger() {

        @Override
        public void info(String message) {
            try {
                com.rapid.api.Platform.getLogger().info("[LSP] " + message);
            } catch (Throwable ignore) {
                System.err.println("[LSP] " + message);
            }
        }

        @Override
        public void warning(String message) {
            try {
                com.rapid.api.Platform.getLogger().warning("[LSP] " + message);
            } catch (Throwable ignore) {
                System.err.println("[LSP][warn] " + message);
            }
        }

        @Override
        public void error(String message) {
            try {
                com.rapid.api.Platform.getLogger().error("[LSP] " + message);
            } catch (Throwable ignore) {
                System.err.println("[LSP][error] " + message);
            }
        }

        @Override
        public void error(String message, Throwable cause) {
            try {
                com.rapid.api.Platform.getLogger().error("[LSP] " + message + " : " + cause);
                if (cause != null) {
                    com.rapid.api.Platform.getLogger().trace(cause);
                }
            } catch (Throwable ignore) {
                System.err.println("[LSP][error] " + message);
                if (cause != null) {
                    cause.printStackTrace();
                }
            }
        }
    };
}
