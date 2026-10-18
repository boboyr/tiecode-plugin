package com.linux.plugin.lsp.transport;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 子进程 stdio 通道：把语言服务端当外部进程拉起，走标准输入输出交谈。
 *
 * <p>这是 LSP 最常见的形态（{@code clangd --stdio} 等）。
 * 注意 Android 上 fork/exec 受系统限制，可执行文件需要放在应用可执行的位置
 * （如 {@code nativeLibraryDir} 或已解压的可写目录）。
 */
public final class StdioTransport implements Transport {

    private final String[] command;
    private final String workingDirectory;
    private final Object lock = new Object();

    private Process process;
    private OutputStream output;
    private InputStream input;
    private Thread errorThread;
    private volatile boolean open;

    /**
     * @param command          完整命令行，第一个元素为可执行文件路径
     * @param workingDirectory 子进程工作目录，null 表示继承当前目录
     */
    public StdioTransport(String[] command, String workingDirectory) {
        if (command == null || command.length == 0) {
            throw new IllegalArgumentException("命令行不能为空");
        }
        this.command = command.clone();
        this.workingDirectory = workingDirectory;
    }

    public StdioTransport(List<String> command, String workingDirectory) {
        this(command == null ? null : command.toArray(new String[0]), workingDirectory);
    }

    @Override
    public void open() throws Exception {
        synchronized (lock) {
            if (open) {
                throw new IllegalStateException("通道已打开: " + describe());
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDirectory != null) {
                builder.directory(new java.io.File(workingDirectory));
            }
            // stderr 单独抽干，不能合并进 stdout，否则会污染 LSP 帧。
            builder.redirectErrorStream(false);
            builder.redirectInput(ProcessBuilder.Redirect.PIPE);
            builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
            process = builder.start();
            output = process.getOutputStream();
            input = process.getInputStream();
            open = true;
        }
    }

    @Override
    public InputStream input() {
        return input;
    }

    @Override
    public OutputStream output() {
        return output;
    }

    @Override
    public boolean isOpen() {
        if (!open) {
            return false;
        }
        Process current = process;
        if (current == null) {
            return false;
        }
        // 进程已退出且流不可用则视为断开。
        try {
            current.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    @Override
    public void redirectError(final LogListener listener) {
        final Process current = process;
        if (current == null) {
            return;
        }
        errorThread = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(current.getErrorStream(), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (listener != null) {
                            listener.onLog(line);
                        }
                    }
                } catch (Exception ignore) {
                    // 进程退出/流关闭属于正常路径。
                } finally {
                    closeQuietly(reader);
                }
            }
        }, "lsp-stderr");
        errorThread.setDaemon(true);
        errorThread.start();
    }

    @Override
    public void shutdown() {
        Process current;
        Thread thread;
        synchronized (lock) {
            current = process;
            thread = errorThread;
            process = null;
            errorThread = null;
            open = false;
            closeQuietly(input);
            closeQuietly(output);
            input = null;
            output = null;
        }
        if (current != null) {
            current.destroy();
            // 给进程一点时间体面退出，超时再强杀。
            try {
                if (!current.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    current.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public String describe() {
        return "stdio[" + join(command) + "]";
    }

    private static String join(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignore) {
        }
    }

    /** 常用默认命令：{@code clangd --stdio}。 */
    public static StdioTransport clangd(String executable, String workingDirectory, String... extraArgs) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--stdio");
        Collections.addAll(command, extraArgs);
        return new StdioTransport(command, workingDirectory);
    }
}
