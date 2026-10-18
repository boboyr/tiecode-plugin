package com.goproject.plugin.lang;

import android.os.Bundle;
import android.util.Log;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;

/**
 * Go 编辑器语言包装。
 *
 * <p>补全只保留宿主语言引擎这一条路径:宿主的
 * {@code Language.requireAutoComplete()} 会调用 {@code GoLanguageEngine},
 * 再由 {@code GoCompletionProvider} 对接 gopls,并由宿主的
 * {@code IpeSimpleCompletionItem}/{@code IpeSnippetCompletionItem} 统一应用
 * {@code textEdit} 与 {@code additionalTextEdits}(自动导包)。
 *
 * <p>这里不能再次直接请求 gopls 或发布另一套 Sora 补全项,否则同一个补全选择
 * 会经过两套提交逻辑,容易重复应用编辑并破坏 import 块。
 */
public class GoTexLanguage extends EmptyLanguage {

    public static final String ID = "go";

    private static final String TAG = "DshGoLsp";

    private final TextMateLanguage textMateLanguage;

    public GoTexLanguage(TextMateLanguage textMateLanguage) {
        this.textMateLanguage = textMateLanguage;
    }

    @Override
    public void requireAutoComplete(ContentReference content, CharPosition position,
                                    CompletionPublisher publisher, Bundle extraArguments)
            throws CompletionCancelledException {
        // 唯一的语义补全入口:宿主引擎 -> GoCompletionProvider -> gopls/离线兜底。
        super.requireAutoComplete(content, position, publisher, extraArguments);

        // TextMate 只负责词法/普通补全;不再在此处重复请求 gopls。
        if (textMateLanguage != null) {
            try {
                textMateLanguage.requireAutoComplete(content, position, publisher, extraArguments);
            } catch (Exception exception) {
                Log.e(TAG, "TextMate 补全调用失败", exception);
            }
        }
    }

    @Override
    public boolean needsCompletion(ContentReference content, CharPosition position) {
        if (content == null || position == null || position.index <= 0) {
            return false;
        }
        char previous = content.charAt(position.index - 1);
        if (Character.isWhitespace(previous)) {
            return false;
        }
        return isCompletionChar(previous);
    }

    /** 触发补全的字符:{@code .} 与 Go 标识符字符。 */
    @Override
    public boolean isCompletionChar(char ch) {
        if (Character.isWhitespace(ch)) {
            return false;
        }
        return ch == '.' || Character.isJavaIdentifierPart(ch);
    }

    @Override
    public AnalyzeManager getAnalyzeManager() {
        return textMateLanguage != null
                ? textMateLanguage.getAnalyzeManager()
                : new EmptyAnalyzeManager();
    }

    @Override
    public String getId() {
        return ID;
    }
}
