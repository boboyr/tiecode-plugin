package com.linux.plugin.lsp.protocol;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** file:// URI 与本地路径之间的互转，以及 LSP URI 归一化。 */
public final class Uris {

    private static final String FILE_SCHEME = "file";

    private Uris() {
    }

    /** 本地文件 → {@code file:///...} URI（路径分段正确百分号转义）。 */
    public static String fromFile(File file) {
        if (file == null) {
            return null;
        }
        return fromPath(file.getAbsolutePath());
    }

    /** 本地绝对路径 → {@code file:///...} URI。 */
    public static String fromPath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        StringBuilder sb = new StringBuilder("file://");
        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            sb.append('/').append(percentEncode(segment));
        }
        if (sb.length() == 7) {
            // 只有 "file://"，说明是根路径。
            return "file:///";
        }
        return sb.toString();
    }

    /**
     * {@code file://} URI → 本地路径。
     *
     * @return 非 file scheme 或解析失败时返回 null
     */
    public static String toPath(String uri) {
        if (uri == null) {
            return null;
        }
        try {
            URI parsed = new URI(uri);
            if (!FILE_SCHEME.equalsIgnoreCase(parsed.getScheme())) {
                return null;
            }
            return new File(parsed).getAbsolutePath();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code file://} URI → {@link File}。 */
    public static File toFile(String uri) {
        String path = toPath(uri);
        return path == null ? null : new File(path);
    }

    /** 取 URI 的扩展名（带点、小写）；无扩展名返回空串。 */
    public static String extensionOf(String uriOrPath) {
        if (uriOrPath == null) {
            return "";
        }
        String text = uriOrPath;
        int query = text.indexOf('?');
        if (query >= 0) {
            text = text.substring(0, query);
        }
        int slash = text.lastIndexOf('/');
        String name = slash >= 0 ? text.substring(slash + 1) : text;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** 按扩展名推断 LSP {@code languageId}。 */
    public static String languageIdOf(String uriOrPath) {
        String extension = extensionOf(uriOrPath);
        switch (extension) {
            case ".c":
                return "c";
            case ".h":
                return "c"; // .h 归给 C，clangd 自身会按编译数据库判定
            case ".cpp":
            case ".cc":
            case ".cxx":
            case ".c++":
            case ".hpp":
            case ".hh":
            case ".hxx":
            case ".h++":
            case ".inl":
            case ".ipp":
                return "cpp";
            case ".t":
                return "tie";
            case ".java":
                return "java";
            case ".kt":
                return "kotlin";
            case ".py":
                return "python";
            case ".js":
                return "javascript";
            case ".ts":
                return "typescript";
            case ".go":
                return "go";
            case ".rs":
                return "rust";
            case ".json":
                return "json";
            case ".md":
                return "markdown";
            default:
                return extension.isEmpty() ? "plaintext" : extension.substring(1);
        }
    }

    /**
     * 归一化 URI 以便做键比较：解码后重新编码、统一大小写不变的部分。
     *
     * <p>服务端回传的 URI 与本地拼出来的可能只在转义细节上不同，
     * 直接字符串比较会漏掉匹配。
     */
    public static String normalize(String uri) {
        if (uri == null) {
            return null;
        }
        String path = toPath(uri);
        if (path != null) {
            return fromPath(path);
        }
        try {
            return new URI(uri).normalize().toString();
        } catch (URISyntaxException e) {
            return uri;
        }
    }

    private static String percentEncode(String segment) {
        try {
            byte[] bytes = segment.getBytes("UTF-8");
            StringBuilder sb = new StringBuilder(bytes.length);
            for (byte b : bytes) {
                int value = b & 0xFF;
                char c = (char) value;
                if (isUnreserved(c)) {
                    sb.append(c);
                } else {
                    sb.append('%').append(String.format("%02X", value));
                }
            }
            return sb.toString();
        } catch (UnsupportedEncodingException e) {
            // UTF-8 一定存在。
            return segment;
        }
    }

    /** RFC 3986 unreserved：ALPHA / DIGIT / "-" / "." / "_" / "~"。 */
    private static boolean isUnreserved(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }
}
