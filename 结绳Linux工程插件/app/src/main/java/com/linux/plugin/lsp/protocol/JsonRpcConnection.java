package com.linux.plugin.lsp.protocol;

import com.linux.plugin.lsp.LspLogger;
import com.linux.plugin.lsp.json.Json;
import com.linux.plugin.lsp.json.JsonException;
import com.linux.plugin.lsp.transport.LspMessageCodec;
import com.linux.plugin.lsp.transport.Transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JSON-RPC 2.0 over LSP 传输通道。
 *
 * <p>职责边界很窄，只做四件事：
 * <ol>
 *   <li>请求 / 通知 / 响应的收发与 id 关联；</li>
 *   <li>单条读线程按帧读取消息并按类型分发；</li>
 *   <li>阻塞式 {@code request()} 与异步 {@code notify()}；</li>
 *   <li>连接断开时唤醒所有挂起请求，避免调用方永久阻塞。</li>
 * </ol>
 *
 * <p>LSP 语义（initialize、能力协商、文档同步……）都在 {@link com.linux.plugin.lsp.LspClient}。
 */
public final class JsonRpcConnection {

    /** 服务端主动发来的请求处理器。返回值会作为 result 回应；抛异常则回 error。 */
    public interface RequestHandler {
        Object handle(String method, Map<String, Object> params) throws Exception;
    }

    /** 通知处理器的便捷接口（无需返回值）。 */
    public interface NotificationHandler {
        void handle(String method, Map<String, Object> params);
    }

    /** 请求失败：服务端返回 error，或连接中断。 */
    public static class RpcException extends Exception {

        private static final long serialVersionUID = 1L;

        private final int code;
        private final String rpcMethod;

        public RpcException(int code, String message, String rpcMethod) {
            super(message);
            this.code = code;
            this.rpcMethod = rpcMethod;
        }

        public RpcException(String message, String rpcMethod) {
            this(-1, message, rpcMethod);
        }

        public int getCode() {
            return code;
        }

        public String getRpcMethod() {
            return rpcMethod;
        }
    }

    /** 单条请求的挂起状态。 */
    private static final class Pending {

        final CountDownLatch latch = new CountDownLatch(1);
        volatile Object result;
        volatile RpcException error;
    }

    private final TransportFactory transportFactory;
    private final LspLogger logger;
    private final Object writeLock = new Object();
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    private volatile Transport transport;
    private volatile Thread readThread;
    private volatile boolean running;
    private volatile RequestHandler requestHandler;
    private volatile NotificationHandler notificationHandler;
    private volatile Runnable disconnectHandler;
    private volatile Transport.LogListener serverErrorListener;

    public JsonRpcConnection(TransportFactory transportFactory, LspLogger logger) {
        if (transportFactory == null) {
            throw new IllegalArgumentException("transportFactory 不能为空");
        }
        this.transportFactory = transportFactory;
        this.logger = logger == null ? LspLogger.DEFAULT : logger;
    }

    public void setRequestHandler(RequestHandler handler) {
        this.requestHandler = handler;
    }

    public void setNotificationHandler(NotificationHandler handler) {
        this.notificationHandler = handler;
    }

    /** 读线程因任何原因退出时回调，用于上层标记不可用/触发重启。 */
    public void setDisconnectHandler(Runnable handler) {
        this.disconnectHandler = handler;
    }

    /**
     * 接收服务端 stderr。
     *
     * <p>必须在 {@link #start()} 之前设置：stdio 形态下 stderr 需要在进程刚拉起时
     * 就接上抽干线程，否则服务端日志写满管道会把进程卡死。
     */
    public void setServerErrorListener(Transport.LogListener listener) {
        this.serverErrorListener = listener;
    }

