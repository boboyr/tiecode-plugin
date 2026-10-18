package com.goproject.plugin.project;

import com.rapid.api.lang.engine.DefaultLanguageEngine;
import com.rapid.api.lang.engine.LanguageContext;

public class GoLanguageEngine extends DefaultLanguageEngine{
	
    private static class Holder {
        final static GoLanguageEngine INSTANCE = new GoLanguageEngine();
    }

    public static GoLanguageEngine getInstance() {
        return Holder.INSTANCE;
    }

    private GoLanguageEngine() {
        setCompletion(new GoCompletionProvider());
    }
	
    public LanguageContext getContext() {
        return null;
    }

	
}
