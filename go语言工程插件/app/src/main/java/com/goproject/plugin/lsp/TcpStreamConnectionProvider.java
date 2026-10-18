package com.goproject.plugin.lsp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider;

/**
 * 基于 TCP 的 LSP 通道:连接 Termux 里 {@code gopls -listen=host:port} 的监听端口。
 *
 * <p>与旧协议栈里的 SocketTransport 等价,只是改由 editor-lsp 的
 * {@link StreamConnectionProvider} 抽象承载,交给 lsp4j 读写成帧。
 */
public class TcpStreamConnectionProvider implements StreamConnectionProvider {

    private final String host;
    private final int port;
    private Socket socket;
    private boolean closed;

    public TcpStreamConnectionProvider(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void start() throws IOException {
        Socket created = new Socket();
        created.connect(new InetSocketAddress(host, port), GoplsConfig.CONNECT_TIMEOUT_MILLIS);
        created.setSoTimeout(0);
        created.setTcpNoDelay(true);
        this.socket = created;
        this.closed = false;
    }

    @Override
    public InputStream getInputStream() {
        try {
            if (socket == null) {
                throw new IllegalStateException("Socket not initialized");
            }
            return socket.getInputStream();
        } catch (IOException e) {
            throw new RuntimeException("Failed to get input stream", e);
        }
    }

    @Override
    public OutputStream getOutputStream() {
        try {
            if (socket == null) {
                throw new IllegalStateException("Socket not initialized");
            }
            return socket.getOutputStream();
        } catch (IOException e) {
            throw new RuntimeException("Failed to get output stream", e);
        }
    }

    @Override
    public boolean isClosed() {
        return closed || socket == null || socket.isClosed();
    }

    @Override
    public void close() {
        closed = true;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        } finally {
            socket = null;
        }
    }
}
