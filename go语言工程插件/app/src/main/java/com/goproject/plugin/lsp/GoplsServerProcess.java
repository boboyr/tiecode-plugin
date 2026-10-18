package com.goproject.plugin.lsp;

import android.content.Context;
import android.net.LocalSocketAddress;

import com.goproject.plugin.lsp.bridge.GoplsLogger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * gopls 服务端进程的生命周期管理：由插件自己拉起、自己关掉，不再依赖 Termux。
 *
 * <h3>为什么自己启动就能用 Unix 域套接字</h3>
 * gopls 作为插件的子进程运行，与结绳<b>同 uid</b>，因此套接字文件放在 app 私有目录里
 * 双方都能访问——这解决了"跑在 Termux 里时 uid 不同、私有目录互不可见，
 * 而 {@code /sdcard} 又不允许创建套接字文件"的死结。
 *
 * <h3>为什么 stdout/stderr 要重定向到文件</h3>
 * 走 {@code -listen} 时 LSP 报文在套接字上，stdout/stderr 只剩日志。
 * 但<b>必须把它们接走</b>：否则管道缓冲区写满后 gopls 会阻塞在写日志上而卡死。
 * 这里直接重定向到文件，既排空管道又留下可查的日志。
 */
public final class GoplsServerProcess {

    /** 等套接字就绪的最长时间。首次启动 gopls 通常 100~300ms。 */
    private static final long READY_TIMEOUT_MILLIS = 15_000L;

    /** 轮询间隔。 */
    private static final long READY_POLL_MILLIS = 50L;

    private final Object lock = new Object();

    private Process process;
    /** 客户端连接时使用的套接字名（文件路径或抽象名）。 */
    private String socketName;
    /** 客户端连接时使用的命名空间。 */
    private LocalSocketAddress.Namespace namespace = LocalSocketAddress.Namespace.FILESYSTEM;
    /** 实际传给 gopls 的 {@code -listen} 值。 */
    private String listenArgument;

    public String getSocketName() {
        return socketName;
    }

    public LocalSocketAddress.Namespace getNamespace() {
        return namespace;
    }

    public String getListenArgument() {
        return listenArgument;
    }

