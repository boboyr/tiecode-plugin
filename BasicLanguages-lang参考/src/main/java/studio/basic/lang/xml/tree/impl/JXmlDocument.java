package studio.basic.lang.xml.tree.impl;

import java.util.ArrayList;
import java.util.List;

import studio.basic.lang.xml.tree.Visitor;
import studio.basic.lang.xml.tree.XmlTree;
import studio.basic.lang.xml.tree.XmlDocument;

public class JXmlDocument extends JXmlTree implements XmlDocument {
    public List<XmlTree> contents = new ArrayList<>();

    @Override
    public List<XmlTree> getContents() {
        return contents;
    }

    @Override
    public <Param, Result> Result accept(Visitor<Param, Result> visitor, Param param) {
        return visitor.visitDocument(this, param);
    }
}
