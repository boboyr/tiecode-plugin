package com.scala.android.tool;

import android.content.Context;

import com.rapid.api.logging.Logger;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.util.file.FileUtils;

import java.io.File;
import java.io.IOException;

/**
 * Scala 构建日志。
 *
 * <p>编译/D8 的输出除了进宿主构建日志外，还会落一份到
 * {@code <filesDir>/env/scala/build.log}，方便「Scala 构建日志」菜单随时回看
 * ——宿主构建面板会被后续输出刷掉，出问题时光看它是找不到根因的。
 */
public final class ScalaBuildLog {

    private static final String FILE_NAME = "build.log";
    private static final long MAX_BYTES = 256L * 1024L;
    private static final Object LOCK = new Object();

    private ScalaBuildLog() {
    }

    public static File logFile(Context context) {
        return new File(ScalaToolchain.envDir(context), FILE_NAME);
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            try {
                FileUtils.writeFile("", logFile(context));
            } catch (IOException ignored) {
                // 日志清不掉不影响构建。
            }
        }
    }

    public static void append(Context context, String text) {
        if (context == null || text == null) {
            return;
        }
        synchronized (LOCK) {
            File file = logFile(context);
            FileUtils.appendWrite(text + "\n", file);
            try {
                if (file.length() > MAX_BYTES) {
                    String all = FileUtils.readFile(file);
                    int keepFrom = Math.max(0, all.length() - (int) (MAX_BYTES / 2));
                    FileUtils.writeFile(all.substring(keepFrom), file);
                }
            } catch (Throwable ignored) {
                // 截断失败顶多让日志文件大一点。
            }
        }
    }

    /** 读取日志尾部，供菜单展示。 */
    public static String tail(Context context, int maxLines) {
        try {
            File file = logFile(context);
            if (!file.isFile()) {
                return "";
            }
            String all = FileUtils.readFile(file);
            String[] lines = all.split("\n");
            if (lines.length <= maxLines) {
                return all;
            }
            StringBuilder builder = new StringBuilder();
            for (int i = lines.length - maxLines; i < lines.length; i++) {
                builder.append(lines[i]).append('\n');
            }
            return builder.toString();
        } catch (Throwable error) {
            return "读取日志失败: " + error;
        }
    }

    /**
     * 生成一个把日志同时送往宿主日志和插件日志文件的回调。
     */
    public static Tool.OnExecutionCallback callback(final Context context, final Logger logger) {
        return new Tool.OnExecutionCallback() {
            @Override
            public void onPrintLine(Tool.LogLevel logLevel, String line) {
                if (line == null) {
                    return;
                }
                if (logger != null) {
                    if (logLevel == Tool.LogLevel.ERROR) {
                        logger.error(line);
                    } else if (logLevel == Tool.LogLevel.WARNING) {
                        logger.warning(line);
                    } else {
                        logger.info(line);
                    }
                }
                append(context, line);
            }
        };
    }
}
