package com.linux.plugin.lang.lsp;

/**
 * 可选的"语言标识解析"能力。
 *
 * <p>服务端在 {@code textDocument/didOpen} 里需要一个 {@code languageId}，
 * 默认按文件扩展名推断（{@code .go → go}）。但有些文件不能按扩展名推：
 * {@code go.mod} 要报 {@code go.mod} 而不是 {@code mod}。
 *
 * <p>所以只要语言上下文实现本接口，桥接层就会优先用它，
 * 按扩展名推断只作为兜底。
 */
public interface LspLanguageContext {

    /**
     * 决定某文件应发给服务端的 {@code languageId}。
     *
     * @param uri 文件 URI
     * @return languageId；返回 null 或空串表示"按扩展名推断即可"
     */
    String languageIdFor(String uri);
}
