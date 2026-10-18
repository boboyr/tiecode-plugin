package studio.basic.lang.xml.tree.impl;

import studio.basic.lang.xml.tree.Visitor;
import studio.basic.lang.xml.tree.XmlLiteral;

public class JXmlLiteral extends JXmlText implements XmlLiteral {
    public JXmlLiteral(String text) {
        super(text);
    }

    @Override
    public <Param, Result> Result accept(Visitor<Param, Result> visitor, Param param) {
        return visitor.visitLiteral(this, param);
    }
}
