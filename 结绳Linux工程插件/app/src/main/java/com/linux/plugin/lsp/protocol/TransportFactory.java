package com.linux.plugin.lsp.protocol;

import com.linux.plugin.lsp.transport.Transport;

/**
 * 传输通道工厂：{@link LspClient} 通过它在需要时创建通道。
 *
 * <p>做成工厂而不是直接持有实例，是为了支持"进程挂了自动重启"：
 * 重启时重新 {@code create()} 一个新通道即可。
 */
public interface TransportFactory {

    /** 创建（尚未 open 的）通道实例。每次调用都应返回新对象。 */
    Transport create();
}
