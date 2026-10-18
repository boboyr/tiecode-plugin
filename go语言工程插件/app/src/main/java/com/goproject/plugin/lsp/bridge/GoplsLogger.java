package com.goproject.plugin.lsp.bridge;

import android.util.Log;

import com.rapid.api.Platform;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 插件版日志出口：<b>同时</b>写 Logcat 与文件，便于没有 adb 时排查。
 *
 * <p>不再实现自建的 {@code LspLogger} 接口：协议层已整体换成 sora 的
 * {@code editor-lsp}，那套接口没有存在意义了。本类直接对外提供静态实例。
 *
 * <p>为什么不只写 Logcat：插件的调试往往在真机上进行，而 adb 不一定可用。
 * 因此这里额外落一份文件，并把它所在的路径记下来，
 * 用 {@link #dumpToConsole()} 可以一键在结绳的日志/控制台窗口里显示出来，
 * 直接长按复制即可。
 *
 * <p>文件位置优先用宿主提供的私有目录（{@code Platform.getSystemPath()}），
 * 拿不到时退回插件的缓存目录；两条路都失败就只写 Logcat。
 */
public final class GoplsLogger {

    /** Logcat 过滤用的 tag。 */
    public static final String TAG = "DshGoLsp";

    private static final String FILE_NAME = "gopls-lsp.log";

    /** 文件超过这个大小就清空重来，避免无限增长。 */
    private static final long MAX_FILE_BYTES = 512 * 1024L;

    private static final GoplsLogger INSTANCE = new GoplsLogger();

    private final Object fileLock = new Object();
    private final SimpleDateFormat timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private volatile File logFile;
    private volatile boolean fileResolved;
    private volatile boolean fileBroken;

    public static GoplsLogger get() {
        return INSTANCE;
    }

    private GoplsLogger() {
    }

    // ------------------------------------------------------------------
    // 对外
    // ------------------------------------------------------------------

    /** 日志文件；不可用时返回 null。 */
    public File logFile() {
        ensureFile();
        return logFile;
    }

    /**
     * 把日志内容打到结绳的控制台窗口，便于直接复制。
     *
     * @param maxLines 最多显示多少行（从末尾截取）
     */
    public void dumpToConsole(int maxLines) {
        String content = readLog(maxLines);
        if (content == null || content.isEmpty()) {
            content = "（暂无日志）";
        }
        File file = logFile();
        String header = file == null ? "" : ("日志文件: " + file.getAbsolutePath() + "\n\n");
        try {
            Platform.openConsole(header + content);
        } catch (Throwable t) {
            // 控制台不可用时退回 Logcat，保证信息不丢。
            info("控制台不可用，日志如下:\n" + content);
        }
    }

    /** 清空日志文件。 */
    public void clearFile() {
        synchronized (fileLock) {
            File file = logFile();
            if (file == null) {
                return;
            }
            try {
                new FileOutputStream(file, false).close();
            } catch (Throwable ignore) {
            }
        }
    }

    /** 读取日志文本（末尾 maxLines 行）。 */
    public String readLog(int maxLines) {
        File file = logFile();
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            byte[] bytes = readAll(file);
            String text = new String(bytes, "UTF-8");
            if (maxLines <= 0) {
                return text;
            }
            String[] lines = text.split("\n", -1);
            if (lines.length <= maxLines) {
                return text;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - maxLines; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 日志方法
    // ------------------------------------------------------------------

    public void info(String message) {
        write("I", message);
    }

    public void warning(String message) {
        write("W", message);
    }

    public void error(String message) {
        write("E", message);
    }

    public void error(String message, Throwable cause) {
        write("E", message + (cause == null ? "" : " : " + cause));
        if (cause != null) {
            write("E", stackToString(cause));
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void write(String level, String message) {
        try {
            if ("I".equals(level)) {
                Log.i(TAG, message);
            } else if ("W".equals(level)) {
                Log.w(TAG, message);
            } else {
                Log.e(TAG, message);
            }
        } catch (Throwable ignore) {
        }
        appendToFile(level, message);
    }

    private void appendToFile(String level, String message) {
        if (fileBroken) {
            return;
        }
        File file = logFile();
        if (file == null) {
            return;
        }
        synchronized (fileLock) {
            try {
                if (file.length() > MAX_FILE_BYTES) {
                    // 超限就重开，避免日志文件无限膨胀把磁盘占满。
                    new FileOutputStream(file, false).close();
                }
                Writer writer = new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8");
                try {
                    writer.write(timestamp.format(new Date()));
                    writer.write(' ');
                    writer.write(level);
                    writer.write(' ');
                    writer.write(message == null ? "null" : message);
                    writer.write('\n');
                    writer.flush();
                } finally {
                    writer.close();
                }
            } catch (Throwable t) {
                // 文件不可写就放弃文件通道，Logcat 仍在工作。
                fileBroken = true;
            }
        }
    }

    /** 解析日志文件位置，只做一次。 */
    private void ensureFile() {
        if (fileResolved) {
            return;
        }
        synchronized (fileLock) {
            if (fileResolved) {
                return;
            }
            fileResolved = true;
            try {
                String base = Platform.getSystemPath().getPrivatePath("log");
                File directory = new File(base);
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IllegalStateException("无法创建日志目录: " + base);
                }
                logFile = new File(directory, FILE_NAME);
            } catch (Throwable t) {
                // 退路：用应用缓存目录（同样不需要存储权限）。
                try {
                    android.content.Context context =
                            com.rapid.api.ext.plugin.AndroidPlugins.getContext(this);
                    logFile = new File(context.getCacheDir(), FILE_NAME);
                } catch (Throwable t2) {
                    logFile = null;
                    fileBroken = true;
                }
            }
        }
    }

    private static byte[] readAll(File file) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(file);
        try {
            long length = file.length();
            byte[] buffer = new byte[(int) Math.min(length, 256 * 1024L)];
            int read = 0;
            while (read < buffer.length) {
                int n = in.read(buffer, read, buffer.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read == buffer.length) {
                return buffer;
            }
            byte[] trimmed = new byte[read];
            System.arraycopy(buffer, 0, trimmed, 0, read);
            return trimmed;
        } finally {
            try {
                in.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static String stackToString(Throwable cause) {
        java.io.StringWriter writer = new java.io.StringWriter();
        java.io.PrintWriter printer = new java.io.PrintWriter(writer);
        cause.printStackTrace(printer);
        printer.flush();
        return writer.toString();
    }
}
