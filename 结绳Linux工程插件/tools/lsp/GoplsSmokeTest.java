package com.linux.plugin.lsp;

import com.linux.plugin.lsp.json.Json;
import com.linux.plugin.lsp.protocol.JsonRpcConnection;
import com.linux.plugin.lsp.protocol.ServerCapabilities;
import com.linux.plugin.lsp.protocol.TransportFactory;
import com.linux.plugin.lsp.transport.SocketTransport;
import com.linux.plugin.lsp.transport.Transport;

import java.util.List;
import java.util.Map;

/**
 * gopls 连接冒烟测试：不依赖结绳、不依赖 Android SDK，纯 JDK 就能跑。
 *
 * <p>用途：在碰插件代码之前，先确认"Termux 那边的 gopls 是否真的在听、
 * 是否按标准 LSP 应答"。这一步跑通，插件里连不上就一定是插件侧的问题，
 * 能省掉大量猜测。
 *
 * <p>之所以能不依赖 Android：{@code com.linux.plugin.lsp} 这个包
 * （JSON、传输、JSON-RPC、LspClient）刻意<b>只用 JDK 类</b>，
 * 连写日志都是反射兜底。只有 {@code com.linux.plugin.lang.lsp} 那层
 * 才依赖 studio-api 与 Android。
 *
 * <h3>Termux 侧先起服务端</h3>
 * <pre>
 * gopls -listen=localhost:37374
 * </pre>
 *
 * <h3>编译与运行</h3>
 * <pre>
 * cd 结绳Linux工程插件
 *
 * javac -encoding UTF-8 -d /tmp/lspbuild \
 *     $(find app/src/main/java/com/linux/plugin/lsp -name '*.java') \
 *     tools/lsp/GoplsSmokeTest.java
 *
 * java -cp /tmp/lspbuild com.linux.plugin.lsp.GoplsSmokeTest \
 *     127.0.0.1 37374 /sdcard/dsh
 * </pre>
 *
 * <p>它做的事：连接 → {@code initialize} → {@code initialized} →
 * 对工作区里第一个 {@code .go} 文件发一次真实补全请求 → 打印结果 → 优雅退出。
 * 想确认"补全到底能不能用"，这一步最直接。
 */
public final class GoplsSmokeTest {

    private static final long REQUEST_TIMEOUT_MS = 60_000L;

    /** 与 {@code GoLspContext.DEFAULT_PORT} 一致；这里写死是为了让本测试不依赖 Android。 */
    private static final int DEFAULT_GOPLS_PORT = 37374;

    private GoplsSmokeTest() {
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_GOPLS_PORT;
        String workspace = args.length > 2 ? args[2] : "/sdcard";

        System.out.println("== gopls 冒烟测试 ==");
        System.out.println("端点: " + host + ":" + port);
        System.out.println("workspace: " + workspace);

        final SocketTransport transport = new SocketTransport(host, port);
        JsonRpcConnection connection = new JsonRpcConnection(new TransportFactory() {
            @Override
            public Transport create() {
                return transport;
            }
        }, LspLogger.DEFAULT);
        connection.setServerErrorListener(new Transport.LogListener() {
            @Override
            public void onLog(String line) {
                System.out.println("[gopls] " + line);
            }
        });

        try {
            connection.start();
            System.out.println("已连接: " + connection.describeTransport());
        } catch (Exception e) {
            System.err.println("连接失败: " + e.getMessage());
            System.err.println("请确认 Termux 里已执行: gopls -listen=localhost:" + port);
            System.exit(2);
            return;
        }

        try {
            Map<String, Object> initParams = Json.obj(
                    "processId", null,
                    "clientInfo", Json.obj("name", "dsh-smoke", "version", "1.0"),
                    // 只声明最少的必需能力即可。
                    "capabilities", Json.obj("textDocument", Json.obj()),
                    "rootUri", toFileUri(workspace),
                    "workspaceFolders", Json.arr(Json.obj(
                            "uri", toFileUri(workspace),
                            "name", lastSegment(workspace))));

            System.out.println("\n-> initialize");
            Object result = connection.request("initialize", initParams, REQUEST_TIMEOUT_MS);
            Map<String, Object> resultObject = Json.object(result);
            Map<String, Object> serverInfo = Json.object(resultObject, "serverInfo");
            ServerCapabilities capabilities = new ServerCapabilities(Json.object(resultObject, "capabilities"));

            System.out.println("<- 服务端: " + Json.write(serverInfo));
            System.out.println("<- 能力: " + capabilities);
            System.out.println("   补全=" + capabilities.supportsCompletion()
                    + " 悬停=" + capabilities.supportsHover()
                    + " 定义=" + capabilities.supportsDefinition()
                    + " 引用=" + capabilities.supportsReferences()
                    + " 诊断推送=" + (capabilities.getSyncChangeKind() != 0));
            System.out.println("   同步方式=" + (capabilities.prefersFullSync() ? "全量" : "增量"));
            System.out.println("   补全触发字符=" + capabilities.getCompletionTriggerCharacters());

            connection.notify("initialized", Json.obj());

            String file = firstGoFile(workspace);
            if (file == null) {
                System.out.println("\n[跳过补全测试] " + workspace + " 下没找到 .go 文件");
            } else {
                probeCompletion(connection, file, capabilities);
            }

            System.out.println("\n-> shutdown");
            connection.request("shutdown", null, 5_000L);
            connection.notify("exit", null);
            System.out.println("\n== 冒烟测试通过：gopls 在标准 LSP 上应答正常 ==");
        } catch (Exception e) {
            System.err.println("测试失败: " + e);
            e.printStackTrace();
        } finally {
            connection.close();
        }
    }

