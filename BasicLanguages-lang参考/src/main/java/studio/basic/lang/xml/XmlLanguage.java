package studio.basic.lang.xml;

import androidx.annotation.NonNull;

import com.rapid.android.ui.widget.editor.sweetline.SweetLineLanguage;
import com.rapid.api.lang.engine.highlight.token.IdentifierToken;
import com.rapid.api.lang.engine.highlight.token.IdentifierTokenizer;

import java.io.IOException;

import io.github.rosemoe.sora.text.ContentReference;

public class XmlLanguage extends SweetLineLanguage {
    public static final String ID = "xml";

    public XmlLanguage() {
        super(ID, ID, false);
    }

    @Override
    public int getIndentAdvance(@NonNull ContentReference content, int line, int column) {
        int width = 0;
        IdentifierTokenizer tokenizer = new IdentifierTokenizer(content.getLine(line));
        int token;
        loop:
        while (true) {
            try {
                if ((token = tokenizer.yylex()) == IdentifierToken.EOF) break;
                switch (token) {
                    case IdentifierToken.LT:
                        width += 4;
                        break loop;
                    case IdentifierToken.RBRACE:
                        break loop;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return width;
    }

    @Override
    public String getId() {
        return ID;
    }
}
