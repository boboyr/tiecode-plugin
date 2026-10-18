package studio.basic.lang.xml.tree.impl;

import java.util.List;

import studio.basic.lang.xml.tree.Visitor;
import studio.basic.lang.xml.tree.XmlAttribute;
import studio.basic.lang.xml.tree.XmlHead;

public class JXmlHead extends JXmlTree implements XmlHead {

    @Override
    public <Param, Result> Result accept(Visitor<Param, Result> visitor, Param param) {
        return visitor.visitHead(this, param);
    }

    @Override
    public List<XmlAttribute> getAttributes() {
        return null;
    }
}