    public boolean isRunning() {
        Process current = process;
        if (current == null) {
            return false;
        }
        try {
            current.exitValue();
            // 已经退出。
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    /**
     * 启动 gopls 并等待套接字可连接。
     *
     * <p>应在后台线程调用：解压工具链可能要几秒，等待套接字就绪也有轮询。
     *
     * @param workspace 工程工作目录（作为 gopls 的进程工作目录）
     * @return 是否启动成功且套接字已就绪
     */
    public boolean start(Context context, File workspace) {
        synchronized (lock) {
            if (isRunning()) {
                return true;
            }

            if (!GoplsToolchain.ensureInstalled(context)) {
                GoplsLogger.get().error("Go 工具链不可用，无法启动 gopls");
                return false;
            }

            resolveSocketAddress(context);

            File gopls = GoplsToolchain.goplsBinary(context);
            File logFile = GoplsToolchain.serverLogFile(context);

            List<String> command = new ArrayList<>();
            command.add(gopls.getAbsolutePath());
            command.add("-listen=" + listenArgument);
            // 没有客户端连接时不退出：客户端断开后 gopls 仍需存活，
            // 否则每次重连都要重新索引。0 表示无限等待。
            command.add("-listen.timeout=0");
            // 把 LSP 报文写进日志，便于排查协议层问题。
            command.add("-rpc.trace");

            GoplsLogger.get().info("启动 gopls: " + gopls.getAbsolutePath()
                    + " -listen=" + listenArgument
                    + " workspace=" + (workspace == null ? "(无)" : workspace.getAbsolutePath()));

            ProcessBuilder builder = new ProcessBuilder(command);
            if (workspace != null && workspace.isDirectory()) {
                builder.directory(workspace);
            }
            builder.environment().putAll(GoplsToolchain.buildEnvironment(context));
            // 日志重定向到文件：既排空管道（防阻塞），又留下可查记录。
            // 用 to() 而不是 appendTo()，每次启动拿到一份干净的日志。
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.to(logFile));

            Process created;
            try {
                created = builder.start();
            } catch (Throwable t) {
                GoplsLogger.get().error("启动 gopls 进程失败: " + t, t);
                return false;
            }
            this.process = created;

            if (!waitForReady(created)) {
                GoplsLogger.get().error("gopls 未在 " + READY_TIMEOUT_MILLIS + "ms 内就绪，"
                        + "详见日志 " + logFile.getAbsolutePath());
                stopLocked();
                return false;
            }
            GoplsLogger.get().info("gopls 已就绪: " + listenArgument);
            return true;
        }
    }

    /** 结束 gopls 进程；幂等。 */
    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    private void stopLocked() {
        Process current = process;
        process = null;
        if (current == null) {
            return;
        }
        try {
            current.destroy();
            if (!current.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                current.destroyForcibly();
            }
        } catch (Throwable ignore) {
            // 关闭阶段的异常无需上抛。
        }
        GoplsLogger.get().info("已结束 gopls 进程");
    }

    /**
     * 决定套接字地址，并清理上一次留下的套接字文件。
     *
     * <p>路径过长（超过 Linux {@code sun_path} 上限）时退化为抽象命名空间——
     * 它不占文件系统也没有长度限制。
     */
    private void resolveSocketAddress(Context context) {
        if (GoplsToolchain.isSocketPathUsable(context)) {
            File socket = GoplsToolchain.socketFile(context);
            // 上一轮异常退出时套接字文件可能还在，残留会让 gopls 的 bind 失败（EADDRINUSE）。
            if (socket.exists() && !socket.delete()) {
                GoplsLogger.get().warning("残留套接字文件删除失败: " + socket.getAbsolutePath());
            }
            socketName = socket.getAbsolutePath();
            namespace = LocalSocketAddress.Namespace.FILESYSTEM;
            listenArgument = "unix;" + socketName;
            // 让 GoplsServerDefinition 建连时能拿到实际路径。
            GoplsConfig.setResolvedUnixSocketPath(socketName);
            return;
        }
        // 退路：抽象命名空间。
        socketName = GoplsConfig.getUnixAbstractName();
        namespace = LocalSocketAddress.Namespace.ABSTRACT;
        listenArgument = "unix;@" + socketName;
        GoplsLogger.get().warning("套接字路径过长，改用抽象命名空间 @" + socketName);
    }

    /**
     * 等待 gopls 把套接字准备好。
     *
     * <p>文件系统命名空间可以直接看文件是否出现；抽象命名空间没有文件可查，
     * 只能给一小段固定延迟，然后交给上层的连接重试。
     */
    private boolean waitForReady(Process current) {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        boolean fileSystem = namespace == LocalSocketAddress.Namespace.FILESYSTEM;

        while (System.currentTimeMillis() < deadline) {
            // 进程已经退出就没必要再等。
            try {
                current.exitValue();
                return false;
            } catch (IllegalThreadStateException alive) {
                // 仍在运行。
            }
            if (!fileSystem) {
                sleepQuietly(READY_POLL_MILLIS * 4);
                return true;
            }
            if (new File(socketName).exists()) {
                return true;
            }
            sleepQuietly(READY_POLL_MILLIS);
        }
        return false;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 供日志：把当前命令描述成可直接手抄的形式。 */
    public String describeCommand() {
        return "gopls -listen=" + listenArgument;
    }

    /** 供上层判断 IOException 是否属于"套接字还没建好"这类可重试错误。 */
    public static boolean isRetryableConnectError(IOException e) {
        String message = e.getMessage();
        return message != null && (message.contains("ENOENT")
                || message.contains("ECONNREFUSED")
                || message.contains("No such file"));
    }
}
