package com.linux.plugin.lsp;

/**
 * 文档正文来源。
 *
 * <p>LSP 需要拿到编辑器里"当前"的文本（可能还没落盘）。
 * 结绳侧的 {code LanguageContext} 已经维护了未保存内容，
 * 桥接层通过实现本接口把它接进来，避免 LSP 层硬编码读文件。
 */
public interface DocumentContentProvider {

    /**
     * 读取文档当前正文。
     *
     * @param uri 文件 URI
     * @return 正文；读不到时返回 null
     */
    String getContent(String uri);
}
