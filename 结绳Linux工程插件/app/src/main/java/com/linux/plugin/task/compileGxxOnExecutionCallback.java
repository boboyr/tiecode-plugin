package com.linux.plugin.task;

import com.rapid.api.logging.Logger;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.project.toolchain.Tool.LogLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Gxx 编译输出回调:把工具链的每行输出转发到构建日志,并留存用于失败诊断。
 *
 * <p>原实现 {@code onPrintLine} 为空,导致 gxx 的编译错误被完全丢弃,
 * 只能看到“编译到目标平台失败”而看不到原因。
 */
public class compileGxxOnExecutionCallback implements Tool.OnExecutionCallback {

    /** 保留的最大输出行数,防止长输出占用过多内存。 */
    private static final int MAX_LINES = 500;

    private final Logger logger;
    private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

    public compileGxxOnExecutionCallback(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void onPrintLine(LogLevel logLevel, String string) {
        if (string == null) {
            return;
        }
        synchronized (lines) {
            if (lines.size() >= MAX_LINES) {
                lines.remove(0);
            }
            lines.add(string);
        }
        if (logger == null) {
            return;
        }
        if (logLevel == LogLevel.ERROR) {
            logger.error(string);
        } else if (logLevel == LogLevel.WARNING) {
            logger.warning(string);
        } else {
            logger.info(string);
        }
    }

    /** 汇总已捕获的输出,供失败时拼接错误信息。 */
    public String tail(int maxLines) {
        synchronized (lines) {
            int from = Math.max(0, lines.size() - Math.max(1, maxLines));
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.size(); i++) {
                sb.append(lines.get(i)).append('\n');
            }
            return sb.toString();
        }
    }
}
