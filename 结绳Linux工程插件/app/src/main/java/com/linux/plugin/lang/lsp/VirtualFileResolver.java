package com.linux.plugin.lang.lsp;

import com.rapid.framework.vfs.VirtualFile;

/** LSP URI → 结绳 {@link VirtualFile} 的解析器。 */
public interface VirtualFileResolver {

    /**
     * 解析 URI 对应的虚拟文件。
     *
     * @return 解析失败或文件不存在时返回 null（调用方应跳过该条目而不是抛错）
     */
    VirtualFile resolve(String uri);
}
