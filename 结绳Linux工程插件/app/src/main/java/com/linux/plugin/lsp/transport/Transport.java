package com.linux.plugin.lsp.transport;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * LSP 传输通道抽象：只负责"字节流从哪来、往哪去"，
 * 消息分帧（Content-Length 头）由 {@code LspMessageCodec} 统一处理。
 *
 * <p>这样 stdio（子进程）与 socket（TCP 回环）两种形态可以互换，
 * 上层 {@code LspClient} 无需感知差异。
 */
public interface Transport {

    /** 建立连接。重复调用应抛异常。 */
    void open() throws Exception;

    /** 通道是否已建立且仍然可用。 */
    boolean isOpen();

    /** 服务端 → 客户端 的字节流。 */
    InputStream input();

    /** 客户端 → 服务端 的字节流。 */
    OutputStream output();

    /**
     * 把服务端 stderr 接到日志上。
     *
     * <p>stdio 形态会起一条抽干线程持续读取，避免子进程因管道写满而阻塞；
     * socket 形态无 stderr，实现为空。
     */
    void redirectError(LogListener listener);

    /** 关闭通道并释放资源；幂等。 */
    void shutdown();

    /** 供日志/诊断展示的通道描述，如 {@code stdio[clangd --stdio]}。 */
    String describe();

    /** 日志回调。 */
    interface LogListener {
        void onLog(String line);
    }
}
