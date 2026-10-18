package studio.basic.lang.xml.tree.impl;

import studio.basic.lang.xml.tree.Visitor;
import studio.basic.lang.xml.tree.XmlIdentifier;

public class JXmlIdentifier extends JXmlText implements XmlIdentifier {
    public JXmlIdentifier(String text) {
        super(text);
    }

    @Override
    public <Param, Result> Result accept(Visitor<Param, Result> visitor, Param param) {
        return visitor.visitIdentifier(this, param);
    }
}
