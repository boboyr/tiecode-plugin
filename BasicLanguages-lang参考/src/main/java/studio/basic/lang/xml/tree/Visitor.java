package studio.basic.lang.xml.tree;

public interface Visitor<Param, Result> {
    Result visitDocument(XmlDocument tree, Param param);
    Result visitHead(XmlHead tree, Param param);
    Result visitElement(XmlElement tree, Param param);
    Result visitTag(XmlTag tree, Param param);
    Result visitNamespace(XmlNamespace tree, Param param);
    Result visitNamespaceDeclare(XmlNamespaceDeclare tree, Param param);
    Result visitAttribute(XmlAttribute tree, Param param);
    Result visitText(XmlText tree, Param param);
    Result visitIdentifier(XmlIdentifier tree, Param param);
    Result visitLiteral(XmlLiteral tree, Param param);
}
