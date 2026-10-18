package studio.basic.lang.xml.tree.impl;

import studio.basic.lang.xml.tree.Visitor;
import studio.basic.lang.xml.tree.XmlNamespace;
import studio.basic.lang.xml.tree.XmlNamespaceDeclare;

public class JXmlNamespaceDeclare extends JXmlAttribute implements XmlNamespaceDeclare {
    private final static JXmlNamespace NS_DECLARE = new JXmlNamespace("xmlns");

    @Override
    public <Param, Result> Result accept(Visitor<Param, Result> visitor, Param param) {
        return visitor.visitNamespaceDeclare(this, param);
    }

    @Override
    public XmlNamespace getNamespace() {
        return NS_DECLARE;
    }
}
