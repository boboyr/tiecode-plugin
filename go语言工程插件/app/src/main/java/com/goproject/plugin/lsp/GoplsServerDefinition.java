package com.goproject.plugin.lsp;

import com.goproject.plugin.lsp.bridge.GoplsLogger;

import android.net.LocalSocketAddress;

import java.net.URI;
import java.util.Arrays;

import io.github.rosemoe.sora.lsp.client.languageserver.ServerStatus;
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition;
import io.github.rosemoe.sora.lsp.client.languageserver.wrapper.EventHandler;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * gopls 的服务端定义:描述"如何连上 gopls、初始化参数、事件回调"。
 *
 * <p>通道由 {@link GoplsConfig#getTransport()} 决定。默认是 Unix 文件路径：
 * gopls 由 {@link GoplsServerProcess} 作为插件的子进程拉起，与结绳同 uid，
 * 因此套接字就放在 app 私有目录里。监听地址的实际值由 {@link GoplsServerProcess}
 * 在启动时算好并注入 {@link GoplsConfig}。
 *
 * <p>路径超长时会自动退化为抽象命名空间（{@link UnixSocketStreamConnectionProvider} 的
 * ABSTRACT 模式）；排查问题时也可用 {@link GoplsConfig#useTcp()} 切到 TCP 回环
 * （{@link TcpStreamConnectionProvider}）。
 *
 * <p>协议读写与能力协商全部交给 editor-lsp 内部的 lsp4j 客户端，插件不自建协议栈。
 */
public class GoplsServerDefinition extends CustomLanguageServerDefinition {

    private static final EventHandler.EventListener LISTENER = new EventHandler.EventListener() {
        @Override
        public void initialize(LanguageServer server, InitializeResult result) {
            String info = (result == null || result.getServerInfo() == null)
                    ? "unknown" : result.getServerInfo().getName();
            GoplsLogger.get().info("gopls initialize 完成: " + info);
        }

        @Override
        public void onShowMessage(MessageParams params) {
            if (params != null) {
                GoplsLogger.get().info("gopls 消息: " + params.getMessage());
            }
        }

        @Override
        public void onLogMessage(MessageParams params) {
            if (params != null) {
                GoplsLogger.get().info("gopls 日志: " + params.getMessage());
            }
        }

        @Override
        public void onHandlerException(Exception e) {
            GoplsLogger.get().warning("gopls 处理异常: " + e);
        }

        @Override
        public void onStatusChange(ServerStatus oldStatus, ServerStatus newStatus) {
            GoplsLogger.get().info("gopls 状态: " + oldStatus + " -> " + newStatus);
        }
    };

    public GoplsServerDefinition(String ext, final GoplsSession session) {
        super(
                ext,
                workingDir -> createConnectionProvider(session),
                "gopls",
                null,
                Arrays.asList(GoplsConfig.EXTENSIONS));
    }

    /**
     * 按当前配置创建通道。
     *
     * <p>每次连接都重新读一次 {@link GoplsConfig#getTransport()}，
     * 这样运行期切换通道后，下一次重连就能生效，不必重启插件。
     */
    private static io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider
            createConnectionProvider(GoplsSession session) {
        switch (GoplsConfig.getTransport()) {
            case UNIX_ABSTRACT:
                return new UnixSocketStreamConnectionProvider(
                        GoplsConfig.getUnixAbstractName(),
                        LocalSocketAddress.Namespace.ABSTRACT);
            case UNIX_FILESYSTEM:
                return new UnixSocketStreamConnectionProvider(
                        GoplsConfig.getUnixSocketPath(),
                        LocalSocketAddress.Namespace.FILESYSTEM);
            default:
                return new TcpStreamConnectionProvider(session.getHost(), session.getPort());
        }
    }

    @Override
    public Object getInitializationOptions(URI uri) {
        return GoplsConfig.initializationOptions();
    }

    @Override
    public EventHandler.EventListener getEventListener() {
        return LISTENER;
    }
}
