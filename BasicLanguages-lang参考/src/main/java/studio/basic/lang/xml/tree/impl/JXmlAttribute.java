package studio.basic.lang.xml.tree.impl;

import studio.basic.lang.xml.tree.Visitor;
import studio.basic.lang.xml.tree.XmlAttribute;
import studio.basic.lang.xml.tree.XmlLiteral;
import studio.basic.lang.xml.tree.XmlNamespace;
import studio.basic.lang.xml.tree.XmlIdentifier;

public class JXmlAttribute extends JXmlTree implements XmlAttribute {
    public JXmlNamespace namespace;
    public JXmlIdentifier name;
    public JXmlLiteral value;

    @Override
    public <Param, Result> Result accept(Visitor<Param, Result> visitor, Param param) {
        return visitor.visitAttribute(this, param);
    }

    @Override
    public XmlNamespace getNamespace() {
        return namespace;
    }

    @Override
    public XmlIdentifier getName() {
        return name;
    }

    @Override
    public XmlLiteral getValue() {
        return value;
    }
}
