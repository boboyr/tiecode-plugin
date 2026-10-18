package studio.basic.lang.xml.tree.impl;

import studio.basic.lang.xml.tree.Visitor;
import studio.basic.lang.xml.tree.XmlText;

public class JXmlText extends JXmlTree implements XmlText {
    public String text;

    public JXmlText(String text) {
        this.text = text;
    }

    @Override
    public <Param, Result> Result accept(Visitor<Param, Result> visitor, Param param) {
        return visitor.visitText(this, param);
    }

    @Override
    public String getText() {
        return text;
    }
}
