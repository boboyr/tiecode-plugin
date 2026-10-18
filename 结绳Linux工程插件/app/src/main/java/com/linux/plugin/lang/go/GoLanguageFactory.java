package com.linux.plugin.lang.go;

import android.content.res.AssetManager;

import com.linux.plugin.lsp.LspLogger;
import com.rapid.api.Platform;
import com.rapid.api.lang.Language;
import com.rapid.api.lang.factory.TextMateLanguageFactory;

import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.IThemeSource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import com.rapid.api.ext.plugin.AndroidPlugins;

/**
 * Go 语言工厂：把 {@code .go} 文件关联到语言 id {@code "go"} 并给出语言实例。
 *
 * <p>结绳是这样定位引擎的：扩展名 → 工厂（{@code getAssociateFileType}）→
 * 语言 id（{@code getLanguageId}）→ 引擎（{@code LanguageManager.registerEngine}）。
 * 所以这个工厂是 gopls 能被用上的前提：没有它，{@code .go} 文件压根不会被
 * 当成 Go 语言打开，注册再好的引擎也没人调用。
 *
 * <p>语法资源缺失时返回 null（没有高亮），但<b>补全等语义功能不受影响</b>：
 * 引擎是按语言 id 从 {@code LanguageManager} 取的，与 Language 实例是否构造成功无关。
 * 这样单个语法文件出问题不会牵连整个 gopls 通道。
 */
public class GoLanguageFactory extends TextMateLanguageFactory {

    @Override
    public String getLanguageId() {
        return GoLanguage.ID;
    }

    @Override
    public String getAssociateFileType() {
        return "go";
    }

    @Override
    public Language newLanguage() {
        AssetManager assets = AndroidPlugins.getContext(this).getAssets();
        if (assets == null) {
            LspLogger.DEFAULT.warning("拿不到 AssetManager，Go 高亮降级为纯文本");
            return plainText();
        }
        try {
            IGrammarSource grammar = readGrammarSource(
                    assets.open(GoLanguage.GRAMMAR_ASSET),
                    "go.tmLanguage.json");
            Reader configuration = new InputStreamReader(
                    assets.open(GoLanguage.CONFIGURATION_ASSET), "UTF-8");
            IThemeSource theme = getThemeSource();
            return new GoLanguage(grammar, configuration, theme);
        } catch (IOException e) {
            LspLogger.DEFAULT.warning("加载 Go 语法失败，降级为纯文本: " + e.getMessage());
            return plainText();
        } catch (Throwable t) {
            LspLogger.DEFAULT.error("构造 Go 语言失败，降级为纯文本", t);
            return plainText();
        }
    }

    /** 拿 Activity 的 AssetManager；失败返回 null。 */
    private static AssetManager assets() {
        try {
            android.app.Activity activity = Platform.getActivity();
            return activity == null ? null : activity.getAssets();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 语法不可用时的兜底。
     *
     * <p>返回 null（无高亮）而不是硬塞一个残缺的 Language：
     * 残缺实现更容易在 Sora 编辑器内部触发意外行为，
     * 而"没有高亮"是用户一眼就能理解、且不影响补全的状态。
     */
    private static Language plainText() {
        return null;
    }
}
