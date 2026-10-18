package studio.basic.lang.xml;

import com.rapid.api.framework.project.lang.DefaultOperatorProvider;

public class XmlOperatorProvider extends DefaultOperatorProvider {
    public final static char[] XML_OPERATORS = {
            '<', '>', '/', ':', '=', '"', '\'',
            '@', '+', '-', '|', '?', '#', '%', '.'
    };

    @Override
    public char[] getOperators() {
        return XML_OPERATORS;
    }
}