    /** 打开一个 Go 文件、请求一次补全，打印服务端返回的条目。 */
    private static void probeCompletion(JsonRpcConnection connection, String file,
                                        ServerCapabilities capabilities) throws Exception {
        String uri = toFileUri(file);
        String text = readFile(file);

        System.out.println("\n-> didOpen " + file + " (" + text.length() + " 字节)");
        connection.notify("textDocument/didOpen", Json.obj("textDocument", Json.obj(
                "uri", uri,
                "languageId", "go",
                "version", Integer.valueOf(1),
                "text", text)));

        // 取第 10 行或文件末尾作为补全位置：那里更可能有内容可补。
        int line = Math.min(9, countLines(text) - 1);
        System.out.println("-> completion @ " + (line + 1) + ":1");
        long started = System.currentTimeMillis();
        Object response = connection.request("textDocument/completion", Json.obj(
                "textDocument", Json.obj("uri", uri),
                "position", Json.obj("line", Integer.valueOf(line), "character", Integer.valueOf(1)),
                "context", Json.obj("triggerKind", Integer.valueOf(1))), REQUEST_TIMEOUT_MS);
        long elapsed = System.currentTimeMillis() - started;

        List<Object> items = extractItems(response);
        System.out.println("<- 补全返回 " + items.size() + " 条，耗时 " + elapsed + "ms");

        int shown = 0;
        for (Object entry : items) {
            if (shown++ >= 10) {
                System.out.println("   ...");
                break;
            }
            Map<String, Object> item = Json.object(entry);
            if (item == null) {
                continue;
            }
            System.out.println("   - " + Json.string(item, "label", "?")
                    + "  kind=" + Json.integer(item, "kind", 0)
                    + (Json.string(item, "detail", null) == null ? "" : "  detail=" + Json.string(item, "detail", null)));
        }
        if (items.isEmpty()) {
            System.out.println("   （空结果是正常的：补全本来就是上下文相关的，"
                    + "光标位置没有可补内容时服务端就会返回空）");
        }

        connection.notify("textDocument/didClose", Json.obj("textDocument", Json.obj("uri", uri)));
    }

    /** completion 可能返回数组，也可能返回 {@code {isIncomplete, items}}。 */
    private static List<Object> extractItems(Object response) {
        if (response instanceof Map) {
            return Json.array(Json.object(response), "items");
        }
        return Json.array(response);
    }

    private static String firstGoFile(String directory) {
        java.io.File root = new java.io.File(directory);
        return walk(root, 0);
    }

    private static String walk(java.io.File directory, int depth) {
        if (directory == null || !directory.isDirectory() || depth > 4) {
            return null;
        }
        java.io.File[] children = directory.listFiles();
        if (children == null) {
            return null;
        }
        java.util.Arrays.sort(children);
        // 先看文件，再递归目录：让结果稳定可复现。
        for (java.io.File child : children) {
            if (child.isFile() && child.getName().endsWith(".go")
                    && !child.getName().endsWith("_test.go")) {
                return child.getAbsolutePath();
            }
        }
        for (java.io.File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            String name = child.getName();
            if ("build".equals(name) || "node_modules".equals(name) || ".git".equals(name)) {
                continue;
            }
            String found = walk(child, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String readFile(String path) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
        return new String(bytes, "UTF-8");
    }

    private static int countLines(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /** 复用协议层的 URI 工具，保证与插件里发的 URI 完全一致。 */
    private static String toFileUri(String path) {
        return com.linux.plugin.lsp.protocol.Uris.fromPath(path);
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
