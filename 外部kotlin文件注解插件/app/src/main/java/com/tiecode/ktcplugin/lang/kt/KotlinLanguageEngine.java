package com.tiecode.ktcplugin.lang.kt;

import com.rapid.api.lang.engine.DefaultLanguageEngine;
import com.rapid.api.lang.engine.LanguageContext;

/**
 * Kotlin 语言引擎：把补全等语言服务装配到结绳的引擎通道上。
 *
 * <p>注册方式见 {@code META-INF/program.json} 的
 * {@code project.language.engine} 扩展点，其中 {@code language} 必须等于
 * {@link KotlinLanguage#ID}（{@code "kotlin"}），否则编辑器按扩展名找到语言后
 * 取不到这个引擎，表现就是「高亮正常但补全永远不弹」。
 *
 * <p>目前装配的能力：
 * <ul>
 *   <li>{@code setCompletion} —— 关键字 / 片段 / 标准库 / 工程符号补全。</li>
 * </ul>
 * 语法高亮与符号配对由 {@link KotlinLanguage}（TextMate 语法）负责，不在这里。
 * 语义高亮没有装配：那需要真的解析类型（本插件不做类型分析），
 * 而 TextMate 语法已经覆盖了关键字、字符串、注释、数字等绝大多数着色场景。
 */
public class KotlinLanguageEngine extends DefaultLanguageEngine {

    private static final class Holder {
        static final KotlinLanguageEngine INSTANCE = new KotlinLanguageEngine();
    }

    public static KotlinLanguageEngine getInstance() {
        return Holder.INSTANCE;
    }

    public KotlinLanguageEngine() {
        setCompletion(new KotlinCompletionProvider(KotlinLanguageContext.getInstance()));
    }

    @Override
    public LanguageContext getContext() {
        return KotlinLanguageContext.getInstance();
    }
}
