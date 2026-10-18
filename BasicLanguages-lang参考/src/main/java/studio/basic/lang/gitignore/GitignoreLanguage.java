package studio.basic.lang.gitignore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

public class GitignoreLanguage extends Language {
    public final static String ID = "gitignore";
    private GitignoreAnalyzer analyzer = new GitignoreAnalyzer();

    @Override
    public String getId() {
        return ID;
    }

    @NonNull
    @Override
    public AnalyzeManager getAnalyzeManager() {
        return analyzer;
    }

    @Override
    public int getInterruptionLevel() {
        return Language.INTERRUPTION_LEVEL_STRONG;
    }

    @Override
    public int getIndentAdvance(@NonNull ContentReference content, int line, int column) {
        return 0;
    }

    @Override
    public boolean useTab() {
        return true;
    }

    @Override
    public Formatter getFormatter() {
        return new EmptyLanguage.EmptyFormatter();
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return new SymbolPairMatch.DefaultSymbolPairs();
    }

    @Nullable
    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return null;
    }
}