    /** 建立通道并启动读线程；同一条连接只允许 start 一次。 */
    public void start() throws Exception {
        synchronized (writeLock) {
            if (running) {
                throw new IllegalStateException("连接已启动");
            }
            Transport created = transportFactory.create();
            created.open();
            if (serverErrorListener != null) {
                created.redirectError(serverErrorListener);
            }
            transport = created;
            running = true;
        }

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "lsp-reader");
        thread.setDaemon(true);
        readThread = thread;
        thread.start();
    }

    public boolean isRunning() {
        return running;
    }

    /** 通道描述，便于日志定位。 */
    public String describeTransport() {
        Transport current = transport;
        return current == null ? "(未连接)" : current.describe();
    }

    /** 发送通知（无 id，不等回复）。 */
    public void notify(String method, Object params) {
        Map<String, Object> message = Json.obj("jsonrpc", "2.0", "method", method);
        if (params != null) {
            message.put("params", params);
        }
        send(message, method);
    }

    /** 发送请求并阻塞等待结果。 */
    public Object request(String method, Object params) throws RpcException {
        return request(method, params, 0L);
    }

    /**
     * 发送请求并阻塞等待结果。
     *
     * @param timeoutMillis 超时毫秒；&le;0 表示不超时
     */
    public Object request(String method, Object params, long timeoutMillis) throws RpcException {
        if (!running) {
            throw new RpcException("连接未启动，无法请求 " + method, method);
        }
        long id = nextId.getAndIncrement();
        Pending slot = new Pending();
        pending.put(Long.valueOf(id), slot);

        Map<String, Object> message = Json.obj("jsonrpc", "2.0", "id", Long.valueOf(id), "method", method);
        if (params != null) {
            message.put("params", params);
        }
        try {
            send(message, method);
        } catch (RuntimeException e) {
            pending.remove(Long.valueOf(id));
            throw new RpcException("发送请求失败: " + e.getMessage(), method);
        }

        try {
            if (timeoutMillis > 0) {
                if (!slot.latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    pending.remove(Long.valueOf(id));
                    // 告诉服务端别再算了，避免结果回来时无人认领。
                    cancel(id);
                    throw new RpcException("请求超时(" + timeoutMillis + "ms): " + method, method);
                }
            } else {
                slot.latch.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(Long.valueOf(id));
            cancel(id);
            throw new RpcException("请求被中断: " + method, method);
        }

        if (slot.error != null) {
            throw slot.error;
        }
        return slot.result;
    }

    /** 向服务端发送 {@code $/cancelRequest}。 */
    public void cancel(long id) {
        try {
            notify("$/cancelRequest", Json.obj("id", Long.valueOf(id)));
        } catch (RuntimeException ignore) {
            // 连接已断时忽略。
        }
    }

    /** 关闭连接：停止读线程、唤醒挂起请求、关闭通道。幂等。 */
    public void close() {
        running = false;
        Transport current = transport;
        transport = null;
        if (current != null) {
            current.shutdown();
        }
        Thread thread = readThread;
        if (thread != null) {
            thread.interrupt();
            readThread = null;
        }
        failAllPending("连接已关闭");
    }

    /** 写出原始消息（已序列化的 JSON 文本），供测试与调试使用。 */
    public void sendRaw(String body) {
        sendText(body, "(raw)");
    }
    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private void send(Map<String, Object> message, String method) {
        sendText(Json.write(message), method);
    }

    private void sendText(String body, String method) {
        Transport current = transport;
        if (current == null) {
            throw new IllegalStateException("通道不可用，无法发送: " + method);
        }
        try {
            synchronized (writeLock) {
                LspMessageCodec.write(current.output(), body);
            }
        } catch (Exception e) {
            throw new IllegalStateException("写出消息失败(" + method + "): " + e, e);
        }
    }

    private void readLoop() {
        String reason = "对端关闭";
        try {
            Transport current = transport;
            while (running && current != null) {
                String body = LspMessageCodec.read(current.input());
                if (body == null) {
                    break;
                }
                dispatch(body);
                current = transport;
            }
        } catch (Throwable t) {
            reason = String.valueOf(t);
            if (running) {
                logger.warning("LSP 读线程结束: " + t);
            }
        } finally {
            boolean wasRunning = running;
            running = false;
            failAllPending("连接中断(" + reason + ")");
            if (wasRunning) {
                logger.warning("LSP 连接已断开: " + reason);
            }
            Runnable handler = disconnectHandler;
            if (handler != null) {
                try {
                    handler.run();
                } catch (Throwable t) {
                    logger.error("断开回调失败", t);
                }
            }
        }
    }

    private void dispatch(String body) {
        Map<String, Object> message;
        try {
            message = Json.readObject(body);
        } catch (JsonException e) {
            logger.error("无法解析服务端消息: " + e.getMessage());
            return;
        }

        Object idValue = message.get("id");
        boolean hasId = idValue != null;
        String method = Json.string(message, "method", null);
        Object paramsValue = message.get("params");
        Map<String, Object> params = Json.object(paramsValue);

        if (method == null) {
            // 响应。
            if (!hasId) {
                logger.warning("收到既无 method 也无 id 的消息，已忽略");
                return;
            }
            handleResponse(idValue, message);
            return;
        }

        // 服务端请求（有 id，需要回应）或通知（无 id）。
        if (!hasId) {
            handleNotification(method, params == null ? new java.util.LinkedHashMap<String, Object>() : params);
            return;
        }
        handleServerRequest(idValue, method, params);
    }

    private void handleResponse(Object idValue, Map<String, Object> message) {
        Pending slot = pendingIdOf(idValue);
        if (slot == null) {
            // 可能是已经超时/取消的请求，忽略。
            return;
        }
        Map<String, Object> error = Json.object(message, "error");
        if (error != null) {
            int code = Json.integer(error, "code", -1);
            String text = Json.string(error, "message", "未知错误");
            slot.error = new RpcException(code, text, null);
        } else {
            slot.result = message.get("result");
        }
        slot.latch.countDown();
    }

    private Pending pendingIdOf(Object idValue) {
        Long id = null;
        if (idValue instanceof Number) {
            id = Long.valueOf(((Number) idValue).longValue());
        } else if (idValue instanceof String) {
            try {
                id = Long.valueOf(Long.parseLong((String) idValue));
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        if (id == null) {
            return null;
        }
        return pending.remove(id);
    }

    private void handleNotification(String method, Map<String, Object> params) {
        NotificationHandler handler = notificationHandler;
        if (handler == null) {
            return;
        }
        try {
            handler.handle(method, params);
        } catch (Throwable t) {
            logger.error("处理通知失败: " + method, t);
        }
    }

    private void handleServerRequest(Object idValue, String method, Map<String, Object> params) {
        RequestHandler handler = requestHandler;
        Map<String, Object> response = Json.obj("jsonrpc", "2.0", "id", idValue);
        if (handler == null) {
            response.put("error", Json.obj("code", Integer.valueOf(-32601), "message", "不支持的方法: " + method));
        } else {
            try {
                Object result = handler.handle(method, params);
                response.put("result", result);
            } catch (Exception e) {
                response.put("error", Json.obj(
                        "code", Integer.valueOf(-32603),
                        "message", String.valueOf(e.getMessage())));
            }
        }
        try {
            send(response, method);
        } catch (RuntimeException e) {
            logger.warning("回应服务端请求失败: " + method);
        }
    }

    private void failAllPending(String reason) {
        List<Long> ids = new ArrayList<>(pending.keySet());
        Collections.sort(ids);
        for (Long id : ids) {
            Pending slot = pending.remove(id);
            if (slot == null) {
                continue;
            }
            slot.error = new RpcException(reason, null);
            slot.latch.countDown();
        }
    }
}
