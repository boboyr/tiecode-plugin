package studio.basic.lang.xml.tree;

public interface XmlAttribute extends XmlTree {
    XmlNamespace getNamespace();

    XmlIdentifier getName();

    XmlLiteral getValue();
}
