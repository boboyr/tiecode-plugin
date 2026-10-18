package studio.basic.lang.xml.tree.impl;

import studio.basic.lang.xml.tree.Visitor;
import studio.basic.lang.xml.tree.XmlNamespace;

public class JXmlNamespace extends JXmlText implements XmlNamespace {
    public JXmlNamespace(String text) {
        super(text);
    }

    @Override
    public <Param, Result> Result accept(Visitor<Param, Result> visitor, Param param) {
        return visitor.visitNamespace(this, param);
    }
}
