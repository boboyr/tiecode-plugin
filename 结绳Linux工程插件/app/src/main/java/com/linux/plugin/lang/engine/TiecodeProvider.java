package com.linux.plugin.lang.engine;

import com.rapid.api.lang.engine.AbstractProvider;

/** 结绳语言服务基类,持有共享的语言上下文。 */
public abstract class TiecodeProvider extends AbstractProvider<TiecodeLanguageContext> {

    public TiecodeProvider(TiecodeLanguageContext context) {
        super(context);
    }
}
