package com.goproject.plugin.lsp;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import com.goproject.plugin.lsp.bridge.GoplsLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider;

/**
 * 基于 Unix 域套接字的 LSP 通道。
 *
 * <p>用 Android 的 {@link LocalSocket} 而不是 {@code java.net}：
 * JDK 的 {@code UnixDomainSocketAddress}（Java 16+）在 Android 上不可用，
 * 而 {@code LocalSocket} 正好封装了 Linux 的 {@code AF_UNIX}。
 *
 * <h3>两种命名空间</h3>
 * <ul>
 *   <li>{@link LocalSocketAddress.Namespace#ABSTRACT}：不占文件系统、不需要文件权限，
 *       两边约定同一个名字即可。这是默认选择，因为 {@code /sdcard} 无法创建套接字文件
 *       （FUSE/sdcardfs 不支持 {@code mknod(S_IFSOCK)}），而 Termux 与结绳的私有目录
 *       互相不可访问，文件路径在跨 app 场景下很难走通。</li>
 *   <li>{@link LocalSocketAddress.Namespace#FILESYSTEM}：普通文件路径。
 *       需要服务端能创建、客户端能 traverse 且有写权限。</li>
 * </ul>
 *
 * <h3>连接超时</h3>
 * {@code LocalSocket} 的 {@code connect} 没有超时参数（Android 未暴露），
 * 因此这里在调用前不做超时控制；实际使用中 gopls 就在同一台设备上，
 * 连不上会立刻返回 ECONNREFUSED / ENOENT，不会长时间挂住。
 */
public class UnixSocketStreamConnectionProvider implements StreamConnectionProvider {

    private final String socketName;
    private final LocalSocketAddress.Namespace namespace;

    private LocalSocket socket;
    /**
     * 自己维护关闭标记。
     *
     * <p>不依赖 {@code LocalSocket.isClosed()}：该方法的可用性随 Android 版本而异，
     * 而这里只需要"我们是否已经关过"这一个语义，自己记更稳。
     */
    private volatile boolean closed;

    /**
     * @param socketName 抽象命名空间的<b>名字本身</b>（不要带 {@code @}），
     *                   或文件系统命名空间下的完整路径
     * @param namespace  {@link LocalSocketAddress.Namespace#ABSTRACT} 或
     *                   {@link LocalSocketAddress.Namespace#FILESYSTEM}
     */
    public UnixSocketStreamConnectionProvider(String socketName,
                                              LocalSocketAddress.Namespace namespace) {
        this.socketName = socketName;
        this.namespace = namespace;
    }

    /** 便捷构造：抽象命名空间。 */
    public static UnixSocketStreamConnectionProvider abstractNamespace(String name) {
        return new UnixSocketStreamConnectionProvider(name, LocalSocketAddress.Namespace.ABSTRACT);
    }

    /** 便捷构造：文件系统路径。 */
    public static UnixSocketStreamConnectionProvider fileSystem(String path) {
        return new UnixSocketStreamConnectionProvider(path, LocalSocketAddress.Namespace.FILESYSTEM);
    }

    @Override
    public void start() throws IOException {
        LocalSocket created = new LocalSocket();
        try {
            created.connect(new LocalSocketAddress(socketName, namespace));
        } catch (IOException e) {
            // 连接失败要顺手关掉，否则会泄漏一个已分配的 fd。
            try {
                created.close();
            } catch (IOException ignored) {
            }
            throw new IOException("无法连接 gopls 套接字 " + describeTarget() + ": " + e.getMessage(), e);
        }
        this.socket = created;
        this.closed = false;
        GoplsLogger.get().info("已连接 gopls 套接字: " + describeTarget());
    }

    @Override
    public InputStream getInputStream() {
        LocalSocket current = socket;
        if (current == null) {
            throw new IllegalStateException("套接字尚未初始化（未调用 start）");
        }
        try {
            return current.getInputStream();
        } catch (IOException e) {
            throw new RuntimeException("取套接字输入流失败: " + describeTarget(), e);
        }
    }

    @Override
    public OutputStream getOutputStream() {
        LocalSocket current = socket;
        if (current == null) {
            throw new IllegalStateException("套接字尚未初始化（未调用 start）");
        }
        try {
            return current.getOutputStream();
        } catch (IOException e) {
            throw new RuntimeException("取套接字输出流失败: " + describeTarget(), e);
        }
    }

    @Override
    public boolean isClosed() {
        return closed || socket == null;
    }

    @Override
    public void close() {
        closed = true;
        LocalSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // 关闭阶段的异常无需向上传播。
            }
        }
    }

    /** 供日志展示的连接目标。 */
    private String describeTarget() {
        if (namespace == LocalSocketAddress.Namespace.ABSTRACT) {
            return "@" + socketName + "（抽象命名空间）";
        }
        return socketName + "（文件系统路径）";
    }
}
