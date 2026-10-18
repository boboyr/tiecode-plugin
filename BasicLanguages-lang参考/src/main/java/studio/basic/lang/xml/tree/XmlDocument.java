package studio.basic.lang.xml.tree;

import java.util.List;

public interface XmlDocument extends XmlTree {
    List<XmlTree> getContents();
}
