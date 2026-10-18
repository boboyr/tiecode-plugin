package com.linux.plugin.lang.lsp;

import com.linux.plugin.lsp.DocumentContentProvider;
import com.linux.plugin.lsp.LspLogger;
import com.linux.plugin.lsp.protocol.Uris;
import com.rapid.api.lang.engine.LanguageContext;
import com.rapid.framework.vfs.VirtualFile;
import com.rapid.framework.vfs.VirtualFileSystems;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 把结绳的 {@link LanguageContext} / 虚拟文件系统接到 LSP 层。
 *
 * <p>为什么需要这层：LSP 必须拿到编辑器里"当前"的文本——包括<b>尚未保存</b>的内容。
 * 直接读磁盘会在用户边打字时给服务端送旧文本，补全结果对不上。
 * 结绳的 {@code DefaultLanguageContext} 已经缓存了未保存正文，
 * 这里把它取出来交给 {@code LspClient}。
 */
public final class LspContentProviders {

    private LspContentProviders() {
    }

    /**
     * 构造以结绳语言上下文为源的正文提供者。
     *
     * <p>取文顺序：上下文缓存 → 直接读文件。
     */
    public static DocumentContentProvider fromContext(final LanguageContext context, final LspLogger logger) {
        return new DocumentContentProvider() {

            private final ConcurrentHashMap<String, VirtualFile> cache = new ConcurrentHashMap<>();

            @Override
            public String getContent(String uri) {
                if (uri == null) {
                    return null;
                }
                String key = Uris.normalize(uri);
                if (context != null) {
                    VirtualFile file = virtualFile(key);
                    if (file != null) {
                        try {
                            String content = context.getDynamicContent(file);
                            if (content != null) {
                                return content;
                            }
                        } catch (Throwable t) {
                            log(logger, "读取上下文内容失败(" + key + "): " + t);
                        }
                    }
                }
                return readFromDisk(key, logger);
            }

            /** 带缓存的 URI → VirtualFile，避免每次补全都重建对象。 */
            private VirtualFile virtualFile(String uri) {
                VirtualFile cached = cache.get(uri);
                if (cached != null) {
                    return cached;
                }
                VirtualFile created = resolveVirtualFile(uri, logger);
                if (created != null) {
                    cache.put(uri, created);
                }
                return created;
            }
        };
    }

    /**
     * 构造 URI → {@link VirtualFile} 解析器，供跳转结果与 workspaceEdit 落地使用。
     */
    public static VirtualFileResolver resolver(final LspLogger logger) {
        return new VirtualFileResolver() {

            private final ConcurrentHashMap<String, VirtualFile> cache = new ConcurrentHashMap<>();

            @Override
            public VirtualFile resolve(String uri) {
                if (uri == null) {
                    return null;
                }
                String key = Uris.normalize(uri);
                VirtualFile cached = cache.get(key);
                if (cached != null) {
                    return cached;
                }
                VirtualFile created = resolveVirtualFile(key, logger);
                if (created != null) {
                    cache.put(key, created);
                }
                return created;
            }
        };
    }

    /**
     * URI → VirtualFile。
     *
     * <p>用虚拟文件系统解析，非 file:// 协议（jar、http 等）也能覆盖。
     * 再校验一次存在性：服务端可能返回已经不存在的文件。
     */
    static VirtualFile resolveVirtualFile(String uri, LspLogger logger) {
        if (uri == null) {
            return null;
        }
        try {
            VirtualFileSystems systems = VirtualFileSystems.getInstance();
            VirtualFile file = systems.makeVirtualFile(java.net.URI.create(uri));
            if (file == null || (!file.isFile() && !file.isDirectory())) {
                return null;
            }
            return file;
        } catch (Throwable t) {
            log(logger, "解析虚拟文件失败(" + uri + "): " + t);
            return null;
        }
    }

    /** 兜底：直接用 java.io 读文件。 */
    private static String readFromDisk(String uri, LspLogger logger) {
        java.io.File file = Uris.toFile(uri);
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            byte[] buffer = new byte[(int) Math.min(file.length(), Integer.MAX_VALUE)];
            java.io.InputStream in = new java.io.FileInputStream(file);
            try {
                int read = 0;
                while (read < buffer.length) {
                    int n = in.read(buffer, read, buffer.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                return new String(buffer, 0, read, "UTF-8");
            } finally {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
        } catch (Throwable t) {
            log(logger, "直接读取文件失败(" + file + "): " + t);
            return null;
        }
    }

    private static void log(LspLogger logger, String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }
}
