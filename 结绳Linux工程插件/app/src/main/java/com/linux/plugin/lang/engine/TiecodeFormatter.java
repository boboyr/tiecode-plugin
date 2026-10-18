package com.linux.plugin.lang.engine;

import cn.tiecode.compiler.TCIDEService;

import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.TextRange;

/** 结绳格式化器:委托编译器 {@link TCIDEService#formatText} 完成代码格式化。 */
public class TiecodeFormatter implements Formatter {

    private volatile boolean running;
    private FormatResultReceiver receiver;

    @Override
    public void format(Content text, TextRange cursorRange) {
        running = true;
        try {
            String formatted = TCIDEService.formatText(text.toString());
            if (receiver != null && formatted != null) {
                receiver.onFormatSucceed(formatted, cursorRange);
            }
        } catch (Throwable t) {
            if (receiver != null) {
                receiver.onFormatFail(t);
            }
        } finally {
            running = false;
        }
    }

    @Override
    public void formatRegion(Content text, TextRange rangeToFormat, TextRange cursorRange) {
        format(text, cursorRange);
    }

    @Override
    public void setReceiver(FormatResultReceiver receiver) {
        this.receiver = receiver;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 必须显式实现该接口默认方法。
     *
     * <p>宿主的 sora 运行时不提供 {@code Formatter$-CC} 伴生类;若本类不覆盖该方法,
     * D8 会生成指向 {@code Formatter$-CC} 的转发桥接,触发
     * {@code NoClassDefFoundError: io/github/rosemoe/sora/lang/format/Formatter$-CC}。
     */
    @Override
    public void cancel() {
        running = false;
    }

    @Override
    public void destroy() {
        running = false;
    }
}
