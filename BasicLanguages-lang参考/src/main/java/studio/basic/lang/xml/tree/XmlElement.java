package studio.basic.lang.xml.tree;

import java.util.List;

public interface XmlElement extends XmlTree {
    XmlTag getStartTag();

    List<? extends XmlAttribute> getAttributes();

    List<? extends XmlElement> getChildren();

    XmlTag getEndTag();

    XmlText getText();
}
