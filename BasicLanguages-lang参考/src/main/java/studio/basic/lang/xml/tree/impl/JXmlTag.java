package studio.basic.lang.xml.tree.impl;

import studio.basic.lang.xml.tree.Visitor;
import studio.basic.lang.xml.tree.XmlTag;

public class JXmlTag extends JXmlText implements XmlTag {
    public JXmlTag(String text) {
        super(text);
    }

    @Override
    public <Param, Result> Result accept(Visitor<Param, Result> visitor, Param param) {
        return visitor.visitTag(this, param);
    }
}
