package com.linux.plugin.lang.engine;

import com.linux.plugin.lang.engine.completion.TieCompletionProvider;
import com.linux.plugin.lang.engine.highlight.TieHighlightProvider;

import com.rapid.api.lang.engine.DefaultLanguageEngine;
import com.rapid.api.lang.engine.LanguageContext;

/**
 * 结绳语言引擎:装配补全、语义高亮等语言服务。
 *
 * <p>所有服务共享 {@link TiecodeLanguageContext} 中的编译器 IDE 服务实例。
 */
public class TieLanguageEngine extends DefaultLanguageEngine {

    private static final class Holder {
        static final TieLanguageEngine INSTANCE = new TieLanguageEngine();
    }

    public static TieLanguageEngine getInstance() {
        return Holder.INSTANCE;
    }

    public TieLanguageEngine() {
        TiecodeLanguageContext context = TiecodeLanguageContext.getInstance();
        setCompletion(new TieCompletionProvider(context));
        setHighlight(new TieHighlightProvider(context));
    }

    @Override
    public LanguageContext getContext() {
        return TiecodeLanguageContext.getInstance();
    }
}
