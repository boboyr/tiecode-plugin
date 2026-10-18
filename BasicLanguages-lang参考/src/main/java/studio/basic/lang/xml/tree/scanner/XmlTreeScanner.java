package studio.basic.lang.xml.tree.scanner;

import java.util.List;

import studio.basic.lang.xml.tree.Visitor;
import studio.basic.lang.xml.tree.XmlAttribute;
import studio.basic.lang.xml.tree.XmlElement;
import studio.basic.lang.xml.tree.XmlHead;
import studio.basic.lang.xml.tree.XmlLiteral;
import studio.basic.lang.xml.tree.XmlNamespace;
import studio.basic.lang.xml.tree.XmlNamespaceDeclare;
import studio.basic.lang.xml.tree.XmlText;
import studio.basic.lang.xml.tree.XmlTree;
import studio.basic.lang.xml.tree.XmlDocument;
import studio.basic.lang.xml.tree.XmlIdentifier;
import studio.basic.lang.xml.tree.XmlTag;

public class XmlTreeScanner<Param, Result> implements Visitor<Param, Result> {

    public Result scan(XmlTree node, Param param) {
        if (node != null) {
            return node.accept(this, param);
        }
        return null;
    }

    public void scan(List<? extends XmlTree> nodes, Param param) {
        if (nodes == null) {
            return;
        }
        for (XmlTree node : nodes) {
            scan(node, param);
        }
    }

    @Override
    public Result visitDocument(XmlDocument tree, Param param) {
        scan(tree.getContents(), param);
        return null;
    }

    @Override
    public Result visitHead(XmlHead tree, Param param) {
        scan(tree.getAttributes(), param);
        return null;
    }

    @Override
    public Result visitElement(XmlElement tree, Param param) {
        scan(tree.getStartTag(), param);
        scan(tree.getAttributes(), param);
        scan(tree.getText(), param);
        scan(tree.getChildren(), param);
        scan(tree.getEndTag(), param);
        return null;
    }

    @Override
    public Result visitTag(XmlTag tree, Param param) {
        return null;
    }

    @Override
    public Result visitNamespace(XmlNamespace tree, Param param) {
        return null;
    }

    @Override
    public Result visitNamespaceDeclare(XmlNamespaceDeclare tree, Param param) {
        scan(tree.getNamespace(), param);
        scan(tree.getName(), param);
        scan(tree.getValue(), param);
        return null;
    }

    @Override
    public Result visitAttribute(XmlAttribute tree, Param param) {
        scan(tree.getNamespace(), param);
        scan(tree.getName(), param);
        scan(tree.getValue(), param);
        return null;
    }

    @Override
    public Result visitText(XmlText tree, Param param) {
        return null;
    }

    @Override
    public Result visitIdentifier(XmlIdentifier tree, Param param) {
        return null;
    }

    @Override
    public Result visitLiteral(XmlLiteral tree, Param param) {
        return null;
    }
}
