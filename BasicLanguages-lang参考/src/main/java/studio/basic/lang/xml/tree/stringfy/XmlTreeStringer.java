package studio.basic.lang.xml.tree.stringfy;

import com.rapid.util.collection.CollectionUtils;

import java.util.List;

import studio.basic.lang.xml.tree.XmlAttribute;
import studio.basic.lang.xml.tree.XmlElement;
import studio.basic.lang.xml.tree.XmlHead;
import studio.basic.lang.xml.tree.XmlLiteral;
import studio.basic.lang.xml.tree.XmlNamespace;
import studio.basic.lang.xml.tree.XmlNamespaceDeclare;
import studio.basic.lang.xml.tree.XmlText;
import studio.basic.lang.xml.tree.XmlTree;
import studio.basic.lang.xml.tree.XmlIdentifier;
import studio.basic.lang.xml.tree.XmlTag;
import studio.basic.lang.xml.tree.scanner.XmlTreeScanner;

public class XmlTreeStringer extends XmlTreeScanner<StringBuilder, Void> {
    private int tab;

    public String print(XmlTree node) {
        StringBuilder builder = new StringBuilder();
        node.accept(this, builder);
        return builder.toString();
    }

    @Override
    public Void visitHead(XmlHead tree, StringBuilder builder) {
        builder.append("<?xml ");
        super.visitHead(tree, builder);
        builder.append("?>");
        printLine(builder);
        return null;
    }

    @Override
    public Void visitElement(XmlElement tree, StringBuilder builder) {
        printTab(tab, builder);
        builder.append("<");
        scan(tree.getStartTag(), builder);
        printLine(builder);
        tab++;
        scan(tree.getAttributes(), builder);
        List<? extends XmlElement> children = tree.getChildren();
        if (CollectionUtils.isEmpty(children) && tree.getEndTag() == null) {
            builder.append("/>");
        } else {
            builder.append(">");
            scan(tree.getText(), builder);
            scan(children, builder);
            builder.append("</");
            scan(tree.getEndTag(), builder);
            builder.append(">");
        }
        printLine(builder);
        tab--;
        return null;
    }

    @Override
    public Void visitTag(XmlTag tree, StringBuilder builder) {
        builder.append(tree.getText());
        return null;
    }

    @Override
    public Void visitNamespace(XmlNamespace tree, StringBuilder builder) {
        builder.append(tree.getText());
        return null;
    }

    @Override
    public Void visitNamespaceDeclare(XmlNamespaceDeclare tree, StringBuilder builder) {
        visitAttribute(tree, builder);
        return null;
    }

    @Override
    public Void visitAttribute(XmlAttribute tree, StringBuilder builder) {
        printTab(tab, builder);
        scan(tree.getNamespace(), builder);
        builder.append(":");
        scan(tree.getName(), builder);
        builder.append("=");
        scan(tree.getValue(), builder);
        printLine(builder);
        return null;
    }

    @Override
    public Void visitText(XmlText tree, StringBuilder builder) {
        builder.append(tree.getText());
        return null;
    }

    @Override
    public Void visitIdentifier(XmlIdentifier tree, StringBuilder builder) {
        builder.append(tree.getText());
        return null;
    }

    @Override
    public Void visitLiteral(XmlLiteral tree, StringBuilder builder) {
        builder.append(tree.getText());
        return null;
    }

    private void printTab(int n, StringBuilder builder) {
        for (int i = 0; i < n; i++) {
            builder.append("\t");
        }
    }

    private void printLine(StringBuilder builder) {
        builder.append("\n");
    }
}
