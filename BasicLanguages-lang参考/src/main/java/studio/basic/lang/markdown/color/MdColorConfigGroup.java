package studio.basic.lang.markdown.color;

import com.rapid.api.component.widget.editor.BaseColorConfigGroup;
import com.rapid.api.component.widget.editor.ColorConfig;
import com.rapid.api.component.widget.editor.ColorConfigGroup;
import com.rapid.api.component.widget.editor.EditorTheme;
import com.rapid.api.component.widget.editor.SampleCode;
import com.rapid.android.ui.widget.editor.sweetline.SweetLineColorConfigGroup;
import com.rapid.android.ui.widget.editor.sweetline.SweetLineConfigItem;
import com.rapid.util.collection.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MdColorConfigGroup extends BaseColorConfigGroup implements SweetLineColorConfigGroup {
    private final static String SAMPLE_TEXT = "@markdown\n" +
            "#PUNCTUATION_HEADING###PUNCTUATION_HEADING###HEADING#Heading\n" +
            "#HTML_TAG#<br>\n" +
            "#LINK#[Link](http://xx.xx)";

    public MdColorConfigGroup() {
        addConfigItem(new ColorConfig(MdHighlightToken.HEADING,
                MdHighlightToken.KEY_HEADING, "标题头定义", ColorConfig.KEYWORD));
        addConfigItem(new ColorConfig(MdHighlightToken.PUNCTUATION_HEADING,
                MdHighlightToken.KEY_PUNCTUATION_HEADING, "标题头定义符号", ColorConfig.KEYWORD));
        addConfigItem(new ColorConfig(MdHighlightToken.LINK,
                MdHighlightToken.KEY_LINK, "超链接", ColorConfig.STRING));
        addConfigItem(new ColorConfig(MdHighlightToken.HTML_TAG,
                MdHighlightToken.KEY_HTML_TAG, "Html标签", ColorConfig.KEYWORD));
    }

    public MdColorConfigGroup(List<ColorConfig> colorConfigs) {
        super(colorConfigs);
    }

    @Override
    public String getParentId() {
        return ID_BASIC;
    }

    @Override
    public String getLanguageId() {
        return "markdown";
    }

    @Override
    public String getName() {
        return "Markdown";
    }

    @Override
    public SampleCode getSampleCode(EditorTheme editorTheme) {
        return SampleCode.fromCsml(SAMPLE_TEXT, editorTheme);
    }

    @Override
    public ColorConfigGroup copy() {
        List<ColorConfig> colorConfigs = CollectionUtils.deepCopy(items);
        return new MdColorConfigGroup(colorConfigs);
    }

    @Override
    public Map<String, SweetLineConfigItem> getSweetLineConfigMapping() {
        Map<String, SweetLineConfigItem> configs = new HashMap<>();
        configs.put(MdHighlightToken.KEY_HEADING, new SweetLineConfigItem(true, false, "markdown.heading"));
        configs.put(MdHighlightToken.KEY_PUNCTUATION_HEADING, new SweetLineConfigItem(true, false, "markdown.punctuation.heading"));
        configs.put(MdHighlightToken.KEY_LINK, new SweetLineConfigItem(false, true, "markdown.link"));
        configs.put(MdHighlightToken.KEY_HTML_TAG, new SweetLineConfigItem("markdown.html.tag"));
        return configs;
    }
}
