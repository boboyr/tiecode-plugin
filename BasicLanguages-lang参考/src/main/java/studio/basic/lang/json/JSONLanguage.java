package studio.basic.lang.json;

import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.EOF;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.LBRACE;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.LBRACK;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.RBRACE;
import static com.rapid.api.lang.engine.highlight.token.IdentifierToken.RBRACK;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.rapid.api.lang.engine.highlight.token.IdentifierTokenizer;

import java.io.IOException;

import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

public class JSONLanguage extends Language {
    public final static String ID = "json";
    private JSONAnalyzer analyzer = new JSONAnalyzer();
    private JSONNewLineHandler[] newLineHandlers = {new JSONNewLineHandler()};

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
        int width = 0;
        IdentifierTokenizer tokenizer = new IdentifierTokenizer(content.getLine(line));
        int token;
        loop:
        while (true) {
            try {
                if ((token = tokenizer.yylex()) == EOF) break;
                switch (token) {
                    case LBRACK:
                    case LBRACE:
                        width += 4;
                        break loop;
                    case RBRACK:
                    case RBRACE:
                        //width--;
                        break loop;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return width;
    }

    @Override
    public boolean useTab() {
        return false;
    }

    @Override
    public Formatter getFormatter() {
        return new JSONFormatter();
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return new SymbolPairMatch.DefaultSymbolPairs();
    }

    @Nullable
    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return newLineHandlers;
    }
}
