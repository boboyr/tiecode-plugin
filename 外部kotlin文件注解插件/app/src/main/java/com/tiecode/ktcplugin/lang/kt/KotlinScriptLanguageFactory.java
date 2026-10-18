package com.tiecode.ktcplugin.lang.kt;

/**
 * Kotlin 脚本（{@code .kts}）语言工厂。
 *
 * <p>结绳的 {@code LanguageManager.findLanguageFactory} 是<b>精确等值</b>比较，
 * 一个工厂只能声明一个后缀，所以 {@code .kts} 需要单独一个工厂；否则即使
 * 文件能被编辑器打开，也是「无语言」状态——不高亮、不补全。
 *
 * <p>语言 id 仍然复用 {@link KotlinLanguage#ID}：引擎是按语言 id 取的，
 * 两个后缀共用同一个引擎（同一个 {@link KotlinLanguageEngine} 实例），
 * 不需要也不应该为脚本再注册一份补全能力。
 */
public class KotlinScriptLanguageFactory extends KotlinLanguageFactory {

    @Override
    public String getAssociateFileType() {
        return "kts";
    }
}
