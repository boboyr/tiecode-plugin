package studio.basic.lang.xml.tree.impl;

import java.util.ArrayList;
import java.util.List;

import studio.basic.lang.xml.tree.Visitor;
import studio.basic.lang.xml.tree.XmlAttribute;
import studio.basic.lang.xml.tree.XmlElement;
import studio.basic.lang.xml.tree.XmlText;
import studio.basic.lang.xml.tree.XmlTag;

public class JXmlElement extends JXmlTree implements XmlElement {
    public JXmlTag startTag;
    public JXmlTag endTag;
    public List<JXmlAttribute> attributes = new ArrayList<>();
    public List<JXmlElement> children = new ArrayList<>();
    public XmlText text;

    @Override
    public <Param, Result> Result accept(Visitor<Param, Result> visitor, Param param) {
        return visitor.visitElement(this, param);
    }

    @Override
    public XmlTag getStartTag() {
        return startTag;
    }

    @Override
    public List<? extends XmlAttribute> getAttributes() {
        return attributes;
    }

    @Override
    public List<? extends XmlElement> getChildren() {
        return children;
    }

    @Override
    public XmlTag getEndTag() {
        return endTag;
    }

    @Override
    public XmlText getText() {
        return text;
    }
}
