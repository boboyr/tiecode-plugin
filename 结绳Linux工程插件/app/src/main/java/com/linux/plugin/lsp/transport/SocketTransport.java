package com.linux.plugin.lsp.transport;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * TCP 回环通道：语言服务端已在别处监听端口，插件只做客户端。
 *
 * <p>Android 上比 stdio 更稳：不依赖 fork/exec，也不会被子进程生命周期牵连。
 * 典型用法是 proot/Ubuntu 里跑 clangd（{@code clangd --port=...}）。
 *
 * <p>不设读超时：LSP 是长连接，靠 shutdown 关闭 socket 来解除阻塞。
 */
public final class SocketTransport implements Transport {

    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final String host;
    private final int port;
    private final Object lock = new Object();

    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private volatile boolean open;

    public SocketTransport(String host, int port) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("主机不能为空");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("端口非法: " + port);
        }
        this.host = host;
        this.port = port;
    }

    /** 默认连本机回环。 */
    public SocketTransport(int port) {
        this("127.0.0.1", port);
    }

    @Override
    public void open() throws Exception {
        synchronized (lock) {
            if (open) {
                throw new IllegalStateException("通道已打开: " + describe());
            }
            Socket created = new Socket();
            created.setTcpNoDelay(true);
            created.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket = created;
            input = created.getInputStream();
            output = created.getOutputStream();
            open = true;
        }
    }

    @Override
    public InputStream input() {
        return input;
    }

    @Override
    public OutputStream output() {
        return output;
    }

    @Override
    public boolean isOpen() {
        if (!open) {
            return false;
        }
        Socket current = socket;
        return current != null && current.isConnected() && !current.isClosed();
    }

    @Override
    public void redirectError(LogListener listener) {
        // socket 形态没有独立的 stderr 流，服务端日志由它自己输出。
    }

    @Override
    public void shutdown() {
        Socket current;
        synchronized (lock) {
            current = socket;
            socket = null;
            open = false;
            // 关流即可，socket.close() 会一并释放。
        }
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignore) {
            }
        }
        input = null;
        output = null;
    }

    @Override
    public String describe() {
        return "socket[" + host + ":" + port + "]";
    }
}
