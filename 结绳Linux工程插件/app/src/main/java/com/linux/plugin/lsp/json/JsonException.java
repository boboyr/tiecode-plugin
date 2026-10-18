package com.linux.plugin.lsp.json;

/** JSON 解析失败。 */
public class JsonException extends Exception {

    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }

    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }
}
