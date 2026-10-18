package com.scala.android.project;

import com.rapid.api.lang.engine.DefaultLanguageEngine;
import com.rapid.api.lang.engine.LanguageContext;

/**
 * Scala 语言引擎。
 *
 * <ul>
 *   <li>补全 → {@link ScalaCompletionProvider}（关键字/片段/类型/覆写成员/语义补全）；</li>
 *   <li>诊断 → {@link ScalaLintProvider}（编译器的错误变成红线波浪线）；</li>
 *   <li>快速修复 → {@link ScalaCodeActionProvider}（实现抽象方法 / 添加 import）。</li>
 * </ul>
 */
public class ScalaLanguageEngine extends DefaultLanguageEngine {

    private static class Holder {
        static final ScalaLanguageEngine INSTANCE = new ScalaLanguageEngine();
    }

    public static ScalaLanguageEngine getInstance() {
        return Holder.INSTANCE;
    }

    private ScalaLanguageEngine() {
        setCompletion(new ScalaCompletionProvider());
        setLint(new ScalaLintProvider());
        // 同一套修复同时挂到两条通道：codeAction（快速修复）与 sourceGenerating（「生成代码」菜单）
        ScalaCodeActionProvider actions = new ScalaCodeActionProvider();
        setCodeAction(actions);
        setSourceGenerating(actions);
    }

    @Override
    public LanguageContext getContext() {
        // 补全只需要编辑器传来的 params（正文 + 光标），不需要额外上下文。
        return null;
    }
}
