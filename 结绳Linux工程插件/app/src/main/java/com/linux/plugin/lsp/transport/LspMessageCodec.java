package com.linux.plugin.lsp.transport;

/**
 * LSP 消息分帧编解码：{@code Content-Length: N\r\n\r\n<UTF-8 body>}。
 *
 * <p>对应 LSP 规范的 "Base Protocol"。允许附加头（如 {@code Content-Type}），
 * 头名大小写不敏感，未知头直接忽略。
 */
public final class LspMessageCodec {

    /** 单条消息的字节上限，防御性上限，避免恶意/损坏的 Content-Length 造成 OOM。 */
    private static final int MAX_MESSAGE_BYTES = 64 * 1024 * 1024;

    private LspMessageCodec() {
    }

    /**
     * 读取一条消息的正文。
     *
     * @return UTF-8 正文；对端正常关闭（EOF）时返回 null
     */
    public static String read(java.io.InputStream in) throws Exception {
        int contentLength = -1;

        while (true) {
            String line = readHeaderLine(in);
            if (line == null) {
                // 对端在两次消息之间关闭：正常的会话结束信号。
                return null;
            }
            if (line.isEmpty()) {
                // 空行 = 头结束。
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue; // 非法头，忽略
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ("Content-Length".equalsIgnoreCase(name)) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new java.io.IOException("非法的 Content-Length: " + value);
                }
            }
            // Content-Type 等其他头：LSP 一律 UTF-8，无需处理。
        }

        if (contentLength < 0) {
            throw new java.io.IOException("LSP 消息头缺少 Content-Length");
        }
        if (contentLength > MAX_MESSAGE_BYTES) {
            throw new java.io.IOException("LSP 消息过大: " + contentLength + " 字节");
        }

        byte[] buffer = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = in.read(buffer, read, contentLength - read);
            if (n < 0) {
                throw new java.io.EOFException("LSP 消息正文被截断: 期望 " + contentLength + " 字节，实到 " + read);
            }
            read += n;
        }
        return new String(buffer, 0, contentLength, "UTF-8");
    }

    /**
     * 读取一行头（以 {@code \r\n} 或 {@code \n} 结束），返回不含行尾的文本。
     *
     * @return null 表示流已到末尾
     */
    private static String readHeaderLine(java.io.InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder(32);
        int c = in.read();
        if (c < 0) {
            return null;
        }
        while (c >= 0) {
            if (c == '\n') {
                // 兼容 LF 与 CRLF，去掉可能存在的 '\r'。
                int length = sb.length();
                if (length > 0 && sb.charAt(length - 1) == '\r') {
                    sb.setLength(length - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
            c = in.read();
        }
        // 末尾没有换行：把已读到内容当作最后一行返回。
        return sb.toString();
    }

    /** 写出一条消息，自动补 Content-Length 头。调用方负责同步。 */
    public static void write(java.io.OutputStream out, String body) throws Exception {
        byte[] payload = body.getBytes("UTF-8");
        StringBuilder header = new StringBuilder(48);
        header.append("Content-Length: ").append(payload.length).append("\r\n");
        header.append("Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n");
        header.append("\r\n");
        out.write(header.toString().getBytes("US-ASCII"));
        out.write(payload);
        out.flush();
    }
}
