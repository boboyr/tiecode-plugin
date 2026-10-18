package studio.basic.lang.markdown.color;

import static com.rapid.api.component.widget.editor.HighlightTokenGenerator.generate;

import com.rapid.api.component.widget.editor.HighlightToken;

public interface MdHighlightToken extends HighlightToken {
    int HEADING = generate();
    int PUNCTUATION_HEADING = generate();
    int LINK = generate();
    int HTML_TAG = generate();

    String KEY_HEADING = "HEADING";
    String KEY_PUNCTUATION_HEADING = "PUNCTUATION_HEADING";
    String KEY_LINK = "LINK";
    String KEY_HTML_TAG = "HTML_TAG";
}
