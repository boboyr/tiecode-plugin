package studio.basic.lang.markdown;

import com.rapid.android.ui.widget.editor.sweetline.SweetLineLanguage;

public class MarkdownLanguage extends SweetLineLanguage {
    public final static String ID = "markdown";

    protected MarkdownLanguage() {
        super(ID, ID, true);
    }

    @Override
    public boolean useTab() {
        return true;
    }

    @Override
    public String getId() {
        return ID;
    }
}
